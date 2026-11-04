import Combine
import Foundation

/// 手动把在线书的后续章节缓存到本机，离线也能接着听。
///
/// 对齐安卓 `OnlineBookCacheManager` 的核心行为：
/// - 从**正在听的那一集**开始，往后取一个窗口，逐集顺序下载；
/// - 每集之间按设置的秒数停顿，书源才不会被整个窗口的连发请求打到限流；
/// - 一集失败不会中断整轮，连续失败 `abortAfterConsecutiveFailures` 次才收手；
/// - 每次运行都以一个说得清的原因结束（完成 / 连续失败 / 拿不到章节 / 用户不允许流量 / 意外）；
/// - 移动数据绝不悄悄花：真要联网前先问一句。
///
/// 已缓存的章节在播放时优先读本地文件（见 `PlaybackEngine.load`），
/// 没缓存的照旧在线播，所以半本缓存的书是无缝的。
///
/// 并发模型：类本身不挂 `@MainActor`（`AppState` 在非隔离上下文里创建它），
/// 但**所有碰 `@Published` 的方法都标了 `@MainActor`** —— 进度只会从主线程发出去；
/// 真正的下载走 `cacheOne`，那是非隔离的，网络与写盘都不占主线程。
final class OnlineCacheManager: ObservableObject {

  /// 每本书当前这次缓存的进度，键是 book.id
  @Published private(set) var states: [String: OnlineCacheState] = [:]
  /// 正在等用户回答「要不要用流量」
  @Published private(set) var meteredConfirmation: OnlineCacheConfirmation?

  private let library: LibraryStore
  private let extensions: ExtensionStore
  private let activity: ManualCacheActivity

  private var tasks: [String: Task<Void, Never>] = [:]
  /// 每次 start / cancel / clear 都会把代数 +1：旧任务看到代数不对就立刻收手
  private var generations: [String: Int] = [:]
  /// 连续多少次启动一集都没缓存成（对齐安卓 OnlineCacheJob.attempts）
  private var attempts: [String: Int] = [:]
  private var pendingConfirmation: CheckedContinuation<Bool, Never>?

  init(
    library: LibraryStore,
    extensions: ExtensionStore,
    activity: ManualCacheActivity
  ) {
    self.library = library
    self.extensions = extensions
    self.activity = activity
  }

  @MainActor
  func state(for bookId: String) -> OnlineCacheState? {
    states[bookId]
  }

  // MARK: - 已有缓存

  @MainActor
  func cachedInfo(for book: Book) async -> OnlineCachedInfo {
    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    let bookId = book.id
    let counts = await Task.detached(priority: .utility) {
      (
        OnlineChapterFileCache.cachedFileCount(source: source, bookId: bookId),
        OnlineChapterFileCache.cachedBytes(source: source, bookId: bookId)
      )
    }.value
    return OnlineCachedInfo(
      bookId: bookId,
      title: book.title,
      totalChapters: book.chapters.count,
      currentIndex: startIndex(of: book),
      cachedCount: counts.0,
      cachedBytes: counts.1
    )
  }

  /// 正在听的那一集就是第一个要缓存的：听到一半断网也得能接着听
  @MainActor
  func startIndex(of book: Book) -> Int {
    guard !book.chapters.isEmpty else { return 0 }
    return min(max(0, book.position.chapterIndex), book.chapters.count - 1)
  }

  // MARK: - 开始 / 停止 / 清除

  /// 从当前章往后缓存 [count] 集（含当前章）。同名任务会被替换掉。
  @MainActor
  func cacheUpcoming(book: Book, count: Int, delaySeconds: Int) {
    let bookId = book.id
    let generation = (generations[bookId] ?? 0) + 1
    generations[bookId] = generation
    tasks[bookId]?.cancel()
    dropPendingConfirmation(for: bookId)

    let clampedCount = min(max(1, count), OnlineCacheLimits.maxManualCache)
    let clampedDelay = min(max(0, delaySeconds), OnlineCacheLimits.maxEpisodeDelaySeconds)

    // 用户一点「开始缓存」进度条就得出来，不能等第一次网络往返
    states[bookId] = OnlineCacheState(
      total: clampedCount,
      done: 0,
      failed: 0,
      downloading: true
    )
    DLog("cache", "开始缓存 书=\(book.title) 集数=\(clampedCount) 间隔=\(clampedDelay)s")

    tasks[bookId] = Task { [weak self] in
      await self?.run(
        bookId: bookId,
        generation: generation,
        count: clampedCount,
        delaySeconds: clampedDelay
      )
    }
  }

