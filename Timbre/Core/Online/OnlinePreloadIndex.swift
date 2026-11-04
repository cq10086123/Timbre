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

  private static let lock = NSLock()
  private static var loaded = false
  private static var autoKeys: Set<String> = []

  private static var indexFile: URL {
    OnlineChapterFileCache.root.appendingPathComponent(fileName)
  }

  private static func relativePath(of url: URL) -> String {
    let rootPath = OnlineChapterFileCache.root.path + "/"
    guard url.path.hasPrefix(rootPath) else { return url.lastPathComponent }
    return String(url.path.dropFirst(rootPath.count))
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

  /// 超过上限就按「最久没碰过」删自动缓存，直到降到上限的八成。
  /// 手动缓存的章节文件一律跳过 —— 那是用户自己点的，不该被自动清掉。
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

    let target = Int64(Double(maxBytes) * 0.8)
    var removed = 0
    for entry in files.sorted(by: { $0.modified < $1.modified }) {
      guard total > target else { break }
      guard keys.contains(relativePath(of: entry.url)) else { continue }
      guard (try? FileManager.default.removeItem(at: entry.url)) != nil else { continue }
      total -= entry.size
      removed += 1
    }
    if removed > 0 {
      DLog("preload", "预加载缓存超限，删掉 \(removed) 集自动缓存，现占用 \(total) 字节")
    }
    return removed
  }

  /// 播放命中本地文件时把修改时间往前推，淘汰时才分得清「最近还在听」。
  static func touch(_ url: URL) {
    try? FileManager.default.setAttributes(
      [.modificationDate: Date()],
      ofItemAtPath: url.path
    )
  }

  // MARK: - 内部

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

  /// 调用方必须已持锁
  private static func loadIfNeeded() {
    guard !loaded else { return }
    loaded = true
    guard let data = try? Data(contentsOf: indexFile),
      let names = try? JSONDecoder().decode([String].self, from: data)
    else { return }
    autoKeys = Set(names)
  }

  /// 调用方必须已持锁
  private static func persist() {
    guard let data = try? JSONEncoder().encode(Array(autoKeys).sorted()) else { return }
    try? data.write(to: indexFile, options: .atomic)
  }
}
