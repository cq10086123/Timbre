import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// 播放内核：AVPlayer（苹果原生播放器）+ 后台播放 + 锁屏 / 蓝牙控制
/// 支持本地文件与接口源直链（带 Referer / Cookie 等请求头）
final class PlaybackEngine: NSObject, ObservableObject {
  @Published private(set) var bookId: String?
  @Published private(set) var chapterIndex: Int = 0
  /// 章内第几个分段（对齐安卓的 markIndex）—— 播放的最小单位是分段
  @Published private(set) var markIndex: Int = 0
  @Published private(set) var isPlaying = false
  @Published private(set) var position: Double = 0
  @Published private(set) var duration: Double = 0
  @Published private(set) var isLoading = false
  @Published private(set) var speed: Double = 1.0
  @Published private(set) var sleepTimerEnd: Date?
  @Published private(set) var sleepAtChapterEnd = false
  @Published private(set) var sleepRemaining: Double = 0
  @Published var errorMessage: String?
  /// 终极兜底（整集下载后播放）的进度 0~1；nil = 没在下载。
  /// 63MB 的 DASH 音轨要下一会儿，播放键上得能看到百分比，不然用户以为又卡死了。
  @Published private(set) var downloadProgress: Double?

  private let player = AVPlayer()
  private let library: LibraryStore
  private let settings: SettingsStore
  private let extensions: ExtensionStore
  private let webdav: WebDavStore
  /// WebDAV 书的章节预取队列
  private let webDavPrefetcher = WebDavPrefetcher()

  private var timeObserverToken: Any?
  private var endObserver: NSObjectProtocol?
  private var failedObserver: NSObjectProtocol?
  private var errorLogObserver: NSObjectProtocol?
  private var statusObservation: NSKeyValueObservation?
  private var interruptionObserver: NSObjectProtocol?
  private var routeObserver: NSObjectProtocol?
  private var sleepTicker: Timer?
  private var fadeTimer: Timer?
  private var saveTick = 0
  private var outroHandled = false
  private var resumeAfterLoad = false
  private var shouldResumeAfterInterruption = false
  private var pendingResume: Double = 0
  /// 每次加载递增，异步解析回来时校验，避免旧章节覆盖新章节
  private var loadToken = 0
  /// resourceLoader.delegate 是 weak，必须由这里强引用住，否则代理立刻失效
  private var proxyLoader: HeaderProxyLoader?
  /// 容器声明方式：0 = com.apple.m4a-audio，1 = public.mpeg-4
  private var proxyVariant = 0
  /// 取流策略阶梯：0 = 代理+m4a，1 = 代理+mp4，2 = 整集下载后播放，3 = 原生补头（不走代理）。
  /// 每爬一级重装一次 item，最多爬三级。
  private var streamStage = 0
  /// 第 2 档的整集下载任务：切章 / 重试时要作废，别让它在后台白下 63MB
  private var fallbackDownloadTask: Task<Void, Never>?
  /// 换档复用：本集已解析出的直链与请求头。
  /// 接口源脚本跑一遍要好几秒（B 站要 finger/wbi/playurl 四连），
  /// 换取流档位时直链又没过期，没理由重跑一遍脚本。
  private var lastResolved: ResolvedStream?

  private struct ResolvedStream {
    let url: URL
    let headers: [String: String]
    let bookId: String
    let chapterId: String
  }
  /// 就绪看门狗：数据在流进来但 item 迟迟不变 ready，说明容器声明对不上，换下一级
  private var readyWatchdog: Timer?
  /// 已经喂给播放器多少字节（判断「慢」还是「解不了」）
  private var lastBufferedBytes: Int64 = 0
  /// 播放器状态变化（等待原因 / 是否卡顿）
  private var timeControlObservation: NSKeyValueObservation?
  /// 当前这一章解析出来的直链，重试时不用再跑一遍 JS
  private var activeLoad: (book: Book, chapter: Chapter)?
  /// 音量提升 + 跳过空白，走 MTAudioProcessingTap
  private let audioProcessor = AudioProcessor()
  /// 在线书源播放地址预加载（「播放器设置」里的那三个数值 + 开关）
  private let preloader = OnlinePreloader()

  init(
    library: LibraryStore,
    settings: SettingsStore,
    extensions: ExtensionStore,
    webdav: WebDavStore
  ) {
    self.library = library
    self.settings = settings
    self.extensions = extensions
    self.webdav = webdav
    super.init()
    player.automaticallyWaitsToMinimizeStalling = true
    // 卡住时最重要的是知道「在等什么」：网络慢、缓冲空、还是解码卡住，处理方式完全不同
    timeControlObservation = player.observe(\.timeControlStatus, options: [.new]) {
      [weak self] player, _ in
      guard let self, player.timeControlStatus == .waitingToPlayAtSpecifiedRate else { return }
      let reason = player.reasonForWaitingToPlay?.rawValue ?? "-"
      DLog(
        "player",
        "等待播放 原因=\(reason) 缓冲空=\(self.player.currentItem?.isPlaybackBufferEmpty ?? false) "
          + "已喂入=\(Format.bytes(self.proxyLoader?.deliveredBytes ?? 0))"
      )
    }
    configureAudioSession()
    configureRemoteCommands()
    installObservers()
  }

  deinit {
    readyWatchdog?.invalidate()
    fallbackDownloadTask?.cancel()
    timeControlObservation?.invalidate()
    if let timeObserverToken { player.removeTimeObserver(timeObserverToken) }
    for observer in [endObserver, failedObserver, errorLogObserver, interruptionObserver, routeObserver] {
      if let observer { NotificationCenter.default.removeObserver(observer) }
    }
    statusObservation?.invalidate()
    sleepTicker?.invalidate()
    fadeTimer?.invalidate()
  }

  // MARK: - 计算属性

  var currentBook: Book? {
    guard let bookId else { return nil }
    return library.book(withId: bookId)
  }

  var currentChapter: Chapter? {
    guard let book = currentBook, book.chapters.indices.contains(chapterIndex) else { return nil }
    return book.chapters[chapterIndex]
  }

  /// 当前章的所有分段
  var currentMarks: [ChapterMark] { currentChapter?.chapterMarks ?? [] }

  /// 当前分段。没有 markData 的章节只有一个覆盖整章的分段。
  var currentMark: ChapterMark? {
    let marks = currentMarks
    guard marks.indices.contains(markIndex) else { return marks.first }
    return marks[markIndex]
  }

  /// 当前播放单元的名字（分段名优先，退回章节名）
  var currentUnitTitle: String {
    if let name = currentMark?.name, !name.isEmpty { return name }
    return currentChapter?.title ?? ""
  }

  /// 全局第几个播放单元（跨章累加，1 起）—— 对齐安卓章节抽屉的编号
  var currentUnitNumber: Int {
    guard let book = currentBook else { return markIndex + 1 }
    var count = 0
    for index in 0..<min(chapterIndex, book.chapters.count) {
      count += book.chapters[index].chapterMarks.count
    }
    return count + markIndex + 1
  }

  var progressFraction: Double {
    duration > 0 ? min(1, max(0, position / duration)) : 0
  }

  var remaining: Double { max(0, duration - position) }

  // MARK: 分段相对进度（安卓播放页显示的就是分段时长，不是整章时长）

  /// 当前分段在章内的起点
  var unitStart: Double { currentMark?.start ?? 0 }

  /// 当前分段时长。单分段的章节就等于整章时长。
  var unitDuration: Double {
    guard let mark = currentMark else { return duration }
    return mark.duration > 0 ? mark.duration : duration
  }