  /// 停止这次缓存，已经下好的文件留着
  @MainActor
  func cancel(bookId: String) {
    generations[bookId] = (generations[bookId] ?? 0) + 1
    tasks[bookId]?.cancel()
    tasks[bookId] = nil
    // 停下来的进度条只会装作还在跑；已经下载的章节留在设备上
    states[bookId] = nil
    dropPendingConfirmation(for: bookId)
    DLog("cache", "取消缓存 书=\(bookId)")
  }

  /// 删掉这本书所有已缓存的章节（先把跑着的任务停掉）。书和进度都留在书架上。
  @discardableResult
  @MainActor
  func clearBook(_ book: Book) async -> Bool {
    let bookId = book.id
    generations[bookId] = (generations[bookId] ?? 0) + 1
    tasks[bookId]?.cancel()
    tasks[bookId] = nil
    states[bookId] = nil
    dropPendingConfirmation(for: bookId)

    // 让取消的写盘先停一拍，别把文件又写回刚删掉的目录
    try? await Task.sleep(nanoseconds: 200_000_000)

    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    let cleared = await Task.detached(priority: .utility) {
      let result = OnlineChapterFileCache.clearBook(source: source, bookId: bookId)
      // 目录没了，名单里的条目也一起作废，免得淘汰逻辑删到别的东西
      OnlinePreloadIndex.forgetBook(source: source, bookId: bookId)
      return result
    }.value
    DLog("cache", "清除缓存 书=\(book.title) 删除目录=\(cleared)")
    return cleared
  }

  // MARK: - 移动数据确认

  @MainActor
  func confirmMetered() {
    meteredConfirmation = nil
    pendingConfirmation?.resume(returning: true)
    pendingConfirmation = nil
  }

  @MainActor
  func declineMetered() {
    meteredConfirmation = nil
    pendingConfirmation?.resume(returning: false)
    pendingConfirmation = nil
  }

  @MainActor
  private func dropPendingConfirmation(for bookId: String) {
    guard meteredConfirmation?.bookId == bookId else { return }
    meteredConfirmation = nil
    pendingConfirmation?.resume(returning: false)
    pendingConfirmation = nil
  }

  @MainActor
  private func askMetered(bookId: String, title: String, chapters: Int) async -> Bool {
    await withCheckedContinuation { continuation in
      // 单槽：第二本书的确认请求会顶掉第一本的 continuation。
      // 被顶掉的必须当场 resume，否则它的 await 永久挂起（continuation 泄漏）
      if let previous = pendingConfirmation {
        previous.resume(returning: false)
      }
      pendingConfirmation = continuation
      meteredConfirmation = OnlineCacheConfirmation(
        bookId: bookId,
        title: title,
        chapters: chapters
      )
    }
  }

  // MARK: - 主流程

