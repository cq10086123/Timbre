import AVFoundation
import Combine
import MediaPlayer
import UIKit

/// 播放内核：AVPlayer（苹果原生播放器）+ 后台播放 + 锁屏 / 蓝牙控制
/// 支持本地文件与接口源直链（带 Referer / Cookie 等请求头）
final class PlaybackEngine: NSObject, ObservableObject {
  @Published private(set) var bookId: String?
  @Published private(set) var chapterIndex: Int = 0
  @Published private(set) var isPlaying = false
  @Published private(set) var position: Double = 0
  @Published private(set) var duration: Double = 0
  @Published private(set) var isLoading = false
  @Published private(set) var speed: Double = 1.0
  @Published private(set) var sleepTimerEnd: Date?
  @Published private(set) var sleepAtChapterEnd = false
  @Published private(set) var sleepRemaining: Double = 0
  @Published var errorMessage: String?

  private let player = AVPlayer()
  private let library: LibraryStore
  private let settings: SettingsStore
  private let extensions: ExtensionStore
  private let webdav: WebDavStore

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
  /// -11828 时换一种声明重试，每章只重试一次
  private var didRetryVariant = false
  /// 当前这一章解析出来的直链，重试时不用再跑一遍 JS
  private var activeLoad: (book: Book, chapter: Chapter)?

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
    configureAudioSession()
    configureRemoteCommands()
    installObservers()
  }

  deinit {
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

  var progressFraction: Double {
    duration > 0 ? min(1, max(0, position / duration)) : 0
  }

  var remaining: Double { max(0, duration - position) }

  var sleepActive: Bool { sleepAtChapterEnd || sleepTimerEnd != nil }

  func title(forChapter index: Int) -> String {
    guard let book = currentBook, book.chapters.indices.contains(index) else { return "" }
    return book.chapters[index].title
  }

  // MARK: - 打开 / 播放

  func open(
    _ book: Book,
    chapterIndex: Int = 0,
    autoplay: Bool = true,
    resumeAt: Double? = nil
  ) {
    guard !book.chapters.isEmpty else {
      errorMessage = "这本书还没有章节"
      return
    }
    let index = min(max(0, chapterIndex), book.chapters.count - 1)
    self.bookId = book.id
    self.chapterIndex = index
    self.speed = book.speed > 0 ? book.speed : 1.0
    self.outroHandled = false
    self.resumeAfterLoad = autoplay
    // 换书 / 换章：容器声明与重试计数都归零
    self.proxyVariant = 0
    self.didRetryVariant = false
    let resume = resumeAt
      ?? (book.position.chapterIndex == index ? max(0, book.position.seconds) : 0)
    load(book: book, chapter: book.chapters[index], resume: resume)
  }

  func togglePlayPause() {
    if player.timeControlStatus == .playing {
      pause()
    } else {
      play()
    }
  }

  func play() {
    guard player.currentItem != nil else {
      if let book = currentBook { open(book, chapterIndex: chapterIndex, autoplay: true) }
      return
    }
    let wasPlaying = isPlaying
    activateAudioSession()
    player.playImmediately(atRate: Float(speed))
    isPlaying = true
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
    isPlaying = false
    position = 0
    duration = 0
    bookId = nil
    chapterIndex = 0
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
    updateNowPlayingInfo()
  }

  func skip(_ delta: Double) {
    seek(to: position + delta)
  }

  func seekToFraction(_ fraction: Double) {
    guard duration > 0 else { return }
    seek(to: duration * min(1, max(0, fraction)))
  }

  func nextChapter(autoplay: Bool = true) {
    guard let book = currentBook else { return }
    if chapterIndex + 1 < book.chapters.count {
      open(book, chapterIndex: chapterIndex + 1, autoplay: autoplay)
    } else {
      player.pause()
      isPlaying = false
      library.markFinished(bookId: book.id)
      updateNowPlayingInfo()
    }
  }

  func previousChapter() {
    guard let book = currentBook else { return }
    if position > 3 || chapterIndex == 0 {
      seek(to: 0)
      return
    }
    open(book, chapterIndex: chapterIndex - 1, autoplay: true)
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
    let chapterTitle = currentChapter?.title ?? ""
    let label = title.isEmpty
      ? "\(chapterTitle) \(Format.time(position))"
      : title
    let merged = chapterTitle.isEmpty ? label : "\(chapterTitle) · \(label)"
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

  private func load(book: Book, chapter: Chapter, resume: Double) {
    isLoading = true
    errorMessage = nil
    pendingResume = resume
    loadToken += 1
    let token = loadToken
    activeLoad = (book, chapter)

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

    // WebDAV：直连 NAS 上的文件，靠 Authorization 头过认证
    if book.kind == .webdav {
      guard let serverId = book.webdavServerId, let server = webdav.server(withId: serverId) else {
        fail("WebDAV 服务器已被删除，请重新添加后再播放")
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

    Task { [weak self] in
      guard let self else { return }
      do {
        let resolved = try await self.resolveRemote(chapter: chapter, context: remote)
        await MainActor.run {
          guard self.loadToken == token else { return }
          let asset = self.makeAsset(
            url: resolved.url,
            headers: resolved.headers,
            tag: "接口源"
          )
          self.install(item: AVPlayerItem(asset: asset), book: book, chapter: chapter)
        }
      } catch {
        await MainActor.run {
          guard self.loadToken == token else { return }
          self.fail(error.localizedDescription)
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
  private func makeAsset(url: URL, headers: [String: String], tag: String) -> AVURLAsset {
    let needsProxy = !headers.isEmpty || url.pathExtension.lowercased() == "m4s"
    guard needsProxy else {
      DLog("player", "\(tag) 直连 \(url.host ?? "-")\(url.path) (无头，原生路径)")
      return AVURLAsset(url: url)
    }
    DLog(
      "player",
      "\(tag) 代理 \(url.host ?? "-")\(url.path) 头=\(headers.keys.sorted()) 声明=\(proxyVariant)"
    )
    proxyLoader = HeaderProxyLoader(
      origin: url,
      headers: headers,
      contentTypeVariant: proxyVariant
    )
    return proxyLoader?.makeAsset() ?? AVURLAsset(url: url)
  }

  /// -11828 = AVFoundation 认不出容器。最常见的成因是我们回填的 UTI 和实际内容对不上，
  /// 换另一种声明重新加载一次就行，不用再跑一遍接口源脚本。
  private func retryWithOtherContainer() -> Bool {
    guard !didRetryVariant, proxyVariant == 0, let context = activeLoad else { return false }
    didRetryVariant = true
    proxyVariant = 1
    DLog("player", "容器声明失败，换成 public.mpeg-4 重试一次")
    load(book: context.book, chapter: context.chapter, resume: pendingResume)
    return true
  }

  private func install(item: AVPlayerItem, book: Book, chapter: Chapter) {
    player.replaceCurrentItem(with: item)
    observePlaybackFailure(item: item)
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
    }
    saveTick = 0
    updateNowPlayingInfo()
  }

  private func fail(_ message: String) {
    isLoading = false
    isPlaying = false
    errorMessage = message
  }

  /// 一次远程解析需要的、只能在主线程安全读取的上下文
  private struct RemoteContext {
    var script: String
    var allowInsecure: Bool
    var remoteBookId: String
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
        }
      }
      self.saveTick += 1
      // 1000 集的 library.json 不小，别每 3 秒全量编码一次；暂停/切章会 force 落盘
      if self.saveTick % 20 == 0 { self.savePosition() }
      self.checkOutroSkip()
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
        )
      case .failed:
        let error = item.error as NSError?
        DLog(
          "player",
          "✗ item.status=failed domain=\(error?.domain ?? "-") code=\(error?.code ?? 0) "
            + "\(error?.localizedDescription ?? "")"
        )
        DispatchQueue.main.async {
          // 认不出容器时先换一种 UTI 重试，别直接弹错误
          if error?.code == -11828, self?.retryWithOtherContainer() == true { return }
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
    guard duration > Double(book.skipOutro) + 5 else { return }
    let threshold = duration - Double(book.skipOutro)
    if position >= threshold, player.timeControlStatus == .playing {
      outroHandled = true
      nextChapter()
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
      self.seek(to: positionEvent.positionTime)
      return .success
    }
  }

  private func updateNowPlayingInfo() {
    guard let book = currentBook, let chapter = currentChapter else {
      MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
      return
    }
    var info: [String: Any] = [
      MPMediaItemPropertyTitle: chapter.title,
      MPMediaItemPropertyArtist: book.author.isEmpty ? "有声书" : book.author,
      MPMediaItemPropertyAlbumTitle: book.title,
      MPNowPlayingInfoPropertyElapsedPlaybackTime: position,
      MPNowPlayingInfoPropertyPlaybackRate: isPlaying ? speed : 0,
      MPNowPlayingInfoPropertyDefaultPlaybackRate: speed,
    ]
    if duration > 0 {
      info[MPMediaItemPropertyPlaybackDuration] = duration
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