  /// 分段内已播秒数
  var unitPosition: Double { max(0, position - unitStart) }

  var unitProgressFraction: Double {
    unitDuration > 0 ? min(1, max(0, unitPosition / unitDuration)) : 0
  }

  var sleepActive: Bool { sleepAtChapterEnd || sleepTimerEnd != nil }

  func title(forChapter index: Int) -> String {
    guard let book = currentBook, book.chapters.indices.contains(index) else { return "" }
    return book.chapters[index].title
  }

  // MARK: - 打开 / 播放

  /// 打开一本书 / 一章 / 一个分段。
  ///
  /// - `markIndex` 非 nil：从这个分段的开头开始（章节抽屉点某一项）
  /// - `resumeAt` 非 nil：从这个秒数开始（书签、断点续播）
  /// - 两者都没给：按书里记的断点
  func open(
    _ book: Book,
    chapterIndex: Int = 0,
    markIndex: Int? = nil,
    autoplay: Bool = true,
    resumeAt: Double? = nil
  ) {
    guard !book.chapters.isEmpty else {
      errorMessage = "这本书还没有章节"
      return
    }
    let index = min(max(0, chapterIndex), book.chapters.count - 1)
    let marks = book.chapters[index].chapterMarks
    let requestedMark = markIndex.map { min(max(0, $0), max(0, marks.count - 1)) }

    // 起点：显式给分段就从分段头开始，否则用给定秒数 / 书里的断点
    let resume: Double
    if let requestedMark, marks.indices.contains(requestedMark) {
      resume = marks[requestedMark].start
    } else if let resumeAt {
      resume = max(0, resumeAt)
    } else if book.position.chapterIndex == index {
      resume = max(0, book.position.seconds)
    } else {
      resume = marks.first?.start ?? 0
    }
    let resolvedMark = requestedMark ?? book.markIndex(chapterIndex: index, seconds: resume)

    // 从书架点回「正在播的这本书」不能把播放停掉：目标没变就别重来一遍
    let sameTarget = self.bookId == book.id
      && self.chapterIndex == index
      && self.markIndex == resolvedMark
      && player.currentItem != nil
    self.bookId = book.id
    self.chapterIndex = index
    self.markIndex = min(max(0, resolvedMark), max(0, marks.count - 1))
    self.speed = book.speed > 0 ? book.speed : 1.0
    self.outroHandled = false
    self.resumeAfterLoad = autoplay
    // 换书 / 换章：取流策略阶梯与重试计数都归零
    self.streamStage = 0
    self.proxyVariant = 0
    if autoplay {
      load(book: book, chapter: book.chapters[index], resume: resume)
    } else if !sameTarget {
      prepare(book: book, resume: resume)
    } else if markIndex != nil {
      // 暂停着点自己在听的那一项：只把位置挪到这一段开头，不重新取流
      seek(to: resume)
    }
  }

  /// 只做准备、不取流也不播 —— 对齐安卓：`BookPlayScreen` 只展示当前播放状态，
  /// 进播放页不会自己去解析直链（`OnlinePlaybackPreloader` 也要等真正开播才动）。
  ///
  /// 之前这里直接 `load()`，结果一进播放页就启动接口源脚本、拉整段音频、
  /// 而且 `autoplay` 还顺手把播放点了 —— 用户看到的就是「自己跳进来自己就播了」。
  private func prepare(book: Book, resume: Double) {
    // 作废还在跑的上一次解析，别让它一会儿把 item 装回来
    loadToken += 1
    activeLoad = nil
    player.pause()
    player.replaceCurrentItem(with: nil)
    isPlaying = false
    isLoading = false
    downloadProgress = nil
    errorMessage = nil
    pendingResume = resume
    position = resume
    duration = book.chapters.indices.contains(chapterIndex)
      ? max(0, book.chapters[chapterIndex].duration)
      : 0
    markIndex = book.markIndex(chapterIndex: chapterIndex, seconds: resume)
    saveTick = 0
    updateNowPlayingInfo()
    DLog(
      "player",
      "准备第\(chapterIndex + 1)集·第\(markIndex + 1)分段（未取流，等播放键） 起点=\(Int(resume))s"
    )
  }

  func togglePlayPause() {
    // 用户报过「有时候要点两次才播」：把点击当下的状态记下来，
    // 下次看日志就能分辨是点击被吃掉（没有这一行）还是状态没生效（有但没播）
    DLog(
      "player",
      "点击播放键 当前=\(player.timeControlStatus == .playing ? "播放" : "暂停")"
        + " 期望=\(player.timeControlStatus == .playing ? "暂停" : "播放")"
        + " isLoading=\(isLoading) item=\(player.currentItem == nil ? "无" : "有")"
    )
    if player.timeControlStatus == .playing {
      pause()
    } else {
      play()
    }
  }

  func play() {
    guard player.currentItem != nil else {
      // 进播放页时只做了准备（没取流），第一次按播放才真正开始加载
      if let book = currentBook {
        open(book, chapterIndex: chapterIndex, autoplay: true, resumeAt: pendingResume)
      }
      return
    }
    let wasPlaying = isPlaying
    activateAudioSession()
    player.playImmediately(atRate: Float(speed))
    isPlaying = true
    if let bookId { library.touchLastPlayed(bookId: bookId) }
    updateNowPlayingInfo()
    if !wasPlaying { autoEnableSleepTimerIfNeeded() }
  }

  /// 对齐安卓 AutoEnableSleepTimer：开始播放时若处于时间窗口内，自动开启睡眠定时器（默认 20 分钟）
  private func autoEnableSleepTimerIfNeeded() {
    let prefs = settings.settings
    guard prefs.autoSleepEnabled, !sleepActive else { return }
    guard
      AutoSleepWindow.contains(
        startMinute: prefs.autoSleepStartMinute,
        endMinute: prefs.autoSleepEndMinute
      )
    else { return }
    startSleepTimer(minutes: 20)
  }

  func pause(autoRewind: Bool = true) {
    player.pause()
    isPlaying = false
    if autoRewind, settings.settings.autoRewindSeconds > 0, position > 1 {
      let target = max(0, position - Double(settings.settings.autoRewindSeconds))
      seek(to: target)
    }
    savePosition(force: true)
    updateNowPlayingInfo()
  }

  func stop() {
    player.pause()
    player.replaceCurrentItem(with: nil)
    fallbackDownloadTask?.cancel()
    fallbackDownloadTask = nil
    downloadProgress = nil
    isPlaying = false
    position = 0
    duration = 0
    bookId = nil
    chapterIndex = 0
    markIndex = 0
    cancelSleepTimer()
    MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
  }

  func seek(to seconds: Double) {
    let upper = duration > 0 ? duration : seconds
    let clamped = max(0, min(seconds, upper))
    player.seek(
      to: CMTime(seconds: clamped, preferredTimescale: 600),
      toleranceBefore: .zero,
      toleranceAfter: .zero
    )
    position = clamped
    if let book = currentBook {
      markIndex = book.markIndex(chapterIndex: chapterIndex, seconds: clamped)
    }
    // 还没取流就拖进度条：记住目标位置，按播放时从这里开始
    if player.currentItem == nil { pendingResume = clamped }
    updateNowPlayingInfo()
  }

