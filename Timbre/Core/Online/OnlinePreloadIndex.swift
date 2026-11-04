import Foundation

/// 记下「哪些缓存文件是预加载自动下的」，给容量淘汰用。
///
/// 文件仍放在 `OnlineChapterFileCache` 的同一套目录里，所以「已缓存 X 集」、
/// 「清除缓存」、播放命中本地文件这些现成能力全部照旧生效；这里只多存一份名单，
/// 保证空间满了以后**只删自动下的**，用户手动点过「缓存」的永不淘汰。
///
/// 名单以「相对缓存根目录的路径」为键，避免和 `OnlineChapterFileCache` 的文件名
/// 安全化规则各写一套。名单本身是个 json，落在缓存根目录下，`cachedFileCount`
/// 只数 `.audio` 后缀，不会被它算进去。
enum OnlinePreloadIndex {

  private static let fileName = ".preload-index.json"

  /// 刚下完的文件在这么多秒内不参与淘汰。
  ///
  /// 不设这个保护的话，遇到「手动缓存自己就超过上限」的情况（手动缓存不受上限约束，
  /// 很容易几十集上十 GB）会变成：下一集 → 超限 → 删掉刚下这一集 → 下一集再来一遍，
  /// 流量全白烧，一集都存不下来。
  private static let protectWindow: TimeInterval = 600

  private static let lock = NSLock()
  private static var loaded = false
  private static var autoKeys: Set<String> = []

  private static var indexFile: URL {
    OnlineChapterFileCache.root.appendingPathComponent(fileName)
  }

  private static func relativePath(of url: URL, rootPath: String? = nil) -> String {
    // rootPath 可以由调用方算好传进来：循环里逐个问 root 会连带每次 ensure 的 fileExists
    let prefix = rootPath ?? (OnlineChapterFileCache.root.path + "/")
    guard url.path.hasPrefix(prefix) else { return url.lastPathComponent }
    return String(url.path.dropFirst(prefix.count))
  }

  private static func key(source: String, bookId: String, chapterId: String) -> String {
    relativePath(
      of: OnlineChapterFileCache.file(source: source, bookId: bookId, chapterId: chapterId)
    )
  }

  // MARK: - 名单

  /// 这一集是预加载自动存的
  static func markAuto(source: String, bookId: String, chapterId: String) {
    lock.lock()
    loadIfNeeded()
    autoKeys.insert(key(source: source, bookId: bookId, chapterId: chapterId))
    persist()
    lock.unlock()
  }

  /// 这一集已经不属于「自动缓存」了（用户手动缓存覆盖、整本清除）。
  ///
  /// 必须清，否则脏名单会让淘汰逻辑把手动缓存的文件也删掉。
  static func forget(source: String, bookId: String, chapterId: String) {
    lock.lock()
    loadIfNeeded()
    if autoKeys.remove(key(source: source, bookId: bookId, chapterId: chapterId)) != nil {
      persist()
    }
    lock.unlock()
  }

  static func forgetBook(source: String, bookId: String) {
    lock.lock()
    loadIfNeeded()
    let prefix = relativePath(
      of: OnlineChapterFileCache.directory(source: source, bookId: bookId)
    ) + "/"
    let before = autoKeys.count
    autoKeys = autoKeys.filter { !$0.hasPrefix(prefix) }
    if autoKeys.count != before { persist() }
    lock.unlock()
  }

  // MARK: - 容量

  /// 超过上限就按「最久以前下载的」删自动缓存，直到降到上限的八成。
  /// 手动缓存的章节文件一律跳过 —— 那是用户自己点的，不该被自动清掉。
  ///
  /// 排序用文件修改时间（= 下载完成时间），不去碰访问时间：播放时改 mtime 会让
  /// `CachedAudioAlias` 每次都觉得别名过期、反复删了重建，硬链接失败时还要同步
  /// 复制整个文件 —— 那是在主线程上发生的。
  @discardableResult
  static func evictIfNeeded(maxBytes: Int64) -> Int {
    guard maxBytes > 0 else { return 0 }
    let files = audioFiles()
    var total = files.reduce(Int64(0)) { $0 + $1.size }
    guard total > maxBytes else { return 0 }

    lock.lock()
    loadIfNeeded()
    let keys = autoKeys
    lock.unlock()

    let rootPath = OnlineChapterFileCache.root.path + "/"
    let protectAfter = Date().addingTimeInterval(-Self.protectWindow)
    let target = Int64(Double(maxBytes) * 0.8)
    var removed = 0
    for entry in files.sorted(by: { $0.modified < $1.modified }) {
      guard total > target else { break }
      // 刚写进来的先留着，否则会「刚下完就删」，白白烧流量
      guard entry.modified < protectAfter else { continue }
      guard keys.contains(relativePath(of: entry.url, rootPath: rootPath)) else { continue }
      guard (try? FileManager.default.removeItem(at: entry.url)) != nil else { continue }
      // 硬链接别名（`.mp3` / `.m4a` …）还指着同一份数据，不一起删空间释放不掉
      removeAliases(of: entry.url)
      total -= entry.size
      removed += 1
    }
    if removed > 0 {
      DLog("preload", "预加载缓存超限，删掉 \(removed) 集自动缓存，现占用 \(total) 字节")
    }
    return removed
  }