  @MainActor
  private func run(
    bookId: String,
    generation: Int,
    count: Int,
    delaySeconds: Int
  ) async {
    // 这一轮从开始到结束都占着这本书：自动预加载看到就让路，别两边一起打源接口。
    // 放在 run 里用 defer 收尾，是因为这里有十几个提前 return 的分支，逐个写容易漏
    activity.begin(bookId)
    defer { activity.end(bookId) }

    guard isCurrent(bookId, generation) else { return }
    guard let book = library.book(withId: bookId) else {
      stop(bookId, generation, .noChapterList)
      return
    }

    var chapters = book.chapters
    if chapters.isEmpty {
      // 书架上的章节可能还没拉到（刚加入书架就点了缓存）：问一次源
      chapters = await fetchChapters(book: book)
    }
    guard !chapters.isEmpty else {
      stop(bookId, generation, .noChapterList)
      DLog("cache", "拿不到章节列表 书=\(book.title)")
      return
    }

    let startOffset = min(max(0, startIndex(of: book)), chapters.count - 1)
    let window = Array(chapters.dropFirst(startOffset).prefix(count))
    guard !window.isEmpty else {
      stop(bookId, generation, .noChapterList)
      return
    }

    // 移动数据先问一句：没答应之前一个请求都不发
    var meteredAllowed = false
    if NetworkStatus.isExpensive {
      meteredAllowed = await askMetered(
        bookId: bookId,
        title: book.title,
        chapters: window.count
      )
      if !meteredAllowed {
        // 什么都没下，直接结束，不留下一个永远不动的进度条
        stop(bookId, generation, .meteredDeclined)
        DLog("cache", "用户没允许使用移动数据，取消本次缓存")
        return
      }
    }

    let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
    var marks = Array(
      repeating: OnlineCacheMark.pending,
      count: min(window.count, OnlineCacheLimits.maxMarks)
    )
    var done = 0
    for (offset, chapter) in window.enumerated() {
      if OnlineChapterFileCache.isCached(
        source: source,
        bookId: bookId,
        chapterId: chapter.id
      ) {
        done += 1
        if offset < marks.count { marks[offset] = .cached }
      }
    }
    let doneAtStart = done

    var failed = 0
    var consecutiveFailures = 0
    var failures: [OnlineCacheFailure] = []
    var pacedAny = false
    var declined = false
    var stopReason: OnlineCacheStopReason?

    updateState(bookId, generation) { state in
      state.total = window.count
      state.done = done
      state.failed = 0
      state.marks = marks
      state.downloading = true
      state.stopReason = nil
    }

    do {
      for (offset, chapter) in window.enumerated() {
        try Task.checkCancellation()
        guard isCurrent(bookId, generation) else { return }
        if OnlineChapterFileCache.isCached(
          source: source,
          bookId: bookId,
          chapterId: chapter.id
        ) {
          continue
        }

        // 缓到一半网络从 Wi-Fi 变成流量：下一集是新的流量决定，得再问一次
        if NetworkStatus.isExpensive && !meteredAllowed {
          meteredAllowed = await askMetered(
            bookId: bookId,
            title: book.title,
            chapters: window.count - offset
          )
          if !meteredAllowed {
            declined = true
            break
          }
        }
        guard isCurrent(bookId, generation) else { return }

        updateState(bookId, generation) { state in
          state.currentTitle = chapter.title
          state.currentBytes = 0
          state.currentTotalBytes = -1
          state.bytesPerSecond = 0
        }
        setMark(bookId, generation, offset, .running)

        // 集间停顿：让书源喘口气，别把整个窗口连发上去换限流
        if pacedAny && delaySeconds > 0 {
          try await Task.sleep(nanoseconds: UInt64(delaySeconds) * 1_000_000_000)
          try Task.checkCancellation()
        }
        pacedAny = true

        let outcome = await cacheOne(
          book: book,
          bookId: bookId,
          generation: generation,
          chapter: chapter,
          source: source
        )
        guard isCurrent(bookId, generation) else { return }

        switch outcome {
        case .stored:
          done += 1
          consecutiveFailures = 0
          setMark(bookId, generation, offset, .cached)
          updateState(bookId, generation) { $0.done = done }
          // 用户手动缓存下来的这一集，从此不算「自动缓存」，超限淘汰不许动它
          OnlinePreloadIndex.forget(source: source, bookId: bookId, chapterId: chapter.id)
        case .failed(let reason, let detail):
          failed += 1
          consecutiveFailures += 1
          if failures.count < OnlineCacheLimits.maxListedFailures {
            failures.append(
              OnlineCacheFailure(
                title: chapter.title,
                reason: reason,
                chapterOrder: startOffset + offset + 1,
                index: offset,
                detail: detail.map { String($0.prefix(OnlineCacheLimits.maxFailureDetailCharacters)) }
              )
            )
          }
          setMark(bookId, generation, offset, .failed)
          updateState(bookId, generation) { state in
            state.done = done
            state.failed = failed
            state.failures = failures
          }
          DLog("cache", "第\(startOffset + offset + 1)集失败 \(reason.text)")
        }

        if consecutiveFailures >= OnlineCacheLimits.abortAfterConsecutiveFailures {
          break
        }
      }

      if declined {
        stopReason = .meteredDeclined
      } else if done >= window.count {
        stopReason = .completed
      } else if consecutiveFailures >= OnlineCacheLimits.abortAfterConsecutiveFailures {
        stopReason = .tooManyFailures
      }
    } catch is CancellationError {
      // 取消不算失败：已经下好的留着，进度条直接收掉
      return
    } catch {
      DLog("cache", "缓存任务异常：\(error.localizedDescription)")
      stopReason = .error
    }

    finishRun(
      bookId: bookId,
      generation: generation,
      stopReason: stopReason,
      done: done,
      windowCount: window.count,
      doneAtStart: doneAtStart,
      declined: declined
    )
  }