  /// 快进 / 快退。
  ///
  /// 安卓的 `seekBackBy` 是跨 media item 走的（`crossMediaItems = true`），
  /// 一个分段不够扣就把余量带到相邻分段。这里在同一章的分段之间做同样的换算；
  /// 跨章不做（那要重新取流，收益太小）。
  func skip(_ delta: Double) {
    guard delta != 0 else { return }
    let marks = currentMarks
    guard marks.indices.contains(markIndex) else {
      seek(to: position + delta)
      return
    }
    var index = markIndex
    var target = position + delta

    if delta > 0 {
      while target > marks[index].end {
        guard index + 1 < marks.count else {
          target = marks[index].end
          break
        }
        let carry = target - marks[index].end
        index += 1
        target = marks[index].start + carry
      }
    } else {
      while target < marks[index].start {
        guard index > 0 else {
          target = 0
          break
        }
        let carry = marks[index].start - target
        index -= 1
        target = marks[index].end - carry
      }
    }
    seek(to: max(0, target))
  }

  func seekToFraction(_ fraction: Double) {
    guard duration > 0 else { return }
    seek(to: duration * min(1, max(0, fraction)))
  }

  /// 用户的播放意图：按过播放键、还没按暂停。缓冲中（`waitingToPlayAtSpecifiedRate`）也算在播。
  ///
  /// 不要用 `isPlaying` 判断切章要不要接着播 —— 它每 0.5 秒按
  /// `timeControlStatus == .playing` 同步一次，缓冲那一下会被刷成 false，
  /// 于是在缓冲期切章就会变成「切完停住了」。
  var wantsToPlay: Bool { player.timeControlStatus != .paused }

  /// 切章时要不要接着播。
  ///
  /// 对齐安卓：`next()` / `previous()` 走的是 ExoPlayer 的 `seekToNextMediaItem`，
  /// 它只换 item，**不动 playWhenReady** —— 所以暂停状态切章切完还是暂停，
  /// 只有正在播的时候切才继续播。`onChapterClick` 同理，只 `setPosition`。
  /// 之前这里一律 `autoplay: true`，暂停着点下一集就自己响起来了。
  private var chapterSwitchAutoplay: Bool { wantsToPlay }

  /// 同章内跳到另一个分段：只 seek，不重建 item，所以播放状态天然不变
  private func jump(toMark index: Int, autoplay: Bool) {
    guard let chapter = currentChapter else { return }
    let marks = chapter.chapterMarks
    guard marks.indices.contains(index) else { return }
    markIndex = index
    outroHandled = false
    seek(to: marks[index].start)
    if autoplay, player.currentItem == nil {
      // 还没取流（进播放页只做了准备）：把待播位置改到这一段再起播
      pendingResume = marks[index].start
      play()
    }
    DLog("player", "跳到第\(chapterIndex + 1)集·第\(index + 1)分段 \(marks[index].name ?? "")")
  }

  /// 下一集 / 下一分段 —— 对齐安卓 `forceSeekToNext`：始终走下一项
  func nextChapter(autoplay: Bool? = nil) {
    guard let book = currentBook else { return }
    let shouldPlay = autoplay ?? chapterSwitchAutoplay
    let marks = currentMarks
    if markIndex + 1 < marks.count {
      jump(toMark: markIndex + 1, autoplay: shouldPlay)
      return
    }
    if chapterIndex + 1 < book.chapters.count {
      open(book, chapterIndex: chapterIndex + 1, markIndex: 0, autoplay: shouldPlay)
    } else {
      player.pause()
      isPlaying = false
      library.markFinished(bookId: book.id)
      updateNowPlayingInfo()
    }
  }

  /// 上一集 / 上一分段 —— 对齐安卓 `forceSeekToPrevious`：
  /// 永远是「上一项」，不回退到当前项开头（那个是老版本误以为按钮坏了的原因）
  func previousChapter(autoplay: Bool? = nil) {
    guard let book = currentBook else { return }
    let shouldPlay = autoplay ?? chapterSwitchAutoplay
    if markIndex > 0 {
      jump(toMark: markIndex - 1, autoplay: shouldPlay)
      return
    }
    if chapterIndex > 0 {
      let previous = chapterIndex - 1
      let count = book.chapters[previous].chapterMarks.count
      open(
        book,
        chapterIndex: previous,
        markIndex: max(0, count - 1),
        autoplay: shouldPlay
      )
    } else {
      seek(to: 0)
    }
  }

  func setSpeed(_ value: Double) {
    speed = min(4.0, max(0.5, value))
    if player.timeControlStatus == .playing {
      player.rate = Float(speed)
    }
    if let book = currentBook, let index = library.index(of: book.id) {
      library.books[index].speed = speed
      library.save(delay: 1)
    }
    updateNowPlayingInfo()
  }

  // MARK: - 睡眠定时器

  func startSleepTimer(minutes: Int) {
    cancelSleepTimer()
    guard minutes > 0 else { return }
    sleepTimerEnd = Date().addingTimeInterval(Double(minutes) * 60)
    sleepRemaining = Double(minutes) * 60
    startSleepTicker()
  }

  func startSleepAtChapterEnd() {
    cancelSleepTimer()
    sleepAtChapterEnd = true
  }

  func extendSleepTimer(minutes: Int) {
    guard let end = sleepTimerEnd else {
      startSleepTimer(minutes: minutes)
      return
    }
    sleepTimerEnd = end.addingTimeInterval(Double(minutes) * 60)
    sleepRemaining = max(0, end.addingTimeInterval(Double(minutes) * 60).timeIntervalSinceNow)
  }

  func cancelSleepTimer() {
    sleepTicker?.invalidate()
    sleepTicker = nil
    fadeTimer?.invalidate()
    fadeTimer = nil
    sleepTimerEnd = nil
    sleepAtChapterEnd = false
    sleepRemaining = 0
    player.volume = 1
  }

  // MARK: - 书签

  func addBookmark(title: String = "") {
    guard let book = currentBook, !book.chapters.isEmpty else { return }
    let unitTitle = currentUnitTitle
    let label = title.isEmpty
      ? "\(unitTitle) \(Format.time(position))"
      : title
    let merged = unitTitle.isEmpty ? label : "\(unitTitle) · \(label)"
    library.addBookmark(
      bookId: book.id,
      chapterIndex: chapterIndex,
      seconds: position,
      title: merged
    )
  }

  /// 跳到某个书签的位置
  func seek(toBookmark bookmark: Bookmark) {
    guard let book = currentBook else { return }
    if bookmark.chapterIndex == chapterIndex {
      seek(to: bookmark.seconds)
    } else {
      open(
        book,
        chapterIndex: bookmark.chapterIndex,
        autoplay: isPlaying,
        resumeAt: bookmark.seconds
      )
    }
  }

  // MARK: - 加载

