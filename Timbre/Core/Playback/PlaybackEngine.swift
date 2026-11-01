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

  private var timeObserverToken: Any?
  private var endObserver: NSObjectProtocol?
  private var failedObserver: NSObjectProtocol?
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

  init(library: LibraryStore, settings: SettingsStore, extensions: ExtensionStore) {
    self.library = library
    self.settings = settings
    self.extensions = extensions
    super.init()
    player.automaticallyWaitsToMinimizeStalling = true
    configureAudioSession()
    configureRemoteCommands()
    installObservers()
  }

  deinit {
    if let timeObserverToken { player.removeTimeObserver(timeObserverToken) }
    for observer in [endObserver, failedObserver, interruptionObserver, routeObserver] {
      if let observer { NotificationCenter.default.removeObserver(observer) }
    }
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
    activateAudioSession()
    player.playImmediately(atRate: Float(speed))
    isPlaying = true
    updateNowPlayingInfo()
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
          var options: [String: Any] = [:]
          if !resolved.headers.isEmpty {
            options["AVURLAssetHTTPHeaderFieldsKey"] = resolved.headers
          }
          let asset = AVURLAsset(
            url: resolved.url,
            options: options.isEmpty ? nil : options
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
      self.fail("播放失败：\(error?.localizedDescription ?? "未知错误")")
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
    center.nextTrackCommand.addTarget { [weak self] _ in
      self?.nextChapter()
      return .success
    }
    center.previousTrackCommand.addTarget { [weak self] _ in
      self?.previousChapter()
      return .success
    }
    center.skipForwardCommand.addTarget { [weak self] _ in
      self?.skip(Double(self?.settings.settings.seekSeconds ?? 15))
      return .success
    }
    center.skipBackwardCommand.addTarget { [weak self] _ in
      self?.skip(-Double(self?.settings.settings.seekSeconds ?? 15))
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
