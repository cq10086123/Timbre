import AVFoundation
import Combine
import Foundation

/// WebDAV 导入后逐章探测时长（对齐安卓「导入并发度」/ MetadataRetriever 解析）。
///
/// 安卓导入时逐章读文件头拿时长；iOS 导入只列目录（章节 duration=0，
/// 播放到哪章哪章才有时长，书架总时长 / 进度百分比全是 0）。
/// 这里在导入完成后用 AVURLAsset 远程读文件头 —— AVFoundation 自己发
/// Range 请求只拉头几十 KB，不下载整文件 —— 按 1/2/3 的并发度解析，
/// 解析完回写书架（总时长、百分比立刻就位）。
@MainActor
final class WebDavDurationAnalyzer: ObservableObject {
  /// bookId → (done, total)。书架 / 浏览页读这里显示「正在解析 x/y」。
  @Published private(set) var progress: [String: (done: Int, total: Int)] = [:]

  /// 所有书合计的进度（多本书排队时的聚合显示）
  var totalProgress: (done: Int, total: Int) {
    var done = 0
    var total = 0
    for p in progress.values { done += p.done; total += p.total }
    return (done, total)
  }

  private let library: LibraryStore
  private let settings: SettingsStore
  /// 一次只跑一本书，剩下的排队 —— 别跟播放抢带宽
  private var queue: [(book: Book, server: WebDavServer, password: String)] = []
  private var running = false

  init(library: LibraryStore, settings: SettingsStore) {
    self.library = library
    self.settings = settings
  }

  /// 导入完成后调用。重复调用同一本书不会重复解析（只挑 duration<=0 的章节）。
  func analyze(book: Book, server: WebDavServer, password: String) {
    queue.append((book, server, password))
    runNextIfNeeded()
  }

  private func runNextIfNeeded() {
    guard !running, !queue.isEmpty else { return }
    let job = queue.removeFirst()
    running = true
    Task { await analyze(job.book, server: job.server, password: job.password) }
  }

  private func analyze(book: Book, server: WebDavServer, password: String) async {
    defer {
      progress[book.id] = nil
      running = false
      runNextIfNeeded()
    }
    let targets = book.chapters.filter { $0.duration <= 0 && $0.remoteURL != nil }
    guard !targets.isEmpty else { return }
    // 设置中途改了就按本次开跑时的值走，下一本书再生效
    let parallelism = max(1, min(3, settings.settings.webdavImportParallelism))
    progress[book.id] = (0, targets.count)
    DLog("webdav", "开始解析章节时长 \(book.title) 共 \(targets.count) 章，并发 \(parallelism)")

    let headers = WebDavClient.authHeaders(server: server, password: password)
    var durations: [String: Double] = [:]
    var done = 0
    var iterator = targets.makeIterator()

    await withTaskGroup(of: (id: String, duration: Double?).self) { group in
      // 先填满并发窗口，完成一个补一个，窗口宽度就是设置里的并发度
      for _ in 0..<parallelism {
        if let chapter = iterator.next() {
          group.addTask { (chapter.id, await Self.probe(chapter, headers: headers)) }
        }
      }
      while let (id, duration) = await group.next() {
        if let duration, duration > 0 { durations[id] = duration }
        done += 1
        progress[book.id] = (done, targets.count)
        if let chapter = iterator.next() {
          group.addTask { (chapter.id, await Self.probe(chapter, headers: headers)) }
        }
      }
    }

    if !durations.isEmpty {
      library.updateChapterDurations(bookId: book.id, durations: durations)
      DLog("webdav", "解析完成 \(durations.count)/\(targets.count) 章 → \(book.title)")
    }
  }

  /// 单章时长：AVURLAsset 读文件头（Range 请求，几 KB～几十 KB）
  private nonisolated static func probe(
    _ chapter: Chapter, headers: [String: String]
  ) async -> Double? {
    guard let remote = chapter.remoteURL, let url = URL(string: remote) else { return nil }
    var options: [String: Any] = [:]
    if !headers.isEmpty { options[AVURLAssetHTTPRequestsHeadersKey] = headers }
    let asset = AVURLAsset(url: url, options: options.isEmpty ? nil : options)
    guard let seconds = try? await asset.load(.duration).seconds else { return nil }
    return seconds.isFinite && seconds > 0 ? seconds : nil
  }
}
