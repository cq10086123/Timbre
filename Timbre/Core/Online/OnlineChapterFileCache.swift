import CryptoKit
import Foundation

/// 手动缓存下来的在线章节文件。
///
/// 目录布局对齐安卓 `OnlineChapterFileCache`：
/// `OnlineChapters/<source>/<bookId>/<chapterId>.audio`，
/// 下载先落到同级的 `.tmp`，完整了才改名 —— 带正式文件名的永远是整章。
///
/// 这一层只服务「用户手动缓存」，在线预加载与播放取流的临时结果都不写这里，
/// 两者互不干扰。
enum OnlineChapterFileCache {
  static let directoryName = "OnlineChapters"
  static let fileExtension = "audio"
  static let tmpSuffix = ".tmp"

  /// 文件名片段上限：再长就改成哈希，别撞上文件名长度限制
  private static let maxSegmentLength = 100

  static var root: URL {
    AppPaths.ensure(AppPaths.documents.appendingPathComponent(directoryName, isDirectory: true))
  }

  static func directory(source: String, bookId: String) -> URL {
    root
      .appendingPathComponent(safe(source), isDirectory: true)
      .appendingPathComponent(safe(bookId), isDirectory: true)
  }

  /// 完整文件的路径（不一定存在）
  static func file(source: String, bookId: String, chapterId: String) -> URL {
    directory(source: source, bookId: bookId)
      .appendingPathComponent(safe(chapterId) + "." + fileExtension)
  }

  /// 下载中的临时文件
  static func tmpFile(source: String, bookId: String, chapterId: String) -> URL {
    file(source: source, bookId: bookId, chapterId: chapterId)
      .appendingPathExtension("tmp")
  }

  /// 已经存着一份完整的
  static func isCached(source: String, bookId: String, chapterId: String) -> Bool {
    let url = file(source: source, bookId: bookId, chapterId: chapterId)
    guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize else { return false }
    return size > 0
  }

  static func cachedFileCount(source: String, bookId: String) -> Int {
    let dir = directory(source: source, bookId: bookId)
    guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return 0 }
    return names.filter { name in
      guard name.hasSuffix("." + fileExtension) else { return false }
      let url = dir.appendingPathComponent(name)
      let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
      return size > 0
    }.count
  }

  static func cachedBytes(source: String, bookId: String) -> Int64 {
    let dir = directory(source: source, bookId: bookId)
    guard let names = try? FileManager.default.contentsOfDirectory(atPath: dir.path) else { return 0 }
    var total: Int64 = 0
    for name in names where name.hasSuffix("." + fileExtension) {
      let url = dir.appendingPathComponent(name)
      total += Int64((try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
    }
    return total
  }

  /// 整本清掉（含下到一半的临时文件）
  @discardableResult
  static func clearBook(source: String, bookId: String) -> Bool {
    let dir = directory(source: source, bookId: bookId)
    guard FileManager.default.fileExists(atPath: dir.path) else { return false }
    do {
      try FileManager.default.removeItem(at: dir)
      return true
    } catch {
      DLog("cache", "清空缓存目录失败 \(dir.lastPathComponent)：\(error.localizedDescription)")
      return false
    }
  }

  /// 把下完的临时文件转正；没有可转正的返回 false
  @discardableResult
  static func completeDownload(source: String, bookId: String, chapterId: String) -> Bool {
    let tmp = tmpFile(source: source, bookId: bookId, chapterId: chapterId)
    let size = (try? tmp.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
    guard size > 0 else {
      try? FileManager.default.removeItem(at: tmp)
      return false
    }
    let dest = file(source: source, bookId: bookId, chapterId: chapterId)
    if FileManager.default.fileExists(atPath: dest.path) {
      try? FileManager.default.removeItem(at: dest)
    }
    do {
      try FileManager.default.moveItem(at: tmp, to: dest)
      return true
    } catch {
      DLog("cache", "转正缓存文件失败：\(error.localizedDescription)")
      try? FileManager.default.removeItem(at: tmp)
      return false
    }
  }

  static func discardTmp(source: String, bookId: String, chapterId: String) {
    try? FileManager.default.removeItem(
      at: tmpFile(source: source, bookId: bookId, chapterId: chapterId)
    )
  }

  /// 清掉下载中断留下的临时文件。
  ///
  /// 正常失败会自己删 `.tmp`，但进程被系统杀掉时来不及 —— 一集几十 MB，攒几次就上百 MB。
  /// 只删一小时前就没动过的，正在下载的不碰。
  static func purgeStaleTmpFiles(maxAge: TimeInterval = 3600) {
    guard
      let enumerator = FileManager.default.enumerator(
        at: OnlineChapterFileCache.root,
        includingPropertiesForKeys: [.contentModificationDateKey],
        options: [.skipsHiddenFiles]
      )
    else { return }
    let deadline = Date().addingTimeInterval(-maxAge)
    var removed = 0
    for case let url as URL in enumerator {
      guard url.pathExtension == "tmp" else { continue }
      let values = try? url.resourceValues(forKeys: [.contentModificationDateKey])
      let modified = values?.contentModificationDate ?? .distantPast
      guard modified < deadline else { continue }
      if (try? FileManager.default.removeItem(at: url)) != nil { removed += 1 }
    }
    if removed > 0 { DLog("cache", "清掉 \(removed) 个中断的临时文件") }
  }

  /// 从书的 `sourceId`（形如 `jdr:<包 id>`）里取出安卓意义上的 source 段
  static func sourceSegment(sourceId: String?) -> String {
    guard let sourceId, !sourceId.isEmpty else { return "unknown" }
    return sourceId.hasPrefix("jdr:") ? String(sourceId.dropFirst(4)) : sourceId
  }

  // MARK: - 文件名安全化

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
    guard out.count > maxSegmentLength else { return out }
    let digest = SHA256.hash(data: Data(segment.utf8))
    return digest.map { String(format: "%02x", $0) }.joined()
  }
}