  /// 收尾：把「没有一集成功」的连续尝试数记下来，够次数就明说放弃了
  @MainActor
  private func finishRun(
    bookId: String,
    generation: Int,
    stopReason: OnlineCacheStopReason?,
    done: Int,
    windowCount: Int,
    doneAtStart: Int,
    declined: Bool
  ) {
    guard isCurrent(bookId, generation) else { return }
    tasks[bookId] = nil

    updateState(bookId, generation) { state in
      state.downloading = false
      state.awaitingConfirmation = false
      state.currentTitle = ""
      state.currentBytes = 0
      state.currentTotalBytes = -1
      state.bytesPerSecond = 0
      state.stopReason = stopReason
    }

    let unfinished = done < windowCount && !declined
    guard unfinished else {
      attempts[bookId] = 0
      return
    }
    let progressed = done > doneAtStart
    let tries = progressed ? 0 : (attempts[bookId] ?? 0) + 1
    attempts[bookId] = tries
    if tries >= OnlineCacheLimits.maxResumeAttempts {
      // 连着几次一集都没下成，再重试也是白费：明确告诉用户是书源的问题
      updateState(bookId, generation) { $0.stopReason = .gaveUp }
      attempts[bookId] = 0
      DLog("cache", "连续 \(tries) 次没有任何进展，放弃这本书的缓存")
    }
  }

  // MARK: - 单集

  private func cacheOne(
    book: Book,
    bookId: String,
    generation: Int,
    chapter: Chapter,
    source: String
  ) async -> ChapterDownloader.Outcome {
    let resolved: (url: String, headers: [String: String])
    do {
      resolved = try await resolveStream(book: book, chapter: chapter)
    } catch {
      return .failed(.resolve, Self.cleanError(error))
    }
    guard let url = URL(string: resolved.url) else {
      return .failed(.resolve, "源返回的地址无法解析：\(String(resolved.url.prefix(120)))")
    }

    let tmpURL = OnlineChapterFileCache.tmpFile(
      source: source,
      bookId: bookId,
      chapterId: chapter.id
    )
    let fileURL = OnlineChapterFileCache.file(
      source: source,
      bookId: bookId,
      chapterId: chapter.id
    )
    let sampler = SpeedSampler()

    return await ChapterDownloader.download(
      url: url,
      headers: resolved.headers,
      tmpURL: tmpURL,
      fileURL: fileURL,
      onProgress: { written, total in
        let speed = sampler.sample(written)
        Task { @MainActor [weak self] in
          guard let self, self.isCurrent(bookId, generation) else { return }
          self.updateState(bookId, generation) { state in
            state.currentBytes = written
            state.currentTotalBytes = total
            state.bytesPerSecond = speed
          }
        }
      }
    )
  }

  private func resolveStream(
    book: Book,
    chapter: Chapter
  ) async throws -> (url: String, headers: [String: String]) {
    guard let installed = extensions.source(withId: book.sourceId),
      let script = extensions.scriptText(for: installed)
    else {
      throw CacheEngineError.noSource
    }
    return try await ExtensionSourceService.shared.audio(
      script: script,
      allowInsecure: installed.allowInsecure,
      bookId: book.remoteBookId ?? book.id,
      chapterId: chapter.id,
      extraJSON: chapter.extraJSON
    )
  }

  private func fetchChapters(book: Book) async -> [Chapter] {
    guard let installed = extensions.source(withId: book.sourceId),
      let script = extensions.scriptText(for: installed)
    else { return [] }
    let fresh = try? await ExtensionSourceService.shared.chapters(
      script: script,
      allowInsecure: installed.allowInsecure,
      bookId: book.remoteBookId ?? book.id,
      extraJSON: book.sourceExtraJSON
    )
    return fresh ?? []
  }

  // MARK: - 状态辅助

  @MainActor
  private func isCurrent(_ bookId: String, _ generation: Int) -> Bool {
    generations[bookId] == generation
  }