  // MARK: - 内部

  /// 删掉 `CachedAudioAlias` 为这一集建的播放别名（`.mp3` / `.m4a` …）。
  ///
  /// 别名优先是硬链接，和原件共用同一份数据：只删 `.audio` 原件的话 inode 还被别名
  /// 引用着，空间一点没释放；复制兜底建出来的别名更是白占一整份。
  private static func removeAliases(of fileURL: URL) {
    let directory = fileURL.deletingLastPathComponent()
    let stem = fileURL.deletingPathExtension().lastPathComponent
    guard
      let names = try? FileManager.default.contentsOfDirectory(atPath: directory.path)
    else { return }
    for name in names {
      let candidate = directory.appendingPathComponent(name)
      // 去掉后缀再比对，避免「1」把「1.2」的别名也删了
      guard candidate.deletingPathExtension().lastPathComponent == stem else { continue }
      let ext = candidate.pathExtension
      guard ext != OnlineChapterFileCache.fileExtension, ext != "tmp" else { continue }
      try? FileManager.default.removeItem(at: candidate)
    }
  }

  private struct Entry {
    var url: URL
    var size: Int64
    var modified: Date
  }

  private static func audioFiles() -> [Entry] {
    let root = OnlineChapterFileCache.root
    guard
      let enumerator = FileManager.default.enumerator(
        at: root,
        includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
        options: [.skipsHiddenFiles]
      )
    else { return [] }
    var entries: [Entry] = []
    for case let url as URL in enumerator {
      guard url.pathExtension == OnlineChapterFileCache.fileExtension else { continue }
      let values = try? url.resourceValues(
        forKeys: [.fileSizeKey, .contentModificationDateKey]
      )
      let size = Int64(values?.fileSize ?? 0)
      guard size > 0 else { continue }
      entries.append(
        Entry(url: url, size: size, modified: values?.contentModificationDate ?? .distantPast)
      )
    }
    return entries
  }

  /// 索引文件的结构。
  ///
  /// 带版本号是因为这份名单决定「哪些文件可以被淘汰」：一旦格式变了却静默解析失败，
  /// 名单会变空，所有已有的自动缓存都会被当成「手动缓存」而永远不被淘汰 ——
  /// 空间只增不减，用户只能手动清整本。宁可作废重建，也不要静默失忆。
  private struct IndexFile: Codable {
    var version: Int
    var keys: [String]
  }

  private static let indexVersion = 1

  /// 调用方必须已持锁
  private static func loadIfNeeded() {
    guard !loaded else { return }
    loaded = true
    guard let data = try? Data(contentsOf: indexFile) else { return }

    if let file = try? JSONDecoder().decode(IndexFile.self, from: data),
      file.version == indexVersion
    {
      autoKeys = Set(file.keys)
      return
    }
    // 旧格式（裸数组）·升级时迁移，别把老用户已经下好的自动缓存全丢了
    if let names = try? JSONDecoder().decode([String].self, from: data) {
      autoKeys = Set(names)
      persist()
      return
    }
    DLog("preload", "自动缓存名单读不出来，作废重建")
    try? FileManager.default.removeItem(at: indexFile)
  }

  /// 调用方必须已持锁
  private static func persist() {
    let file = IndexFile(version: indexVersion, keys: Array(autoKeys).sorted())
    guard let data = try? JSONEncoder().encode(file) else { return }
    try? data.write(to: indexFile, options: .atomic)
  }
}
