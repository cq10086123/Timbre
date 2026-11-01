import Foundation

/// 在线书源播放地址预加载（对齐安卓 `OnlinePlaybackPreloader` + `PreloadPolicy`）。
///
/// 规则和安卓完全一致，因为这套规则就是为了「别把限流的源打死」：
/// - 进播放器不预加载，必须真播起来；
/// - 本章播满「预加载触发时间」才开始，且本章总时长不短于该时间
///   （太短的章节，预加载链条跑不完就要切章，纯属浪费源配额）；
/// - 最多向前预加载「预加载章节数」章，每章之间隔「章节预加载间隔」秒；
/// - 切章 / 暂停 / 换书立刻拆掉正在跑的链条，新章从零开始。
///
/// 预加载只做「解析直链」这一件事 —— 它是切章时最慢、也最容易触发源限流的一步。
final class OnlinePreloader {

  typealias Stream = (url: URL, headers: [String: String])

  private struct Entry {
    var url: URL
    var headers: [String: String]
    var storedAt: Date
  }

  /// 直链有效期，对齐安卓 `STREAM_URL_TTL_MS`（5 分钟）
  private static let ttl: TimeInterval = 300

  private let lock = NSLock()
  private var cache: [String: Entry] = [:]
  private var chainTask: Task<Void, Never>?
  private var chainRunning = false

  // MARK: - 取用

  /// 取走某章的预加载结果（一次性：用过就删，直链失效时可以重新解析）
  func take(chapterId: String) -> Stream? {
    lock.lock()
    let entry = cache.removeValue(forKey: chapterId)
    lock.unlock()
    guard let entry else { return nil }
    guard Date().timeIntervalSince(entry.storedAt) < Self.ttl else { return nil }
    return (entry.url, entry.headers)
  }

  /// 预加载了几章（调试日志用）
  var cachedCount: Int {
    lock.lock()
    defer { lock.unlock() }
    return cache.count
  }

  func clear() {
    cancelChain()
    lock.lock()
    cache.removeAll()
    lock.unlock()
  }

  func cancelChain() {
    lock.lock()
    let task = chainTask
    chainTask = nil
    chainRunning = false
    lock.unlock()
    task?.cancel()
  }

  // MARK: - 驱动

  /// 播放中每秒调用一次（挂在播放器的时间观察者上，不另起计时器）。
  func tick(
    book: Book,
    chapterIndex: Int,
    position: Double,
    duration: Double,
    playing: Bool,
    settings: AppSettings,
    resolve: @escaping (Chapter) async -> Stream?
  ) {
    guard settings.onlinePreloadEnabled, playing else {
      cancelChain()
      return
    }

    let trigger = Double(max(0, settings.onlinePreloadTriggerSeconds))
    // 时长未知（0）视为「太短，不预加载」，与安卓一致
    guard duration >= trigger, position >= trigger else { return }

    let count = max(1, settings.onlinePreloadChapterCount)
    guard cachedAhead(book: book, chapterIndex: chapterIndex, limit: count) < count else {
      return
    }

    lock.lock()
    let alreadyRunning = chainRunning
    if !alreadyRunning { chainRunning = true }
    lock.unlock()
    guard !alreadyRunning else { return }

    let interval = UInt64(max(1, settings.onlinePreloadIntervalSeconds)) * 1_000_000_000

    let task = Task { [weak self] in
      var index = chapterIndex + 1
      var loaded = 0
      while !Task.isCancelled, loaded < count {
        guard let self else { return }
        guard book.chapters.indices.contains(index) else { break }
        let chapter = book.chapters[index]
        if let stream = await resolve(chapter) {
          self.store(chapterId: chapter.id, stream: stream)
          DLog("preload", "预加载完成 第\(index + 1)集 缓存=\(self.cachedCount)")
        } else {
          DLog("preload", "预加载失败 第\(index + 1)集，链条终止")
          break
        }
        loaded += 1
        index += 1
        if loaded < count {
          try? await Task.sleep(nanoseconds: interval)
        }
      }
      self?.finishChain()
    }

    lock.lock()
    chainTask = task
    lock.unlock()
  }

  // MARK: - 内部

  private func finishChain() {
    lock.lock()
    chainTask = nil
    chainRunning = false
    lock.unlock()
  }

  private func store(chapterId: String, stream: Stream) {
    lock.lock()
    cache[chapterId] = Entry(url: stream.url, headers: stream.headers, storedAt: Date())
    lock.unlock()
  }

  /// 当前章之后连续缓存了几章（中间断掉就停，安卓同样只看连续的）
  private func cachedAhead(book: Book, chapterIndex: Int, limit: Int) -> Int {
    lock.lock()
    defer { lock.unlock() }
    var ahead = 0
    var index = chapterIndex + 1
    while ahead < limit, book.chapters.indices.contains(index) {
      guard let entry = cache[book.chapters[index].id],
        Date().timeIntervalSince(entry.storedAt) < Self.ttl
      else { break }
      ahead += 1
      index += 1
    }
    return ahead
  }
}