  @MainActor
  private func updateState(
    _ bookId: String,
    _ generation: Int,
    _ transform: (inout OnlineCacheState) -> Void
  ) {
    guard isCurrent(bookId, generation), var state = states[bookId] else { return }
    transform(&state)
    states[bookId] = state
  }

  @MainActor
  private func setMark(
    _ bookId: String,
    _ generation: Int,
    _ index: Int,
    _ value: OnlineCacheMark
  ) {
    updateState(bookId, generation) { state in
      guard index >= 0, index < state.marks.count else { return }
      state.marks[index] = value
    }
  }

  @MainActor
  private func stop(
    _ bookId: String,
    _ generation: Int,
    _ reason: OnlineCacheStopReason
  ) {
    updateState(bookId, generation) { state in
      state.downloading = false
      state.awaitingConfirmation = false
      state.currentTitle = ""
      state.currentBytes = 0
      state.currentTotalBytes = -1
      state.bytesPerSecond = 0
      state.stopReason = reason
    }
  }

  /// 把异常里的框架前缀和 JS 调用栈剥掉，只留书源自己说的话
  static func cleanError(_ error: Error) -> String {
    var text = error.localizedDescription.trimmingCharacters(in: .whitespacesAndNewlines)
    while true {
      let stripped = text.replacingOccurrences(
        of: #"^([\w.$]*(Exception|Error|Failure|Throwable)|Error):\s*"#,
        with: "",
        options: .regularExpression
      ).trimmingCharacters(in: .whitespacesAndNewlines)
      if stripped == text || stripped.isEmpty { break }
      text = stripped
    }
    let lines = text.split(separator: "\n", omittingEmptySubsequences: false)
    let kept = lines.filter { line in
      let pattern = #"^\s*at\s+\S.*\([^()]*:\d+(?::\d+)?\)\s*$|^\s*at\s+\S+:\d+:\d+\s*$"#
      return line.range(of: pattern, options: .regularExpression) == nil
    }
    let joined = kept.joined(separator: "\n").trimmingCharacters(in: .whitespacesAndNewlines)
    return joined.isEmpty ? text : joined
  }
}

enum CacheEngineError: LocalizedError {
  case noSource

  var errorDescription: String? {
    switch self {
    case .noSource: return "接口源未启用或已被删除"
    }
  }
}

/// 「手动缓存正在跑哪几本书」的线程安全快照。
///
/// 需要它的原因：`OnlineCacheManager` 的状态是 `@MainActor` 的，而播放引擎在
/// 非主线程的播放回调里驱动自动预加载 —— 那边读不到 `states`。
/// 这个类只做一件事：记下哪几本书正在手动缓存，让自动预加载知道什么时候让路。
///
/// 用计数而不是布尔：新任务顶掉旧任务时，旧的收尾不能把新的「正在忙」标记抹掉。
final class ManualCacheActivity {

  private let lock = NSLock()
  private var books: [String: Int] = [:]

  func begin(_ bookId: String) {
    lock.lock()
    books[bookId, default: 0] += 1
    lock.unlock()
  }

  func end(_ bookId: String) {
    lock.lock()
    if let count = books[bookId], count > 1 {
      books[bookId] = count - 1
    } else {
      books.removeValue(forKey: bookId)
    }
    lock.unlock()
  }

  func isBusy(_ bookId: String) -> Bool {
    lock.lock()
    defer { lock.unlock() }
    return books[bookId] != nil
  }
}

/// 平滑速率：原始采样跳得厉害，平滑过的数才能看出是源慢还是文件大
private final class SpeedSampler {
  private var lastBytes: Int64 = 0
  private var lastAt: TimeInterval = 0
  private var rate: Int64 = 0

  func sample(_ bytes: Int64) -> Int64 {
    let now = Date().timeIntervalSince1970
    if lastAt == 0 {
      lastBytes = bytes
      lastAt = now
      return 0
    }
    let elapsed = now - lastAt
    if elapsed < 0.7 { return rate }
    let delta = bytes - lastBytes
    lastAt = now
    lastBytes = bytes
    if delta <= 0 { return rate }
    let instant = Int64(Double(delta) / elapsed)
    rate = rate == 0 ? instant : (rate * 3 + instant) / 4
    return rate
  }
}
