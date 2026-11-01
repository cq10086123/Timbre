import Foundation

/// WebDAV 书的本地播放缓存。
///
/// 布局：`WebDavCache/<serverId>/<bookId>/<chapterId>.audio`，
/// 下载先落 `.tmp`，完整了才改名 —— 带正式名字的永远是整章。
///
/// 和「在线书源缓存」是两套目录：一个是 NAS 上的文件，一个是接口源直链，
/// 清理起来互不影响。
enum WebDavPrefetchCache {
  static let directoryName = "WebDavCache"
  static let fileExtension = "audio"

  static var root: URL {
    AppPaths.ensure(AppPaths.documents.appendingPathComponent(directoryName, isDirectory: true))
  }

  static func directory(serverId: String, bookId: String) -> URL {
    root
      .appendingPathComponent(safe(serverId), isDirectory: true)
      .appendingPathComponent(safe(bookId), isDirectory: true)
  }

  static func file(serverId: String, bookId: String, chapterId: String) -> URL {
    directory(serverId: serverId, bookId: bookId)
      .appendingPathComponent(safe(chapterId) + "." + fileExtension)
  }

  static func tmpFile(serverId: String, bookId: String, chapterId: String) -> URL {
    file(serverId: serverId, bookId: bookId, chapterId: chapterId)
      .appendingPathExtension("tmp")
  }

  static func isCached(serverId: String, bookId: String, chapterId: String) -> Bool {
    let url = file(serverId: serverId, bookId: bookId, chapterId: chapterId)
    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    return size > 0
  }

  /// 整个缓存目录占了多少字节
  static func totalBytes() -> Int64 {
    guard
      let enumerator = FileManager.default.enumerator(
        at: root,
        includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey],
        options: [.skipsHiddenFiles]
      )
    else { return 0 }
    var total: Int64 = 0
    for case let url as URL in enumerator {
      guard url.pathExtension == fileExtension else { continue }
      total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }
    return total
  }

  @discardableResult
  static func clearAll() -> Bool {
    let dir = root
    guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path),
      !names.isEmpty
    else { return false }
    for name in names {
      try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
    }
    DLog("webdav", "已清空 WebDAV 播放缓存")
    return true
  }

  /// 超过上限就把最久没碰过的文件删掉，直到降到上限的八成
  static func evictIfNeeded(maxBytes: Int64) {
    guard maxBytes > 0 else { return }
    var entries: [(url: URL, modified: Date, size: Int64)] = []
    guard
      let enumerator = FileManager.default.enumerator(
        at: root,
        includingPropertiesForKeys: [.isRegularFileKey, .fileSizeKey, .contentModificationDateKey],
        options: [.skipsHiddenFiles]
      )
    else { return }
    var total: Int64 = 0
    for case let url as URL in enumerator {
      guard url.pathExtension == fileExtension else { continue }
      let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey])
      let size = Int64(values?.fileSize ?? 0)
      total += size
      entries.append((url, values?.contentModificationDate ?? .distantPast, size))
    }
    guard total > maxBytes else { return }

    let target = Int64(Double(maxBytes) * 0.8)
    for entry in entries.sorted(by: { $0.modified < $1.modified }) {
      guard total > target else { break }
      try? FileManager.default.removeItem(at: entry.url)
      total -= entry.size
    }
    DLog("webdav", "缓存超过上限，淘汰后剩 \(Format.bytes(total))")
  }

  private static func safe(_ segment: String) -> String {
    if segment.isEmpty { return "_" }
    var out = ""
    out.reserveCapacity(segment.utf8.count)
    for byte in segment.utf8 {
      let isDigit = byte >= 48 && byte <= 57
      let isUpper = byte >= 65 && byte <= 90
      let isLower = byte >= 97 && byte <= 122
      if isDigit || isUpper || isLower || byte == 45 || byte == 46 || byte == 95 {
        out.append(Character(UnicodeScalar(byte)))
      } else {
        out += String(format: "%%%02X", Int(byte))
      }
    }
    guard out.count > 120 else { return out }
    let digest = StableHash.of(segment)
    return digest
  }
}

