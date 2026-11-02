import Combine
import Foundation

/// 书架备份文件的内容 —— 对齐安卓 `WebDavBackup`。
///
/// 只存「书架上有哪些书、每本听到哪、播放偏好是什么」。
/// 备份不是导入书的手段：章节与音频文件仍然属于本机（或 WebDAV 源）。
struct WebDavBackup: Codable {
  var version: Int = currentVersion
  var createdAt: String
  var books: [WebDavBackupBook]

  static let currentVersion = 2

  enum CodingKeys: String, CodingKey {
    case version, createdAt, books
  }

  /// 默认备份就放在备份目录下，不需要为它单独建一个集合
  static let defaultFileName = ".timbre-backup.json"
  /// 带标签的备份排在默认备份旁边
  static let filePrefix = ".timbre-backup-"
  static let fileSuffix = ".json"
  static let contentType = "application/json"

  private static let maxLabelLength = 64

  /// 标签对应的文件名；空标签就是那份没名字的默认备份
  static func fileName(label: String?) -> String {
    guard let raw = label?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else {
      return defaultFileName
    }
    let clean = raw
      .replacingOccurrences(of: "/", with: "_")
      .replacingOccurrences(of: "\\", with: "_")
    let clipped = String(clean.prefix(maxLabelLength))
    return filePrefix + clipped + fileSuffix
  }

  /// 文件名里带的标签，默认备份返回 nil
  static func label(of fileName: String) -> String? {
    if fileName == defaultFileName { return nil }
    guard fileName.hasPrefix(filePrefix), fileName.hasSuffix(fileSuffix) else { return nil }
    let inner = fileName.dropFirst(filePrefix.count).dropLast(fileSuffix.count)
    return inner.isEmpty ? nil : String(inner)
  }

  static func isBackupFile(_ fileName: String) -> Bool {
    fileName == defaultFileName || label(of: fileName) != nil
  }
}

/// 一本书在备份里的样子 —— 字段名与安卓一致，两边可以互相读。
struct WebDavBackupBook: Codable {
  var id: String
  var name: String
  var author: String?
  /// 是否在书架上
  var isActive: Bool
  var currentChapter: String
  var positionInChapter: Int64
  var lastPlayedAt: String
  var playbackSpeed: Float
  var skipSilence: Bool
  var skipIntro: Int64 = 0
  var skipOutro: Int64 = 0
  var gain: Float = 0
  /// 换设备后 id 变了时的匹配提示
  var chapterCount: Int
  var totalDurationMs: Int64

  enum CodingKeys: String, CodingKey {
    case id, name, author, isActive, currentChapter, positionInChapter
    case lastPlayedAt, playbackSpeed, skipSilence, skipIntro, skipOutro, gain
    case chapterCount, totalDurationMs
  }
}

/// 安卓 kotlinx.serialization 默认不写「值等于默认值」的字段（encodeDefaults=false）：
/// 顶层 version=2 / extras={} 会被省略，书条目里 skipIntro / skipOutro / gain 经常整个缺席，
/// author 为 null 时也没有这个键。Swift 合成的 Decodable 对非 Optional 键是强制的
/// （属性默认值不豁免），直接解码会 keyNotFound。对有默认值的字段全部 decodeIfPresent 兜底。
extension WebDavBackupBook {
  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    name = try c.decode(String.self, forKey: .name)
    author = try c.decodeIfPresent(String.self, forKey: .author)
    isActive = try c.decode(Bool.self, forKey: .isActive)
    currentChapter = try c.decode(String.self, forKey: .currentChapter)
    positionInChapter = try c.decode(Int64.self, forKey: .positionInChapter)
    lastPlayedAt = try c.decode(String.self, forKey: .lastPlayedAt)
    playbackSpeed = try c.decode(Float.self, forKey: .playbackSpeed)
    skipSilence = try c.decode(Bool.self, forKey: .skipSilence)
    skipIntro = try c.decodeIfPresent(Int64.self, forKey: .skipIntro) ?? 0
    skipOutro = try c.decodeIfPresent(Int64.self, forKey: .skipOutro) ?? 0
    gain = try c.decodeIfPresent(Float.self, forKey: .gain) ?? 0
    chapterCount = try c.decode(Int.self, forKey: .chapterCount)
    totalDurationMs = try c.decode(Int64.self, forKey: .totalDurationMs)
  }
}

extension WebDavBackup {
  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    version = try c.decodeIfPresent(Int.self, forKey: .version) ?? Self.currentVersion
    createdAt = try c.decode(String.self, forKey: .createdAt)
    books = try c.decode([WebDavBackupBook].self, forKey: .books)
    // 安卓的 extras（书架分区扩展）iOS 暂不消费，未知键直接忽略
  }
}
/// 备份目标：一台服务器 + 用户亲手选的目录。
///
/// 只有服务器还不够 —— 目录必须在设置里明确选过，
/// 这样备份永远不会写进用户没指定的地方。
struct WebDavBackupTarget: Codable {
  var serverId: String?
  var folderUrl: String?
  /// 上次写出的备份标签，下次默认填进对话框
  var label: String?

  var isConfigured: Bool {
    guard let serverId, !serverId.isEmpty else { return false }
    guard let folderUrl else { return false }
    return !folderUrl.isEmpty
  }
}

/// 把 target 套到当前配置的服务器上
struct WebDavBackupLocation {
  var serverId: String
  var serverName: String
  var serverBaseUrl: String
  var folderUrl: String
}

/// 能不能备份：服务器 + 目录
enum WebDavBackupTargetStatus {
  case ready
  case noServer
  case noFolder
}

enum WebDavBackupResult {
  case success(bookCount: Int, serverName: String, label: String?)
  /// 没配服务器，或者密码解不出来
  case noServer
  /// 有服务器，但还没选备份目录
  case noFolder
  /// 书架是空的，写下去只会毁掉上一份备份
  case emptyShelf
  /// 还有书在导入，章节没存完
  case booksLoading(Int)
  case failure(String)
}

enum WebDavRestoreResult {
  case success(
    restored: Int,
    total: Int,
    serverName: String,
    label: String?,
    /// 备份里有、本机没有的 WebDAV 书：它们的章节来自服务器，得先重新添加书源
    missingWebDav: Int
  )
  case noServer
  case noFolder
  case noBackupFile
  case failure(String)
}

/// 备份目录里的一个备份文件
struct WebDavBackupItem: Identifiable, Hashable {
  /// 默认备份为 nil
  var label: String?
  var fileName: String
  var lastModified: TimeInterval
  var size: Int64

  var id: String { fileName }
}

enum WebDavBackupListResult {
  case success(items: [WebDavBackupItem], serverName: String)
  case noServer
  case noFolder
  case failure(String)
}

/// 备份目标与上次用的标签的持久化
final class WebDavBackupStore: ObservableObject {
  @Published private(set) var target = WebDavBackupTarget()

  private var fileURL: URL {
    AppPaths.documents.appendingPathComponent("webdav-backup-target.json")
  }

  init() {
    load()
  }

  func save(_ next: WebDavBackupTarget) {
    target = next
    persist()
  }

  func rememberLabel(_ label: String?) {
    var next = target
    next.label = label
    save(next)
  }

  var lastLabel: String? { target.label }

  private func load() {
    guard let data = try? Data(contentsOf: fileURL),
      let decoded = try? JSONCoding.decoder().decode(WebDavBackupTarget.self, from: data)
    else { return }
    target = decoded
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(target) else { return }
    try? data.write(to: fileURL, options: .atomic)
  }
}
