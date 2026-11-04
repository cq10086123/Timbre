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
  /// 旧链条的收尾不能把新链条的标记清掉，用代次区分
  private var chainGeneration = 0
  /// 上一次链条失败后的冷却截止时间。
  ///
  /// 失败时 `chainRunning` 会被清掉，于是**下一个 0.5 秒的 tick 立刻又起一条新链**。
  /// 实测日志：一章 385 秒的书，播放 20 秒里打了 30 次源接口，全部 429 ——
  /// 源那边「同一 IP 每 30 秒 1 次」的配额被我们自己打爆，
  /// 用户手动切章反而被限流（就是「播完跳过一集」那个bug的燃料）。
  /// 冷却时长直接用设置里的「章节预加载间隔」，不去猜上游返回的重试秒数。
  private var cooldownUntil: Date?
  /// 上一次记过的判定，避免每 0.5s 刷一条同样的日志把日志冲爆
  private var lastDecision: String?

  /// 只在判定变化时记一条，方便从导出的日志里看出预加载为什么没开始
  private func note(_ reason: String) {
    guard lastDecision != reason else { return }
    lastDecision = reason
    DLog("preload", reason)
  }

  // MARK: - 取用

  /// 缓存键带 bookId：不少 jdr 源的章节 id 是「1」「2」这种按集数的短 id，
  /// 只按 chapterId 键控，换书后 TTL 内会取到上一本书同一集的直链（串台）
  private func cacheKey(bookId: String, chapterId: String) -> String {
    "\(bookId)/\(chapterId)"
  }

  /// 取走某章的预加载结果（一次性：用过就删，直链失效时可以重新解析）
  func take(bookId: String, chapterId: String) -> Stream? {
    lock.lock()
    let entry = cache.removeValue(forKey: cacheKey(bookId: bookId, chapterId: chapterId))
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
    chainGeneration += 1
    // 冷却期不在这里清：源是按 IP 限流的，切章 / 暂停不该让我们有理由马上去再打一次
    lock.unlock()
    task?.cancel()
  }

  // MARK: - 驱动

  /// 播放中每秒调用一次（挂在播放器的时间观察者上，不另起计时器）。
  ///
  /// `persistDuration`：预加载时探测到的章节时长回写给调用方（下次不用再问服务器）。
  func tick(
    book: Book,
    chapterIndex: Int,
    position: Double,
    duration: Double,
    playing: Bool,
    settings: AppSettings,
    persistDuration: ((String, Double) -> Void)? = nil,
    resolve: @escaping (Chapter) async -> Stream?
  ) {
    guard settings.onlinePreloadEnabled, playing else {
      cancelChain()
      note(settings.onlinePreloadEnabled ? "预加载待命：当前未在播放" : "预加载待命：开关已关闭")
      return
    }

    let trigger = Double(max(0, settings.onlinePreloadTriggerSeconds))
    // 时长未知（0）视为「太短，不预加载」，与安卓一致
    guard duration >= trigger else {
      note("预加载不适用：本章时长 \(Int(duration))s 短于触发时间 \(Int(trigger))s")
      return
    }
    guard position >= trigger else {
      note("预加载等待：已播 \(Int(position))/\(Int(trigger))s")
      return
    }
    // 冷却期：上一次链条失败后先停手，别每个 tick 重来（源是按 IP 限流的）
    if let until = cooldownDeadline(), Date() < until {
      note("预加载冷却中：上一次解析失败，等间隔时间到了再试")
      return
    }

    let count = max(1, settings.onlinePreloadChapterCount)
    // 已经是最后一集：没有下一章可预加载，别每个 tick 都白起一条空链条
    guard book.chapters.indices.contains(chapterIndex + 1) else {
      note("预加载不适用：已经是最后一集")
      return
    }
    let ahead = cachedAhead(book: book, chapterIndex: chapterIndex, limit: count)
    guard ahead < count else {
      note("预加载就绪：前方已缓存 \(ahead)/\(count) 章")
      return
    }

    lock.lock()
    let alreadyRunning = chainRunning
    if !alreadyRunning {
      chainRunning = true
      chainGeneration += 1
    }
    let generation = chainGeneration
    lock.unlock()
    guard !alreadyRunning else { return }

    let intervalSeconds = max(1, settings.onlinePreloadIntervalSeconds)
    let interval = UInt64(intervalSeconds) * 1_000_000_000
    note("开始预加载：从第 \(chapterIndex + 2) 集起共 \(count) 章，间隔 \(intervalSeconds)s")

    let task = Task { [weak self] in
      guard let self else { return }
      // 链条是不是以「源报错」收尾的 —— 是的话要进冷却，否则下一个 tick 立刻又来一遍
      var failed = false
      // 无论正常走完、解析失败还是被取消（切章/暂停），都要把链条标记清掉，
      // 否则 chainRunning 会一直挂在 true，这一章就再也不会重启预加载
      defer { self.finishChain(generation, cooldownSeconds: failed ? intervalSeconds : 0) }
      var index = chapterIndex + 1
      var loaded = 0
      while !Task.isCancelled, loaded < count {
        guard book.chapters.indices.contains(index) else { break }
        let chapter = book.chapters[index]
        // 这一集已经整段存在手机上了：不用再问源要地址，省一次配额（源是按 IP 限流的）
        if self.isCachedOnDisk(book: book, chapter: chapter) {
          DLog("preload", "第\(index + 1)集已在本地，跳过解析")
        } else if let stream = await resolve(chapter) {
          self.store(bookId: book.id, chapterId: chapter.id, stream: stream)
          DLog("preload", "预加载完成 第\(index + 1)集 缓存=\(self.cachedCount)")
          await self.cacheToDiskIfNeeded(
            book: book,
            chapter: chapter,
            stream: stream,
            settings: settings,
            persistDuration: persistDuration
          )
        } else {
          failed = true
          DLog("preload", "预加载失败 第\(index + 1)集，链条终止，冷却 \(intervalSeconds)s")
          break
        }
        loaded += 1
        index += 1
        if loaded < count {
          try? await Task.sleep(nanoseconds: interval)
        }
      }
    }

    lock.lock()
    chainTask = task
    lock.unlock()
  }

  // MARK: - 内部

  private func cooldownDeadline() -> Date? {
    lock.lock()
    defer { lock.unlock() }
    return cooldownUntil
  }

  private func finishChain(_ generation: Int, cooldownSeconds: Int = 0) {
    lock.lock()
    if chainGeneration == generation {
      chainTask = nil
      chainRunning = false
      if cooldownSeconds > 0 {
        cooldownUntil = Date().addingTimeInterval(Double(cooldownSeconds))
      }
    }
    lock.unlock()
  }

  private func store(bookId: String, chapterId: String, stream: Stream) {
    lock.lock()
    cache[cacheKey(bookId: bookId, chapterId: chapterId)] =
      Entry(url: stream.url, headers: stream.headers, storedAt: Date())
    lock.unlock()
  }

  // MARK: - 存到本地

  /// 预加载的第二步：把这集整段音频下到手机上。
  ///
  /// 写的是「手动缓存」那套目录（`OnlineChapterFileCache`），所以播放命中本地文件、
  /// 「已缓存 X 集」统计、清除缓存都不用另写一套。额外只在 `OnlinePreloadIndex`
  /// 记一笔「这集是自动下的」，容量满了优先淘汰它们。
  ///
  /// 这里失败只是「没缓存成」：直链已经预加载好了，切章照常秒开，不能影响播放。
  private func cacheToDiskIfNeeded(
    book: Book,
    chapter: Chapter,
    stream: Stream,
    settings: AppSettings,
    persistDuration: ((String, Double) -> Void)?
  ) async {
    guard settings.onlinePreloadCacheEnabled else { return }
    // 移动网络：用户没允许就只解析地址，不花流量下整集
    if settings.onlinePreloadCacheWifiOnly && NetworkStatus.isExpensive {
      DLog("preload", "跳过本地缓存：当前是移动网络 第\(chapter.title)")
      return
    }

    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    guard
      !OnlineChapterFileCache.isCached(
        source: source,
        bookId: book.id,
        chapterId: chapter.id
      )
    else { return }

    let headers = HeaderProxyLoader.mergedHeaders(stream.headers)
    let minSeconds = Double(max(0, settings.onlinePreloadCacheMinSeconds))
    var seconds = chapter.duration
    if seconds <= 0 {
      // 源没给时长（新书常见）：向直链所在的服务器问一次，拿不到就按未知处理
      seconds = await AudioDurationProbe.duration(url: stream.url, headers: headers) ?? 0
      if seconds > 0 { persistDuration?(chapter.id, seconds) }
    }
    guard seconds >= minSeconds else {
      DLog(
        "preload",
        "跳过本地缓存 第\(chapter.title)：时长 \(Int(seconds))s 短于 \(Int(minSeconds))s"
      )
      return
    }

    let outcome = await ChapterDownloader.download(
      url: stream.url,
      headers: headers,
      tmpURL: OnlineChapterFileCache.tmpFile(
        source: source,
        bookId: book.id,
        chapterId: chapter.id
      ),
      fileURL: OnlineChapterFileCache.file(
        source: source,
        bookId: book.id,
        chapterId: chapter.id
      ),
      onProgress: { _, _ in }
    )

    switch outcome {
    case .stored:
      OnlinePreloadIndex.markAuto(source: source, bookId: book.id, chapterId: chapter.id)
      DLog("preload", "已存到本地 第\(chapter.title) 共 \(Int(seconds))s")
      OnlinePreloadIndex.evictIfNeeded(maxBytes: settings.onlinePreloadCacheMaxBytes)
    case .failed(let reason, let detail):
      DLog("preload", "存本地失败 第\(chapter.title)：\(reason.text) \(detail ?? "-")")
    }
  }

  /// 当前章之后连续缓存了几章（中间断掉就停，安卓同样只看连续的）。
  ///
  /// 「缓存了」包含两种情况：内存里的直链（TTL 内）和已经整段存在手机上的文件 ——
  /// 后者播放时直接命中本地，压根不需要直链。
  private func cachedAhead(book: Book, chapterIndex: Int, limit: Int) -> Int {
    lock.lock()
    let snapshot = cache
    lock.unlock()
    let now = Date()
    var ahead = 0
    var index = chapterIndex + 1
    while ahead < limit, book.chapters.indices.contains(index) {
      let chapter = book.chapters[index]
      if isCachedOnDisk(book: book, chapter: chapter) {
        ahead += 1
        index += 1
        continue
      }
      guard let entry = snapshot[cacheKey(bookId: book.id, chapterId: chapter.id)],
        now.timeIntervalSince(entry.storedAt) < Self.ttl
      else { break }
      ahead += 1
      index += 1
    }
    return ahead
  }

  /// 这一集是不是已经整段存在手机上了
  private func isCachedOnDisk(book: Book, chapter: Chapter) -> Bool {
    OnlineChapterFileCache.isCached(
      source: OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId),
      bookId: book.id,
      chapterId: chapter.id
    )
  }
}