/// 预取 WebDAV 书的后续章节到本地。
///
/// 对齐安卓 `WebDavCachingDataSource` 的意图：听过的、将要听的章节先在本地，
/// 断网或 NAS 慢的时候不至于卡住。策略由设置里的「预取未听章节」决定，
/// 超过缓存上限时按最久未访问淘汰。
final class WebDavPrefetcher {
  private let lock = NSLock()
  private var task: Task<Void, Never>?

  /// 切章时调用：先停掉上一轮，再按当前设置排一轮新的
  func schedule(
    book: Book,
    from chapterIndex: Int,
    settings: AppSettings,
    webdav: WebDavStore
  ) {
    lock.lock()
    task?.cancel()
    task = nil
    lock.unlock()

    guard book.kind == .webdav else { return }
    guard settings.webdavPrefetchMode != .disabled, settings.webdavCacheMaxBytes > 0 else { return }
    if NetworkStatus.isExpensive && !settings.webdavPrefetchOnMetered {
      DLog("webdav", "预取跳过：当前是移动网络且未允许")
      return
    }
    guard let serverId = book.webdavServerId, let server = webdav.server(withId: serverId) else {
      return
    }
    let password = webdav.password(for: serverId)
    let limit = settings.webdavPrefetchMode.chapterLimit
    let maxBytes = settings.webdavCacheMaxBytes

    let chapters = book.chapters
    let start = min(max(0, chapterIndex) + 1, chapters.count)
    guard start < chapters.count else { return }
    let window = Array(chapters.dropFirst(start).prefix(limit == Int.max ? chapters.count : limit))
    guard !window.isEmpty else { return }

    let newTask = Task(priority: .utility) {
      for chapter in window {
        if Task.isCancelled { return }
        if WebDavPrefetchCache.isCached(
          serverId: serverId,
          bookId: book.id,
          chapterId: chapter.id
        ) {
          continue
        }
        guard let remote = chapter.remoteURL, let url = URL(string: remote) else { continue }
        _ = await Self.download(
          url: url,
          headers: WebDavClient.authHeaders(server: server, password: password),
          destination: WebDavPrefetchCache.file(
            serverId: serverId,
            bookId: book.id,
            chapterId: chapter.id
          ),
          tmp: WebDavPrefetchCache.tmpFile(
            serverId: serverId,
            bookId: book.id,
            chapterId: chapter.id
          )
        )
        WebDavPrefetchCache.evictIfNeeded(maxBytes: maxBytes)
        if Task.isCancelled { return }
      }
    }

    lock.lock()
    task = newTask
    lock.unlock()
  }

  func cancel() {
    lock.lock()
    task?.cancel()
    task = nil
    lock.unlock()
  }

  private static func download(
    url: URL,
    headers: [String: String],
    destination: URL,
    tmp: URL
  ) async -> Bool {
    var request = URLRequest(url: url)
    request.timeoutInterval = 60
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
    do {
      let (data, response) = try await URLSession.shared.data(for: request)
      guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode),
        !data.isEmpty
      else { return false }
      try? FileManager.default.createDirectory(
        at: destination.deletingLastPathComponent(),
        withIntermediateDirectories: true
      )
      try data.write(to: tmp, options: .atomic)
      if FileManager.default.fileExists(atPath: destination.path) {
        try? FileManager.default.removeItem(at: destination)
      }
      try FileManager.default.moveItem(at: tmp, to: destination)
      return true
    } catch {
      try? FileManager.default.removeItem(at: tmp)
      return false
    }
  }
}

/// 超长文件名兜底用的稳定哈希（FNV-1a，够短也够稳）
enum StableHash {
  static func of(_ text: String) -> String {
    var value: UInt64 = 0xcbf2_9ce4_8422_2325
    for byte in text.utf8 {
      value ^= UInt64(byte)
      value = value &* 0x1000_0000_01b3
    }
    return String(format: "%016llx", value)
  }
}