  private func load(book: Book, chapter: Chapter, resume: Double, isRetry: Bool = false) {
    isLoading = true
    errorMessage = nil
    pendingResume = resume
    loadToken += 1
    let token = loadToken
    activeLoad = (book, chapter)
    // 切章/换书先拆掉正在跑的预加载链条，新章从零开始（对齐安卓）
    preloader.cancelChain()
    // 新一轮加载：上一轮的就绪看门狗作废，字节计数器也归零
    // （不归零的话，第 2 级用的是上一个代理的旧计数，会被瞬间判成「又不就绪」）
    readyWatchdog?.invalidate()
    readyWatchdog = nil
    proxyLoader = nil
    lastBufferedBytes = 0
    // 上一轮如果还在整集下载，立刻作废（Task 里按 token 校验，不会误装旧章）
    fallbackDownloadTask?.cancel()
    fallbackDownloadTask = nil
    downloadProgress = nil
    if !isRetry {
      streamStage = 0
      proxyVariant = 0
      lastResolved = nil
    }

    if book.kind == .local {
      guard let path = chapter.localFile else {
        fail("缺少音频文件")
        return
      }
      let url = AppPaths.url(for: path)
      guard FileManager.default.fileExists(atPath: url.path) else {
        fail("音频文件已被删除：\(url.lastPathComponent)")
        return
      }
      install(item: AVPlayerItem(url: url), book: book, chapter: chapter)
      return
    }

    // 手动缓存过这一集：直接播本地文件，断网也能听
    if book.kind == .jdr, let cached = cachedChapterFile(book: book, chapter: chapter) {
      DLog("player", "命中手动缓存 第\(chapter.title)")
      // `.audio` 后缀系统认不出（-11828），按文件头嗅探真实格式播硬链接别名
      install(
        item: AVPlayerItem(url: CachedAudioAlias.playableURL(for: cached)),
        book: book,
        chapter: chapter
      )
      return
    }

    // WebDAV：直连 NAS 上的文件，靠 Authorization 头过认证
    if book.kind == .webdav {
      guard let serverId = book.webdavServerId, let server = webdav.server(withId: serverId) else {
        fail("WebDAV 服务器已被删除，请重新添加后再播放")
        return
      }
      // 预取过这一集：直接读本地，比再走一遍 NAS 快得多，断网也能听
      if WebDavPrefetchCache.isCached(
        serverId: serverId,
        bookId: book.id,
        chapterId: chapter.id
      ) {
        DLog("player", "命中 WebDAV 预取缓存 第\(chapter.title)")
        // `.audio` 后缀系统认不出（-11828），按文件头嗅探真实格式播硬链接别名
        install(
          item: AVPlayerItem(
            url: CachedAudioAlias.playableURL(
              for: WebDavPrefetchCache.file(
                serverId: serverId,
                bookId: book.id,
                chapterId: chapter.id
              )
            )
          ),
          book: book,
          chapter: chapter
        )
        return
      }
      guard let remote = chapter.remoteURL, let url = URL(string: remote) else {
        fail("这本远程书缺少文件地址，请重新导入")
        return
      }
      let headers = WebDavClient.authHeaders(
        server: server,
        password: webdav.password(for: serverId)
      )
      // Basic 认证头必须每个子请求都带，否则 Range 请求会被 401 —— 统一走流代理
      install(
        item: AVPlayerItem(asset: makeAsset(url: url, headers: headers, tag: "WebDAV")),
        book: book,
        chapter: chapter
      )
      return
    }

    // 换档重试：直链没换、也没过期，直接复用上一次的解析结果，
    // 别再跑一遍源脚本（B 站脚本一轮 finger/wbi/playurl 要 5 秒+）
    if isRetry,
      let resolved = lastResolved,
      resolved.bookId == book.id,
      resolved.chapterId == chapter.id
    {
      DLog("player", "复用已解析直链，跳过源脚本")
      installResolved(
        url: resolved.url,
        headers: resolved.headers,
        book: book,
        chapter: chapter,
        token: token
      )
      return
    }

    // 源脚本 / allowInsecure 在主线程取好快照，后台只做网络与 JS 执行，避免跨线程读 ExtensionStore
    guard let sourceId = book.sourceId,
      let installed = extensions.enabledSources.first(where: { $0.id == sourceId })
    else {
      fail("接口源未启用或已被删除")
      return
    }
    guard let script = extensions.scriptText(for: installed) else {
      fail("找不到该源的脚本文件")
      return
    }
    let remote = RemoteContext(
      script: script,
      allowInsecure: installed.allowInsecure,
      remoteBookId: book.remoteBookId ?? book.id
    )

    // 预加载命中：直接装，不用再等一次源接口（切章秒开的关键）
    if let cached = preloader.take(chapterId: chapter.id) {
      DLog("preload", "命中预加载 第\(chapter.title)")
      installResolved(
        url: cached.url,
        headers: cached.headers,
        book: book,
        chapter: chapter,
        token: token
      )
      return
    }

    Task { [weak self] in
      guard let self else { return }
      do {
        let resolved = try await self.resolveRemote(chapter: chapter, context: remote)
        await MainActor.run {
          guard self.loadToken == token else { return }
          self.installResolved(
            url: resolved.url,
            headers: resolved.headers,
            book: book,
            chapter: chapter,
            token: token
          )
        }
      } catch {
        await MainActor.run {
          guard self.loadToken == token else { return }
          self.fail(Self.sourceErrorMessage(error))
        }
      }
    }
  }

  /// 接口源直链拿到后的统一出口：记录直链供换档复用；DASH 分片直跳整集下载；其余直接装。
  private func installResolved(
    url: URL,
    headers: [String: String],
    book: Book,
    chapter: Chapter,
    token: Int
  ) {
    lastResolved = ResolvedStream(
      url: url,
      headers: headers,
      bookId: book.id,
      chapterId: chapter.id
    )

    // `.m4s` 是 DASH 分片，天生就是分片式 fMP4（moov 里没有采样表、全靠 moof 逐段索引）。
    // 三个版本的实测都证明 iOS 的 AVFoundation 对它走流式永远解不出可靠索引
    // （喂几 MB 不就绪，即便「就绪」了时长也是错的），安卓 ExoPlayer 能边下边解是引擎差异。
    // 所以按**容器格式**（不是按哪家源）直接走整集下载：跳过两轮注定失败的流式尝试，
    // 下载带宽好的话十几秒完事，且落了缓存以后秒开。
    if streamStage < 2, url.pathExtension.lowercased() == "m4s" {
      DLog("player", "DASH 分片容器（fMP4）流式解不动，直接整集下载后播放")
      streamStage = 2
      proxyVariant = 1
      downloadThenPlay(url: url, headers: headers, book: book, chapter: chapter, token: token)
      return
    }

    if streamStage == 2 {
      downloadThenPlay(url: url, headers: headers, book: book, chapter: chapter, token: token)
      return
    }
    let asset = makeAsset(url: url, headers: headers, tag: "接口源")
    install(item: AVPlayerItem(asset: asset), book: book, chapter: chapter)
  }

  /// 第 2 档兜底：流式喂进去解不动的容器（典型是 DASH 分片音轨），把整集下到
  /// 在线缓存目录再播本地文件。**对任何源通用**：只要服务器肯给字节就能播，
  /// 不对任何一家做特判。文件落在线缓存 —— 这一集以后再播直接命中缓存，秒开。
  private func downloadThenPlay(
    url: URL,
    headers: [String: String],
    book: Book,
    chapter: Chapter,
    token: Int
  ) {
    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    let fileURL = OnlineChapterFileCache.file(
      source: source,
      bookId: book.id,
      chapterId: chapter.id
    )
    let tmpURL = fileURL.appendingPathExtension("part")
    DLog("player", "终极兜底：整集下载后播放 \(url.host ?? "-")\(url.path)")
    isLoading = true
    downloadProgress = 0
    fallbackDownloadTask = Task { [weak self] in
      guard let self else { return }
      let outcome = await ChapterDownloader.download(
        url: url,
        headers: HeaderProxyLoader.mergedHeaders(headers),
        tmpURL: tmpURL,
        fileURL: fileURL
      ) { [weak self] written, expected in
        guard let self, expected > 0 else { return }
        let fraction = min(1, Double(written) / Double(expected))
        Task { @MainActor in
          guard self.loadToken == token else { return }
          self.downloadProgress = fraction
        }
      }
      await MainActor.run {
        guard self.loadToken == token else { return }
        self.downloadProgress = nil
        switch outcome {
        case .stored:
          DLog("player", "整集下载完成，改播本地文件 第\(chapter.title)")
          // `.audio` 后缀系统认不出（-11828），按文件头嗅探真实格式播硬链接别名
          self.install(
            item: AVPlayerItem(url: CachedAudioAlias.playableURL(for: fileURL)),
            book: book,
            chapter: chapter
          )
        case .failed(_, let detail):
          DLog("player", "✗ 整集下载失败 \(detail ?? "-")")
          // 下载失败还有最后一档（原生补头），别急着弹错误
          if !self.escalateStreamStage(reason: "整集下载失败（\(detail ?? "未知")）") {
            self.fail("拉流失败：\(detail ?? "直链可能已过期")")
          }
        }
      }
    }
  }

