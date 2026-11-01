import Foundation

/// 一本书的来源：本地导入 / 接口源（.jdr）/ WebDAV 远程目录
enum BookSourceKind: String, Codable {
  case local
  case jdr
  case webdav
}

/// 一集（章节）
struct Chapter: Codable, Identifiable, Hashable {
  var id: String
  var title: String
  /// 本地书：相对 Documents 的文件路径
  var localFile: String?
  /// 在线书源：缓存下来的直链（可能过期，播放前会重新解析）
  var remoteURL: String?
  var duration: Double
  /// 接口源透传字段（原始 JSON 对象文本）
  var extraJSON: String?

  init(
    id: String = UUID().uuidString,
    title: String,
    localFile: String? = nil,
    remoteURL: String? = nil,
    duration: Double = 0,
    extraJSON: String? = nil
  ) {
    self.id = id
    self.title = title
    self.localFile = localFile
    self.remoteURL = remoteURL
    self.duration = duration
    self.extraJSON = extraJSON
  }
}

/// 断点续播位置
struct PlaybackPosition: Codable, Hashable {
  var chapterIndex: Int = 0
  var seconds: Double = 0
  var updatedAt: Date?

  var isStarted: Bool { chapterIndex > 0 || seconds > 0.5 }
}

struct Bookmark: Codable, Identifiable, Hashable {
  var id: String = UUID().uuidString
  var chapterIndex: Int
  var seconds: Double
  var title: String
  var createdAt: Date = Date()
}

/// 一本书
struct Book: Codable, Identifiable, Hashable {
  var id: String
  var title: String
  var author: String
  var kind: BookSourceKind
  /// 本地书：相对 Documents 的文件夹路径
  var folder: String?
  var coverFile: String?
  var chapters: [Chapter]
  var addedAt: Date
  var position: PlaybackPosition
  var bookmarks: [Bookmark]
  /// 跳过片头 / 片尾秒数，0 = 关闭
  var skipIntro: Int
  var skipOutro: Int
  var speed: Double
  /// 接口源
  var sourceId: String?
  var sourceName: String?
  var remoteBookId: String?
  var sourceExtraJSON: String?
  /// 已经为哪一章执行过片头跳过（0-based，-1 = 尚未）
  var introSkippedChapter: Int
  /// WebDAV 书：对应的服务器 id（密码在钥匙串里）
  var webdavServerId: String?

  init(
    id: String = UUID().uuidString,
    title: String,
    author: String = "",
    kind: BookSourceKind = .local,
    folder: String? = nil,
    coverFile: String? = nil,
    chapters: [Chapter] = [],
    addedAt: Date = Date(),
    position: PlaybackPosition = PlaybackPosition(),
    bookmarks: [Bookmark] = [],
    skipIntro: Int = 0,
    skipOutro: Int = 0,
    speed: Double = 1.0,
    sourceId: String? = nil,
    sourceName: String? = nil,
    remoteBookId: String? = nil,
    sourceExtraJSON: String? = nil,
    introSkippedChapter: Int = -1,
    webdavServerId: String? = nil
  ) {
    self.id = id
    self.title = title
    self.author = author
    self.kind = kind
    self.folder = folder
    self.coverFile = coverFile
    self.chapters = chapters
    self.addedAt = addedAt
    self.position = position
    self.bookmarks = bookmarks
    self.skipIntro = skipIntro
    self.skipOutro = skipOutro
    self.speed = speed
    self.sourceId = sourceId
    self.sourceName = sourceName
    self.remoteBookId = remoteBookId
    self.sourceExtraJSON = sourceExtraJSON
    self.introSkippedChapter = introSkippedChapter
    self.webdavServerId = webdavServerId
  }

  private enum CodingKeys: String, CodingKey {
    case id, title, author, kind, folder, coverFile, chapters, addedAt, position
    case bookmarks, skipIntro, skipOutro, speed, sourceId, sourceName, remoteBookId, sourceExtraJSON
    case introSkippedChapter, webdavServerId
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    title = try c.decode(String.self, forKey: .title)
    author = try c.decodeIfPresent(String.self, forKey: .author) ?? ""
    kind = try c.decodeIfPresent(BookSourceKind.self, forKey: .kind) ?? .local
    folder = try c.decodeIfPresent(String.self, forKey: .folder)
    coverFile = try c.decodeIfPresent(String.self, forKey: .coverFile)
    chapters = try c.decodeIfPresent([Chapter].self, forKey: .chapters) ?? []
    addedAt = try c.decodeIfPresent(Date.self, forKey: .addedAt) ?? Date()
    position = try c.decodeIfPresent(PlaybackPosition.self, forKey: .position) ?? PlaybackPosition()
    bookmarks = try c.decodeIfPresent([Bookmark].self, forKey: .bookmarks) ?? []
    skipIntro = try c.decodeIfPresent(Int.self, forKey: .skipIntro) ?? 0
    skipOutro = try c.decodeIfPresent(Int.self, forKey: .skipOutro) ?? 0
    speed = try c.decodeIfPresent(Double.self, forKey: .speed) ?? 1.0
    sourceId = try c.decodeIfPresent(String.self, forKey: .sourceId)
    sourceName = try c.decodeIfPresent(String.self, forKey: .sourceName)
    remoteBookId = try c.decodeIfPresent(String.self, forKey: .remoteBookId)
    sourceExtraJSON = try c.decodeIfPresent(String.self, forKey: .sourceExtraJSON)
    introSkippedChapter = try c.decodeIfPresent(Int.self, forKey: .introSkippedChapter) ?? -1
    webdavServerId = try c.decodeIfPresent(String.self, forKey: .webdavServerId)
  }

  /// 已听百分比（0...1），时长未知时按已播章节数估算
  var progress: Double {
    guard !chapters.isEmpty else { return 0 }
    let total = chapters.reduce(0.0) { $0 + max($1.duration, 0) }
    let index = min(max(position.chapterIndex, 0), chapters.count - 1)
    if total <= 0 {
      return (Double(index) + 0.0) / Double(chapters.count)
    }
    let before = chapters.prefix(index).reduce(0.0) { $0 + max($1.duration, 0) }
    let current = min(max(position.seconds, 0), max(chapters[index].duration, 0))
    return min(1, max(0, (before + current) / total))
  }

  /// 总时长（秒）
  var totalDuration: Double {
    chapters.reduce(0.0) { $0 + max($1.duration, 0) }
  }

  var chapterCountText: String { "共\(chapters.count)集" }
}

/// 搜索 / 章节列表里用的轻量结果
struct SourceSearchItem: Identifiable, Hashable {
  var id: String
  var title: String
  var author: String
  var cover: String
  var intro: String
  var trackCount: Int
  var extraJSON: String?
}
