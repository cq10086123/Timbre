import Foundation

/// 一本书的来源：本地导入 / 接口源（.jdr）/ WebDAV 远程目录
enum BookSourceKind: String, Codable {
  case local
  case jdr
  case webdav
}

/// 接口源给的「章内分段」原始数据（毫秒），对齐安卓 `core.data.MarkData`
struct MarkData: Codable, Hashable {
  var startMs: Int
  var name: String

  init(startMs: Int, name: String) {
    self.startMs = startMs
    self.name = name
  }
}

/// 解析出来的章节内分段，对齐安卓 `core.data.ChapterMark`
struct ChapterMark: Hashable, Identifiable {
  var name: String?
  /// 章内起始秒
  var start: Double
  /// 章内结束秒（不含）
  var end: Double

  var id: String { "\(start)-\(end)" }
  var duration: Double { max(0, end - start) }

  func contains(_ seconds: Double) -> Bool { seconds >= start && seconds <= end }
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
  /// 接口源给的章内分段（毫秒）。为空 = 整章一个分段
  var markData: [MarkData]

  init(
    id: String = UUID().uuidString,
    title: String,
    localFile: String? = nil,
    remoteURL: String? = nil,
    duration: Double = 0,
    extraJSON: String? = nil,
    markData: [MarkData] = []
  ) {
    self.id = id
    self.title = title
    self.localFile = localFile
    self.remoteURL = remoteURL
    self.duration = duration
    self.extraJSON = extraJSON
    self.markData = markData
  }

  private enum CodingKeys: String, CodingKey {
    case id, title, localFile, remoteURL, duration, extraJSON, markData
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    title = try c.decode(String.self, forKey: .title)
    localFile = try c.decodeIfPresent(String.self, forKey: .localFile)
    remoteURL = try c.decodeIfPresent(String.self, forKey: .remoteURL)
    duration = try c.decodeIfPresent(Double.self, forKey: .duration) ?? 0
    extraJSON = try c.decodeIfPresent(String.self, forKey: .extraJSON)
    markData = try c.decodeIfPresent([MarkData].self, forKey: .markData) ?? []
  }

  /// 章节内分段 —— 安卓 `Chapter.parseMarkData()` 的逐行翻译。
  ///
  /// 播放的最小单位是分段而不是章节：一个章里可能塞着好几个小节
  /// （接口源里 `markData` 的每一项），跳转、续播、进度都按分段算。
  var chapterMarks: [ChapterMark] {
    // 没有分段数据：整章一个分段，名字用章节名
    if markData.isEmpty {
      return [ChapterMark(name: title, start: 0, end: max(0, duration))]
    }

    var durationMs = Int((duration * 1000).rounded())
    if durationMs <= 0 {
      // 时长还没探明：拿最后一个分段的起点往后兜一分钟，别让分段全挤在 0 秒
      durationMs = max(1, (markData.map(\.startMs).max() ?? 0) + 60_000)
    }

    let positions = Set(markData.map(\.startMs))
    var seen = Set<Int>()
    let sorted = markData
      .filter { !positions.contains($0.startMs - 1) && $0.startMs >= 0 }
      .filter { seen.insert($0.startMs).inserted }
      .sorted { $0.startMs < $1.startMs }
    let maxEndMs = max(durationMs - 1, 1)

    if sorted.isEmpty {
      return [ChapterMark(name: title, start: 0, end: Double(maxEndMs) / 1000)]
    }

    var raw: [(name: String?, startMs: Int, endMs: Int)] = []
    for (index, mark) in sorted.enumerated() {
      let previous = raw.last
      let next = index + 1 < sorted.count ? sorted[index + 1] : nil
      let endMs: Int
      if let next, next.startMs <= durationMs - 2 {
        endMs = next.startMs - 1
      } else {
        endMs = maxEndMs
      }
      if previous == nil {
        if 0 < endMs { raw.append((name: mark.name, startMs: 0, endMs: endMs)) }
      } else if let previous, previous.endMs + 1 < durationMs, previous.endMs + 1 < endMs {
        raw.append((name: mark.name, startMs: previous.endMs + 1, endMs: endMs))
      }
    }

    if raw.isEmpty {
      return [ChapterMark(name: title, start: 0, end: Double(maxEndMs) / 1000)]
    }
    return raw.map {
      ChapterMark(
        name: $0.name,
        start: Double($0.startMs) / 1000,
        end: Double($0.endMs) / 1000
      )
    }
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
  /// 音量提升，单位 dB，0 = 关闭（对齐安卓 VolumeGain.MAX_GAIN = 9 dB）
  var loudnessGain: Double
  /// 跳过空白部分（对齐安卓 ExoPlayer 的 skipSilenceEnabled）
  var skipSilence: Bool
  /// 接口源
  var sourceId: String?
  var sourceName: String?
  var remoteBookId: String?
  var sourceExtraJSON: String?
  /// 已经为哪一章执行过片头跳过（0-based，-1 = 尚未）
  var introSkippedChapter: Int
  /// WebDAV 书：对应的服务器 id（密码在钥匙串里）
  var webdavServerId: String?
  /// 最后一次播放的时间（安卓 `content.lastPlayedAt`），书架「进行中 / 已完成」按它倒序
  var lastPlayedAt: Date?
  /// 是否已经读过音频文件里的内嵌章节。读不到也算读过，别每次启动重扫一遍
  var marksScanned: Bool
  /// 在线书恢复备份时记下的目标章节 id。章节还没拉齐时无法换算成章节数，
  /// 先记在这里，打开播放页拉齐章节后再解析成 position.chapterIndex
  var pendingChapterId: String?

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
    loudnessGain: Double = 0,
    skipSilence: Bool = false,
    sourceId: String? = nil,
    sourceName: String? = nil,
    remoteBookId: String? = nil,
    sourceExtraJSON: String? = nil,
    introSkippedChapter: Int = -1,
    webdavServerId: String? = nil,
    lastPlayedAt: Date? = nil,
    marksScanned: Bool = false,
    pendingChapterId: String? = nil
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
    self.loudnessGain = loudnessGain
    self.skipSilence = skipSilence
    self.sourceId = sourceId
    self.sourceName = sourceName
    self.remoteBookId = remoteBookId
    self.sourceExtraJSON = sourceExtraJSON
    self.introSkippedChapter = introSkippedChapter
    self.webdavServerId = webdavServerId
    self.lastPlayedAt = lastPlayedAt
    self.marksScanned = marksScanned
    self.pendingChapterId = pendingChapterId
  }