  /// 统一出口：决定这一条直链要不要套流代理。
  ///
  /// - 带请求头（接口源返回 `{url, headers}`、WebDAV 的 Authorization）→ 必须走代理，
  ///   否则 AVFoundation 补发的 Range 子请求可能丢头，CDN 直接 403。
  /// - 后缀是 DASH 分片（`.m4s`）→ 走代理，把 content-type 修成 mp4，
  ///   否则 AVFoundation 判不出容器会静默 `status = .failed`。
  /// - 其余普通直链（mp3/m4a 之类）→ 保持原生路径，不引入额外一层。
  ///
  /// `AVURLAssetHTTPHeaderFieldsKey` 在 iOS SDK 里没有导出成符号，只能按文档里的字符串键传。
  private static let httpHeaderFieldsKey = "AVURLAssetHTTPHeaderFieldsKey"

  private func makeAsset(url: URL, headers: [String: String], tag: String) -> AVURLAsset {
    // 第 4 级：怀疑是我们这层代理喂字节的方式让播放器解不动，退回系统自带补头。
    // 缺点是 AVFoundation 补发的子请求不一定继承这些头，所以只放在最后一档试。
    if streamStage >= 3, !headers.isEmpty {
      DLog(
        "player",
        "\(tag) 原生补头 \(url.host ?? "-")\(url.path) 头=\(headers.keys.sorted())"
      )
      // 这个常量在 iOS SDK 里没有公开，只能按文档里那个字符串键传
      return AVURLAsset(
        url: url,
        options: [Self.httpHeaderFieldsKey: headers]
      )
    }

    let needsProxy = !headers.isEmpty || url.pathExtension.lowercased() == "m4s"
    guard needsProxy else {
      DLog("player", "\(tag) 直连 \(url.host ?? "-")\(url.path) (无头，原生路径)")
      return AVURLAsset(url: url)
    }
    DLog(
      "player",
      "\(tag) 代理 \(url.host ?? "-")\(url.path) 头=\(headers.keys.sorted()) 阶段=\(streamStage) 声明=\(proxyVariant)"
    )
    proxyLoader = HeaderProxyLoader(
      origin: url,
      headers: headers,
      contentTypeVariant: proxyVariant
    )
    return proxyLoader?.makeAsset() ?? AVURLAsset(url: url)
  }

  /// 一级一级往下换取流策略：代理+m4a → 代理+mp4 → 整集下载 → 原生补头。
  ///
  /// 1.0.23 的日志证明：B 站这类 DASH 分片音轨在前两档「喂 3MB+ 仍不就绪」、
  /// 原生补头档又被 mCDN 防盗链 -11828 拒掉。所以中间加了「整集下载后播放」——
  /// 只要服务器肯给字节就能播，是真正意义上与源无关的兜底。
  @discardableResult
  private func escalateStreamStage(reason: String) -> Bool {
    guard streamStage < 3, let context = activeLoad else { return false }
    streamStage += 1
    proxyVariant = streamStage == 0 ? 0 : 1
    let next: String
    switch streamStage {
    case 1: next = "代理 + public.mpeg-4"
    case 2: next = "整集下载后播放"
    case 3: next = "原生补头（不走代理）"
    default: next = "?"
    }
    DLog("player", "⚠️ \(reason)，换第 \(streamStage + 1) 种取流方式：\(next)")
    load(
      book: context.book,
      chapter: context.chapter,
      resume: pendingResume,
      isRetry: true
    )
    return true
  }

  /// 数据一直在进，item 却迟迟不就绪 —— 隔 2 秒看一眼，够字节数就换下一级
  private func armReadyWatchdog(token: Int) {
    readyWatchdog?.invalidate()
    var ticks = 0
    let timer = Timer.scheduledTimer(withTimeInterval: 2, repeats: true) { [weak self] timer in
      guard let self, self.loadToken == token else {
        timer.invalidate()
        return
      }
      ticks += 1
      guard let item = self.player.currentItem else {
        timer.invalidate()
        return
      }
      if item.status == .readyToPlay || item.status == .failed {
        timer.invalidate()
        return
      }
      let fed = self.proxyLoader?.deliveredBytes ?? 0
      self.lastBufferedBytes = fed
      // 3 MB 还没解出容器，基本可以断定不是网慢，是声明/取流方式不对
      if fed >= 3_000_000 {
        timer.invalidate()
        if !self.escalateStreamStage(reason: "已喂入 \(Format.bytes(fed)) 仍未就绪") {
          DLog("player", "✗ 四种取流方式都不就绪，放弃这一集")
          self.fail("这个源的音频容器解不开（已换四种方式重试），换一集或换个书源试试")
        }
        return
      }
      // 什么数据都没进，20 秒还没动静也算异常
      if ticks >= 10 {
        timer.invalidate()
        if !self.escalateStreamStage(reason: "20 秒未收到数据也未就绪") {
          self.fail("拉不到音频数据，直链可能已过期")
        }
      }
    }
    readyWatchdog = timer
  }

  private func install(item: AVPlayerItem, book: Book, chapter: Chapter) {
    player.replaceCurrentItem(with: item)
    observePlaybackFailure(item: item)
    applyAudioProcessing(to: item, book: book)
    duration = chapter.duration
    isLoading = false

    var target = pendingResume
    if target <= 0.5 {
      if book.skipIntro > 0, book.introSkippedChapter != chapterIndex {
        target = Double(book.skipIntro)
        library.markIntroSkipped(bookId: book.id, chapterIndex: chapterIndex)
      } else {
        target = 0
      }
    }
    position = target
    markIndex = book.markIndex(chapterIndex: chapterIndex, seconds: target)
    if target > 0 {
      player.seek(
        to: CMTime(seconds: target, preferredTimescale: 600),
        toleranceBefore: .zero,
        toleranceAfter: .zero
      )
    }
    if resumeAfterLoad {
      activateAudioSession()
      player.playImmediately(atRate: Float(speed))
      isPlaying = true
      library.touchLastPlayed(bookId: book.id)
    }
    saveTick = 0
    updateNowPlayingInfo()
    driveWebDavPrefetch()
    // 本地文件（含命中缓存的）不需要盯；只有走网络的才需要判断「是不是解不动」
    if book.kind != .local,
      let asset = item.asset as? AVURLAsset,
      !asset.url.isFileURL
    {
      armReadyWatchdog(token: loadToken)
    }
  }

  /// WebDAV 书的章节预取：按设置把后面的几集先拉到本地，断网/慢盘时不至于卡
  private func driveWebDavPrefetch() {
    guard let book = currentBook, book.kind == .webdav else {
      webDavPrefetcher.cancel()
      return
    }
    webDavPrefetcher.schedule(
      book: book,
      from: chapterIndex,
      settings: settings.settings,
      webdav: webdav
    )
  }

  private func fail(_ message: String) {
    isLoading = false
    isPlaying = false
    errorMessage = message
  }