  private enum CodingKeys: String, CodingKey {
    case id, title, author, kind, folder, coverFile, chapters, addedAt, position
    case bookmarks, skipIntro, skipOutro, speed, sourceId, sourceName, remoteBookId, sourceExtraJSON
    case introSkippedChapter, webdavServerId, lastPlayedAt, marksScanned
    case loudnessGain, skipSilence, pendingChapterId
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
    loudnessGain = try c.decodeIfPresent(Double.self, forKey: .loudnessGain) ?? 0
    skipSilence = try c.decodeIfPresent(Bool.self, forKey: .skipSilence) ?? false
    sourceId = try c.decodeIfPresent(String.self, forKey: .sourceId)
    sourceName = try c.decodeIfPresent(String.self, forKey: .sourceName)
    remoteBookId = try c.decodeIfPresent(String.self, forKey: .remoteBookId)
    sourceExtraJSON = try c.decodeIfPresent(String.self, forKey: .sourceExtraJSON)
    introSkippedChapter = try c.decodeIfPresent(Int.self, forKey: .introSkippedChapter) ?? -1
    webdavServerId = try c.decodeIfPresent(String.self, forKey: .webdavServerId)
    lastPlayedAt = try c.decodeIfPresent(Date.self, forKey: .lastPlayedAt)
    marksScanned = try c.decodeIfPresent(Bool.self, forKey: .marksScanned) ?? false
    pendingChapterId = try c.decodeIfPresent(String.self, forKey: .pendingChapterId)
  }

  /// 在线书章节还没就绪：列表是空的，或者恢复备份记下的目标章节不在列表里。
  /// 打开播放页前要先拉齐章节（对齐安卓「恢复只记账、打开时再拉」）。
  var needsChapterFetch: Bool {
    guard kind == .jdr else { return false }
    if chapters.isEmpty { return true }
    guard let pending = pendingChapterId, !pending.isEmpty else { return false }
    return !chapters.contains { $0.id == pending }
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

  /// 播放单元总数 —— 一章可能被切成多个分段，安卓章节抽屉就是按这个数显示的
  var playbackUnitCount: Int {
    // 绝大多数书没有分段数据，单元数就等于章节数；
    // 播放页每 0.5 秒会读一次这里，别为它把每章的 mark 数组都造一遍
    if chapters.allSatisfy({ $0.markData.isEmpty }) { return chapters.count }
    return chapters.reduce(0) { $0 + $1.chapterMarks.count }
  }

  var unitCountText: String { "共\(playbackUnitCount)集" }

  /// 章内某个秒数落在第几个分段上（对齐安卓 `Chapter.markForPosition`）
  func markIndex(chapterIndex index: Int, seconds: Double) -> Int {
    guard chapters.indices.contains(index) else { return 0 }
    let marks = chapters[index].chapterMarks
    guard !marks.isEmpty else { return 0 }
    if let hit = marks.firstIndex(where: { $0.contains(seconds) }) { return hit }
    // 落在分段之间的缝隙里：取起点不超过当前时间的最后一个
    let fallback = marks.lastIndex { $0.start <= seconds }
    return fallback ?? 0
  }
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