  /// 本地缓存文件解不开（-11828）：删掉这个坏缓存，回源重新解析取流。
  private func discardCorruptCacheAndReload(assetURL: URL) {
    let cached = CachedAudioAlias.cachedFile(for: assetURL)
    try? FileManager.default.removeItem(at: cached)
    if assetURL != cached {
      try? FileManager.default.removeItem(at: assetURL)
    }
    DLog("player", "缓存文件解不开，已删除坏缓存 \(cached.lastPathComponent)，回源重取")
    guard let context = activeLoad else {
      fail("播放失败：缓存文件损坏")
      return
    }
    // 取流阶梯归零，按网络路径重新走一遍
    streamStage = 0
    proxyVariant = 0
    load(
      book: context.book,
      chapter: context.chapter,
      resume: pendingResume,
      isRetry: true
    )
  }

  // MARK: - 音频处理（音量提升 / 跳过空白）

  /// 把当前书的增益与跳过空白设置同步给处理器，再决定要不要挂 audioMix。
  /// 两个开关都关着时完全不碰播放链路。
  private func applyAudioProcessing(to item: AVPlayerItem, book: Book) {
    audioProcessor.setGain(decibels: book.loudnessGain)
    audioProcessor.setSkipSilence(book.skipSilence)
    guard audioProcessor.isActive else { return }
    audioProcessor.attach(to: item, asset: item.asset)
  }

  /// 音量提升，0~9 dB（对齐安卓 VolumeGain.MAX_GAIN）
  func setLoudnessGain(_ decibels: Double) {
    let clamped = min(max(decibels, 0), 9)
    guard let bookId, let index = library.index(of: bookId) else { return }
    guard abs(library.books[index].loudnessGain - clamped) > 0.01 else { return }

    let wasActive = audioProcessor.isActive
    library.books[index].loudnessGain = clamped
    // 拖动滑块会高频触发，用延迟落盘，别每次都全量写盘
    library.save(delay: 0.4)
    audioProcessor.setGain(decibels: clamped)
    syncAudioMix(wasActive: wasActive)
  }

  /// 跳过空白部分
  func setSkipSilence(_ enabled: Bool) {
    guard let bookId, let index = library.index(of: bookId) else { return }
    guard library.books[index].skipSilence != enabled else { return }

    let wasActive = audioProcessor.isActive
    library.books[index].skipSilence = enabled
    library.save(delay: 0.4)
    audioProcessor.setSkipSilence(enabled)
    syncAudioMix(wasActive: wasActive)
  }

  /// tap 里的增益是每次回调现读的，所以只需要处理「整体是否启用」的翻转：
  /// 从关到开才挂一次 audioMix，从开到关才摘掉。拖滑块不会反复重挂。
  private func syncAudioMix(wasActive: Bool) {
    guard let item = player.currentItem else { return }
    if audioProcessor.isActive {
      if !wasActive {
        audioProcessor.attach(to: item, asset: item.asset)
      }
    } else {
      item.audioMix = nil
    }
  }

  /// 处理器报「连续静音够久」时向前跳一小段。
  /// 不像 ExoPlayer 那样按 100ms 窗口无缝裁剪，但能把章节开头/段间的空白跳过。
  private func jumpOverSilence() {
    guard duration > 0, player.timeControlStatus == .playing else { return }
    let target = min(duration - 0.5, position + 2.5)
    guard target > position + 0.3 else { return }
    DLog(
      "audio",
      "跳过空白 \(String(format: "%.1f", position))s → \(String(format: "%.1f", target))s"
    )
    player.seek(
      to: CMTime(seconds: target, preferredTimescale: 600),
      toleranceBefore: .zero,
      toleranceAfter: .zero
    )
  }

  /// 一次远程解析需要的、只能在主线程安全读取的上下文
  private struct RemoteContext {
    var script: String
    var allowInsecure: Bool
    var remoteBookId: String
  }

  /// 这一集手动缓存过就返回本地文件地址（离线也能听）
  private func cachedChapterFile(book: Book, chapter: Chapter) -> URL? {
    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    guard
      OnlineChapterFileCache.isCached(
        source: source,
        bookId: book.id,
        chapterId: chapter.id
      )
    else { return nil }
    return OnlineChapterFileCache.file(source: source, bookId: book.id, chapterId: chapter.id)
  }

  private func resolveRemote(
    chapter: Chapter,
    context: RemoteContext
  ) async throws -> (url: URL, headers: [String: String]) {
    let resolved = try await ExtensionSourceService.shared.audio(
      script: context.script,
      allowInsecure: context.allowInsecure,
      bookId: context.remoteBookId,
      chapterId: chapter.id,
      extraJSON: chapter.extraJSON
    )
    guard let url = URL(string: resolved.url) else {
      throw SourceEngineError.source("源返回的地址无法解析")
    }
    return (url, resolved.headers)
  }

  /// 播放中驱动一次在线预加载（挂在 0.5s 的时间观察者上，不另起计时器）。
  /// 本地书与 WebDAV 的地址是现成的，不需要预解析。
  private func drivePreload() {
    guard let book = currentBook, book.sourceId != nil else {
      preloader.cancelChain()
      return
    }
    guard let sourceId = book.sourceId,
      let installed = extensions.enabledSources.first(where: { $0.id == sourceId }),
      let script = extensions.scriptText(for: installed)
    else {
      preloader.cancelChain()
      return
    }
    let remote = RemoteContext(
      script: script,
      allowInsecure: installed.allowInsecure,
      remoteBookId: book.remoteBookId ?? book.id
    )
    preloader.tick(
      book: book,
      chapterIndex: chapterIndex,
      position: position,
      duration: duration,
      playing: isPlaying,
      settings: settings.settings,
      resolve: { [weak self] chapter in
        guard let self else { return nil }
        return try? await self.resolveRemote(chapter: chapter, context: remote)
      }
    )
  }

  // MARK: - 进度保存

  func savePosition(force: Bool = false) {
    guard let bookId, let book = library.book(withId: bookId), !book.chapters.isEmpty else {
      return
    }
    let index = min(max(0, chapterIndex), book.chapters.count - 1)
    library.setPosition(bookId: bookId, chapterIndex: index, seconds: position)
    if force { library.save() }
  }

  func flush() {
    savePosition(force: true)
  }

  // MARK: - 观察者

  private func installObservers() {
    timeObserverToken = player.addPeriodicTimeObserver(
      forInterval: CMTime(seconds: 0.5, preferredTimescale: 600),
      queue: .main
    ) { [weak self] time in
      guard let self else { return }
      let seconds = time.seconds
      self.position = seconds.isFinite ? max(0, seconds) : 0
      let playing = self.player.timeControlStatus == .playing
      if playing != self.isPlaying { self.isPlaying = playing }
      if let itemDuration = self.player.currentItem?.duration {
        let total = itemDuration.seconds
        if total.isFinite, total > 0, abs(total - self.duration) > 0.2 {
          self.duration = total
          self.persistChapterDuration(total)
        }
      }
      self.saveTick += 1
      // 1000 集的 library.json 不小，别每 3 秒全量编码一次；暂停/切章会 force 落盘
      if self.saveTick % 20 == 0 { self.savePosition() }
      self.checkMarkBoundary()
      self.checkOutroSkip()
      if self.audioProcessor.consumeSilenceJump() { self.jumpOverSilence() }
      self.drivePreload()
    }

    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime,
      object: nil,
      queue: .main
    ) { [weak self] notification in
      guard let self,
        let item = notification.object as? AVPlayerItem,
        item === self.player.currentItem
      else { return }
      self.handleChapterFinished()
    }

    interruptionObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.interruptionNotification,
      object: nil,
      queue: .main
    ) { [weak self] notification in
      guard let self,
        let info = notification.userInfo,
        let rawType = info[AVAudioSessionInterruptionTypeKey] as? UInt,
        let type = AVAudioSession.InterruptionType(rawValue: rawType)
      else { return }
      if type == .began {
        self.shouldResumeAfterInterruption = self.isPlaying
        self.player.pause()
        self.isPlaying = false
      } else if let rawOptions = info[AVAudioSessionInterruptionOptionKey] as? UInt {
        let options = AVAudioSession.InterruptionOptions(rawValue: rawOptions)
        if options.contains(.shouldResume), self.shouldResumeAfterInterruption {
          self.play()
        }
      }
    }

    routeObserver = NotificationCenter.default.addObserver(
      forName: AVAudioSession.routeChangeNotification,
      object: nil,
      queue: .main
    ) { [weak self] notification in
      guard let self,
        let info = notification.userInfo,
        let rawReason = info[AVAudioSessionRouteChangeReasonKey] as? UInt,
        let reason = AVAudioSession.RouteChangeReason(rawValue: rawReason)
      else { return }
      if reason == .oldDeviceUnavailable {
        self.player.pause()
        self.isPlaying = false
        self.savePosition(force: true)
      }
    }

    UIApplication.shared.beginReceivingRemoteControlEvents()
  }

  private func observePlaybackFailure(item: AVPlayerItem) {
    if let failedObserver { NotificationCenter.default.removeObserver(failedObserver) }
    failedObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemFailedToPlayToEndTime,
      object: item,
      queue: .main
    ) { [weak self] notification in
      guard let self else { return }
      let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
      DLog("player", "✗ 播放中断 \(error?.localizedDescription ?? "未知错误")")
      self.fail("播放失败：\(error?.localizedDescription ?? "未知错误")")
    }

    // item.status 变 .failed 时不会发任何通知，只能 KVO。
    // 之前只监听 FailedToPlayToEndTime，导致「拉不到流」这类失败全程静默 ——
    // 界面只是转圈不动，日志里一个字都没有。
    statusObservation?.invalidate()
    statusObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
      switch item.status {
      case .readyToPlay:
        let seconds = item.duration.seconds
        DLog(
          "player",
          "item 就绪 时长=\(seconds.isFinite ? String(format: "%.1f", seconds) : "未知")s"
            + " 已喂入=\(Format.bytes(self?.proxyLoader?.deliveredBytes ?? 0))"
            + " 阶段=\(self?.streamStage ?? 0)"
        )
        self?.readyWatchdog?.invalidate()
      case .failed:
        let error = item.error as NSError?
        DLog(
          "player",
          "✗ item.status=failed domain=\(error?.domain ?? "-") code=\(error?.code ?? 0) "
            + "\(error?.localizedDescription ?? "")"
        )
        DispatchQueue.main.async {
          self?.readyWatchdog?.invalidate()
          // 认不出容器时先换一种取流方式重试，别直接弹错误
          if error?.code == -11828 {
            // 本地缓存文件解不开：不是取流方式的问题，是缓存坏了。
            // 删掉坏缓存回源重取，别在换档阶梯上空转（同一个文件炸三次毫无意义）
            if let asset = item.asset as? AVURLAsset, asset.url.isFileURL {
              self?.discardCorruptCacheAndReload(assetURL: asset.url)
              return
            }
            if self?.escalateStreamStage(reason: "容器认不出来(-11828)") == true {
              return
            }
          }
          self?.fail(Self.describe(error))
        }
      case .unknown:
        DLog("player", "item.status=unknown（开始加载）")
      @unknown default:
        break
      }
    }

    // errorLog 里带 CDN 的真实 HTTP 状态码，是判断「防盗链拦了」还是「容器不认」的关键证据
    if let errorLogObserver { NotificationCenter.default.removeObserver(errorLogObserver) }
    errorLogObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemNewErrorLogEntry,
      object: item,
      queue: .main
    ) { _ in
      guard let log = item.errorLog(), let event = log.events.last else { return }
      DLog(
        "player",
        "✗ 访问日志 status=\(event.errorStatusCode) \(event.errorComment ?? "") "
          + "host=\(event.serverAddress ?? "-") uri=\(event.uri ?? "-")"
      )
    }
  }

  // MARK: - 取流失败原文（对齐安卓 SourceErrorDialog）

  /// 取流失败时给用户看的那句话。
  ///
  /// 不同源的报错格式完全不一样，所以一律照搬源脚本抛出的字符串 ——
  /// 拼接、归类、改写成「这一章没有音频」都只会把真正的原因盖掉。
  /// 只有网络库自己写的单词（不是源说的话）才换成本地文案。
  static func sourceErrorMessage(_ error: Error) -> String {
    let raw = (error as NSError).localizedDescription
    if let framework = frameworkErrorText(raw) { return framework }
    let stripped = rawSourceErrorText(raw)
    return stripped.isEmpty ? "在线书源没有返回这一章的音频地址" : stripped
  }

  /// `timeout` / `canceled` 是 URLSession 与取消逻辑里写死的单词，不是接口返回的内容
  private static func frameworkErrorText(_ detail: String) -> String? {
    switch detail.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
    case "timeout", "timed out", "the request timed out.":
      return "连接书源超时，请检查网络后重试"
    case "canceled", "cancelled":
      return "请求已取消"
    default:
      return nil
    }
  }

  /// 只剥掉包装层，正文一个字不改（换行也保留）：
  /// 异常类型前缀（`Error: ...` / `QuickJsException: ...`）和 JS 调用栈行（`    at audio (l.js:154:37)`）。
  private static func rawSourceErrorText(_ detail: String) -> String {
    var text = detail.trimmingCharacters(in: .whitespacesAndNewlines)
    while true {
      let stripped = text
        .replacingOccurrences(
          of: #"^([\w.$]*(Exception|Error|Failure|Throwable)|Error):\s*"#,
          with: "",
          options: .regularExpression
        )
        .trimmingCharacters(in: .whitespacesAndNewlines)
      if stripped == text || stripped.isEmpty { break }
      text = stripped
    }
    return text
      .components(separatedBy: .newlines)
      .filter { $0.range(of: #"^\s*at\s+\S"#, options: .regularExpression) == nil }
      .joined(separator: "\n")
      .trimmingCharacters(in: .whitespacesAndNewlines)
  }

  /// 把这一章的真实时长写回书架。
  ///
  /// 在线书的章节时长在源里通常没有（安卓也是靠预加载时顺手探一次补上的），
  /// 结果是章节抽屉里每一集都显示 0:00、总时长也是空的。播到就记下来，
  /// 下次打开章节列表就是准确的。
  private func persistChapterDuration(_ seconds: Double) {
    guard seconds > 1, let bookId, let index = library.index(of: bookId) else { return }
    let chapters = library.books[index].chapters
    guard chapters.indices.contains(chapterIndex) else { return }
    // 差不到 1 秒就别写盘了，免得每次起播都整库编码一次
    guard abs(chapters[chapterIndex].duration - seconds) > 1 else { return }
    library.books[index].chapters[chapterIndex].duration = seconds
    library.save(delay: 1)
    DLog("player", "记录章节时长 \(Int(seconds))s 第\(chapterIndex + 1)集")
  }

  /// 把 AVFoundation 的错误翻成听得懂的话
  private static func describe(_ error: NSError?) -> String {
    guard let error else { return "播放失败：未知错误" }
    // -11828 文件类型不支持 / -1008 资源不可用 / -1100 文件不存在 / -1005 连接中断
    switch error.code {
    case -11828:
      return "播放失败：这段音频的容器格式系统认不出来（常见于分片音轨），请换个源或换一集"
    case -1008:
      return "播放失败：音频地址被服务器拒绝，直链可能已过期，重新点一次这一集试试"
    case -1100:
      return "播放失败：音频文件不存在"
    case -1005:
      return "播放失败：网络连接中断"
    default:
      return "播放失败：\(error.localizedDescription)"
    }
  }

  /// 播到分段末尾就自动进下一分段（同章内只 seek，跨章才换 item）。
  ///
  /// 安卓一个分段就是一个 media item，播完自动换下一个；iOS 一个 item 是整章，
  /// 所以这一段得自己按分段的 endMs 推过去，否则带 markData 的书会一章连着播到底。
  /// 本章最后一个分段不在这里处理 —— 交给 `AVPlayerItemDidPlayToEndTime`。
  private func checkMarkBoundary() {
    guard player.timeControlStatus == .playing, duration > 0 else { return }
    let marks = currentMarks
    guard marks.indices.contains(markIndex) else { return }
    let mark = marks[markIndex]
    guard mark.end > mark.start, position >= mark.end else { return }
    let next = markIndex + 1
    guard next < marks.count else { return }
    // 只把游标推过去，不往回 seek —— 观察者 0.5 秒才看一次，
    // 播到边界时人已经多走了不到半秒，硬跳回分段起点会听得出来
    markIndex = next
    outroHandled = false
    if position < marks[next].start {
      seek(to: marks[next].start)
    }
  }

  private func handleChapterFinished() {
    if sleepAtChapterEnd {
      finishSleep()
      return
    }
    guard let book = currentBook else { return }
    if chapterIndex + 1 < book.chapters.count {
      open(book, chapterIndex: chapterIndex + 1, autoplay: true)
    } else {
      player.pause()
      isPlaying = false
      library.markFinished(bookId: book.id)
      updateNowPlayingInfo()
    }
  }

  private func checkOutroSkip() {
    guard let book = currentBook, book.skipOutro > 0, !outroHandled, duration > 0 else { return }
    guard chapterIndex + 1 < book.chapters.count else { return }
    // 片尾只在整章的最后一段生效，别每小节尾巴都跳一次
    guard markIndex == max(0, currentMarks.count - 1) else { return }
    guard duration > Double(book.skipOutro) + 5 else { return }
    let threshold = duration - Double(book.skipOutro)
    if position >= threshold, player.timeControlStatus == .playing {
      outroHandled = true
      // 片尾跳过是「自动接下一章」，必须接着播，不受暂停状态影响
      nextChapter(autoplay: true)
    }
  }

  // MARK: - 睡眠定时器实现

  private func startSleepTicker() {
    sleepTicker?.invalidate()
    let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] _ in
      guard let self, let end = self.sleepTimerEnd else { return }
      let remaining = end.timeIntervalSinceNow
      self.sleepRemaining = max(0, remaining)
      if remaining <= 0 { self.fadeOutAndPause() }
    }
    sleepTicker = timer
    RunLoop.main.add(timer, forMode: .common)
  }

  private func fadeOutAndPause() {
    sleepTicker?.invalidate()
    sleepTicker = nil
    sleepTimerEnd = nil
    guard settings.settings.sleepFadeOut else {
      finishSleep()
      return
    }
    var step = 0
    fadeTimer?.invalidate()
    let timer = Timer(timeInterval: 0.5, repeats: true) { [weak self] timer in
      guard let self else {
        timer.invalidate()
        return
      }
      step += 1
      let volume = max(0, 1 - Float(step) / 16)
      self.player.volume = volume
      if step >= 16 {
        timer.invalidate()
        self.finishSleep()
      }
    }
    fadeTimer = timer
    RunLoop.main.add(timer, forMode: .common)
  }

  private func finishSleep() {
    player.pause()
    isPlaying = false
    player.volume = 1
    sleepTimerEnd = nil
    sleepAtChapterEnd = false
    sleepRemaining = 0
    savePosition(force: true)
    updateNowPlayingInfo()
  }

  // MARK: - 系统集成

  private func configureAudioSession() {
    let session = AVAudioSession.sharedInstance()
    try? session.setCategory(.playback, mode: .spokenAudio, options: [])
  }

  private func activateAudioSession() {
    try? AVAudioSession.sharedInstance().setActive(true)
  }

  private func configureRemoteCommands() {
    let center = MPRemoteCommandCenter.shared()
    center.playCommand.isEnabled = true
    center.pauseCommand.isEnabled = true
    center.togglePlayPauseCommand.isEnabled = true
    center.skipForwardCommand.isEnabled = true
    center.skipBackwardCommand.isEnabled = true
    center.nextTrackCommand.isEnabled = true
    center.previousTrackCommand.isEnabled = true
    center.changePlaybackPositionCommand.isEnabled = true

    center.playCommand.addTarget { [weak self] _ in
      self?.play()
      return .success
    }
    center.pauseCommand.addTarget { [weak self] _ in
      self?.pause()
      return .success
    }
    center.togglePlayPauseCommand.addTarget { [weak self] _ in
      self?.togglePlayPause()
      return .success
    }
    // 安卓「蓝牙上一集/下一集切换章节」：关闭时这两个键退化为快退/快进
    center.nextTrackCommand.addTarget { [weak self] _ in
      guard let self else { return .commandFailed }
      if self.settings.settings.btSkipChapter {
        self.nextChapter()
      } else {
        self.skip(Double(self.settings.settings.seekSeconds))
      }
      return .success
    }
    center.previousTrackCommand.addTarget { [weak self] _ in
      guard let self else { return .commandFailed }
      if self.settings.settings.btSkipChapter {
        self.previousChapter()
      } else {
        self.skip(-Double(self.settings.settings.seekSeconds))
      }
      return .success
    }
    center.skipForwardCommand.addTarget { [weak self] _ in
      self?.skip(Double(self?.settings.settings.seekSeconds ?? 20))
      return .success
    }
    center.skipBackwardCommand.addTarget { [weak self] _ in
      self?.skip(-Double(self?.settings.settings.seekSeconds ?? 20))
      return .success
    }
    center.changePlaybackPositionCommand.addTarget { [weak self] event in
      guard let self,
        let positionEvent = event as? MPChangePlaybackPositionCommandEvent
      else { return .commandFailed }
      // 锁屏进度条和播放页一样是「分段内相对位置」，加回分段起点才是章内绝对位置
      self.seek(to: self.unitStart + positionEvent.positionTime)
      return .success
    }
  }

  private func updateNowPlayingInfo() {
    guard let book = currentBook, let chapter = currentChapter else {
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      return
    }
    var info: [String: Any] = [
      MPMediaItemPropertyTitle: currentUnitTitle.isEmpty ? chapter.title : currentUnitTitle,
      MPMediaItemPropertyArtist: book.author.isEmpty ? "有声书" : book.author,
      MPMediaItemPropertyAlbumTitle: book.title,
      // 锁屏进度同样按分段算，和播放页里的进度条保持一致
      MPNowPlayingInfoPropertyElapsedPlaybackTime: unitPosition,
      MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
      MPNowPlayingInfoPropertyDefaultPlaybackRate: speed,
    ]
    if unitDuration > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = unitDuration
    }
    if let image = coverImage(for: book) {
      info[MPMediaItemPropertyArtwork] = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }
    MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    MPNowPlayingInfoCenter.default().playbackState = isPlaying ? .playing : .paused
  }

  func coverImage(for book: Book) -> UIImage? {
    guard let cover = book.coverFile else { return nil }
    let url = AppPaths.url(for: cover)
    guard let data = try? Data(contentsOf: url) else { return nil }
    return UIImage(data: data)
  }
}
