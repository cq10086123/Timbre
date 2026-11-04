import Foundation

enum ThemeMode: String, Codable, CaseIterable, Identifiable {
  case system
  case light
  case dark

  var id: String { rawValue }

  var title: String {
    switch self {
    case .system: return "跟随系统"
    case .light: return "浅色"
    case .dark: return "深色"
    }
  }
}

enum BookSortOrder: String, Codable, CaseIterable, Identifiable {
  case recent
  case title
  case added
  case author

  var id: String { rawValue }

  var title: String {
    switch self {
    case .recent: return "最近播放"
    case .title: return "书名"
    case .added: return "加入时间"
    case .author: return "作者"
    }
  }
}

/// WebDAV 章节预取策略 —— 对齐安卓 `WebDavCacheSettings.PrefetchMode`
enum WebDavPrefetchMode: String, Codable, CaseIterable, Identifiable {
  case disabled
  case chapters5
  case chapters20
  case fillBook

  var id: String { rawValue }

  var title: String {
    switch self {
    case .disabled: return "关闭"
    case .chapters5: return "5 章"
    case .chapters20: return "20 章"
    case .fillBook: return "整本填满"
    }
  }

  /// 往后预取多少章；`fillBook` 只受缓存上限约束
  var chapterLimit: Int {
    switch self {
    case .disabled: return 0
    case .chapters5: return 5
    case .chapters20: return 20
    case .fillBook: return Int.max
    }
  }
}

struct AppSettings: Codable, Equatable {
  var themeMode: ThemeMode = .dark
  var useGridLayout: Bool = true
  /// 快进 / 快退秒数（安卓 SeekTimeRow：3–60，默认 20）
  var seekSeconds: Int = 20
  /// 暂停后自动回退秒数（安卓 AutoRewindRow：0–20，默认 2）
  var autoRewindSeconds: Int = 2
  /// 新书默认倍速
  var defaultSpeed: Double = 1.0
  /// 睡眠定时器到点淡出
  var sleepFadeOut: Bool = true
  /// 新书默认跳过片头秒数
  var defaultSkipIntro: Int = 0
  var defaultSkipOutro: Int = 0
  var sortOrder: BookSortOrder = .recent
  var lastPlayedBookId: String?

  // MARK: 书架（安卓 settings.library.auto_refresh_wifi）

  /// 仅在 Wi-Fi 下自动刷新书架
  var autoRefreshWifi: Bool = true

  // MARK: 播放器设置（安卓 OnlinePreloadSettings + bt_skip_chapter）

  /// 蓝牙 / 锁屏上一集·下一集切章（关闭则为快退/快进）
  var btSkipChapter: Bool = true
  /// 播放后预加载
  var onlinePreloadEnabled: Bool = true
  /// 预加载触发时间（5–300，默认 30）
  var onlinePreloadTriggerSeconds: Int = 30
  /// 预加载章节数（1–10，默认 2）
  var onlinePreloadChapterCount: Int = 2
  /// 章节预加载间隔（1–600，默认 50）
  var onlinePreloadIntervalSeconds: Int = 50
  /// 预加载时顺带把整集音频存到本地（写入手动缓存同一个目录）
  var onlinePreloadCacheEnabled: Bool = true
  /// 只缓存不短于该秒数的章节（默认 600 = 10 分钟）；更短的预告、片头不占空间
  var onlinePreloadCacheMinSeconds: Int = 600
  /// 预加载缓存占用的上限，0 = 不限制
  var onlinePreloadCacheMaxBytes: Int64 = 2 * 1024 * 1024 * 1024
  /// 只在 Wi-Fi 下下载预加载缓存（移动网络仍照旧只解析直链）
  var onlinePreloadCacheWifiOnly: Bool = true

  // MARK: 自动睡眠定时器（安卓 SleepTimerPreference）

  var autoSleepEnabled: Bool = false
  /// 一天内第几分钟，1320 = 22:00
  var autoSleepStartMinute: Int = 1320
  /// 360 = 06:00
  var autoSleepEndMinute: Int = 360

  // MARK: WebDAV 缓存（安卓 WebDavCacheSettings）

  /// 缓存上限，0 = 关闭缓存
  var webdavCacheMaxBytes: Int64 = 1024 * 1024 * 1024
  /// 预取未听章节的策略
  var webdavPrefetchMode: WebDavPrefetchMode = .fillBook
  /// 允许在移动网络下预取（在线播放本身的缓存不受这个开关限制）
  var webdavPrefetchOnMetered: Bool = false

  // MARK: WebDAV 导入（安卓 settings.playback.import_parallelism）

  /// 导入并发度：导入后同时解析章节时长的数量（1/2/3）。
  /// 调大导入更快，但边听边导时切章响应会变慢。
  var webdavImportParallelism: Int = 1

  init() {}

  private enum CodingKeys: String, CodingKey {
    case themeMode, useGridLayout, seekSeconds, autoRewindSeconds, defaultSpeed
    case sleepFadeOut, defaultSkipIntro, defaultSkipOutro, sortOrder, lastPlayedBookId
    case autoRefreshWifi, btSkipChapter
    case onlinePreloadEnabled, onlinePreloadTriggerSeconds, onlinePreloadChapterCount
    case onlinePreloadIntervalSeconds
    case onlinePreloadCacheEnabled, onlinePreloadCacheMinSeconds
    case onlinePreloadCacheMaxBytes, onlinePreloadCacheWifiOnly
    case autoSleepEnabled, autoSleepStartMinute, autoSleepEndMinute
    case webdavCacheMaxBytes, webdavPrefetchMode, webdavPrefetchOnMetered
    case webdavImportParallelism
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    themeMode = try c.decodeIfPresent(ThemeMode.self, forKey: .themeMode) ?? .dark
    useGridLayout = try c.decodeIfPresent(Bool.self, forKey: .useGridLayout) ?? true
    seekSeconds = try c.decodeIfPresent(Int.self, forKey: .seekSeconds) ?? 20
    autoRewindSeconds = try c.decodeIfPresent(Int.self, forKey: .autoRewindSeconds) ?? 2
    defaultSpeed = try c.decodeIfPresent(Double.self, forKey: .defaultSpeed) ?? 1.0
    sleepFadeOut = try c.decodeIfPresent(Bool.self, forKey: .sleepFadeOut) ?? true
    defaultSkipIntro = try c.decodeIfPresent(Int.self, forKey: .defaultSkipIntro) ?? 0
    defaultSkipOutro = try c.decodeIfPresent(Int.self, forKey: .defaultSkipOutro) ?? 0
    sortOrder = try c.decodeIfPresent(BookSortOrder.self, forKey: .sortOrder) ?? .recent
    lastPlayedBookId = try c.decodeIfPresent(String.self, forKey: .lastPlayedBookId)
    autoRefreshWifi = try c.decodeIfPresent(Bool.self, forKey: .autoRefreshWifi) ?? true
    btSkipChapter = try c.decodeIfPresent(Bool.self, forKey: .btSkipChapter) ?? true
    onlinePreloadEnabled =
      try c.decodeIfPresent(Bool.self, forKey: .onlinePreloadEnabled) ?? true
    onlinePreloadTriggerSeconds =
      try c.decodeIfPresent(Int.self, forKey: .onlinePreloadTriggerSeconds) ?? 30
    onlinePreloadChapterCount =
      try c.decodeIfPresent(Int.self, forKey: .onlinePreloadChapterCount) ?? 2
    onlinePreloadIntervalSeconds =
      try c.decodeIfPresent(Int.self, forKey: .onlinePreloadIntervalSeconds) ?? 50
    onlinePreloadCacheEnabled =
      try c.decodeIfPresent(Bool.self, forKey: .onlinePreloadCacheEnabled) ?? true
    onlinePreloadCacheMinSeconds =
      try c.decodeIfPresent(Int.self, forKey: .onlinePreloadCacheMinSeconds) ?? 600
    onlinePreloadCacheMaxBytes =
      try c.decodeIfPresent(Int64.self, forKey: .onlinePreloadCacheMaxBytes)
      ?? 2 * 1024 * 1024 * 1024
    onlinePreloadCacheWifiOnly =
      try c.decodeIfPresent(Bool.self, forKey: .onlinePreloadCacheWifiOnly) ?? true
    autoSleepEnabled = try c.decodeIfPresent(Bool.self, forKey: .autoSleepEnabled) ?? false
    autoSleepStartMinute =
      try c.decodeIfPresent(Int.self, forKey: .autoSleepStartMinute) ?? 1320
    autoSleepEndMinute = try c.decodeIfPresent(Int.self, forKey: .autoSleepEndMinute) ?? 360
    webdavCacheMaxBytes =
      try c.decodeIfPresent(Int64.self, forKey: .webdavCacheMaxBytes) ?? 1024 * 1024 * 1024
    webdavPrefetchMode =
      try c.decodeIfPresent(WebDavPrefetchMode.self, forKey: .webdavPrefetchMode) ?? .fillBook
    webdavPrefetchOnMetered =
      try c.decodeIfPresent(Bool.self, forKey: .webdavPrefetchOnMetered) ?? false
    webdavImportParallelism =
      try c.decodeIfPresent(Int.self, forKey: .webdavImportParallelism) ?? 1
  }
}

/// 自动睡眠定时器的时间窗口判断（对齐安卓 isTimeInRange：支持跨零点）
enum AutoSleepWindow {
  static func contains(
    now: Date = Date(),
    startMinute: Int,
    endMinute: Int,
    calendar: Calendar = .current
  ) -> Bool {
    let components = calendar.dateComponents([.hour, .minute], from: now)
    let minute = (components.hour ?? 0) * 60 + (components.minute ?? 0)
    if startMinute == endMinute { return false }
    if startMinute < endMinute {
      return minute >= startMinute && minute < endMinute
    }
    // 跨零点，例如 22:00 → 06:00
    return minute >= startMinute || minute < endMinute
  }

  /// 1320 → "22:00"
  static func label(minute: Int) -> String {
    let clamped = ((minute % 1440) + 1440) % 1440
    return String(format: "%02d:%02d", clamped / 60, clamped % 60)
  }
}

/// 定时器 / 时间的可读文案，对齐安卓 plurals.duration_seconds
enum DurationText {
  static func seconds(_ value: Int) -> String { "\(value) 秒" }

  /// 播放 30 秒后
  static func afterPlayback(_ value: Int) -> String { "播放 \(value) 秒后" }

  /// 最多 2
  static func atMost(_ value: Int) -> String { "最多 \(value)" }

  /// 每章间隔 50 秒
  static func interval(_ value: Int) -> String { "每章间隔 \(value) 秒" }

  /// 不短于 10 分钟（预加载缓存的最短时长门槛）
  static func atLeast(_ seconds: Int) -> String {
    if seconds <= 0 { return "不限" }
    if seconds % 60 == 0 { return "不短于 \(seconds / 60) 分钟" }
    if seconds > 60 { return "不短于 \(seconds / 60) 分 \(seconds % 60) 秒" }
    return "不短于 \(seconds) 秒"
  }
}

/// 沙盒内固定路径
enum AppPaths {
  static var documents: URL {
    FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
  }

  static var booksDir: URL { ensure(documents.appendingPathComponent("Books", isDirectory: true)) }
  static var coversDir: URL { ensure(documents.appendingPathComponent("Covers", isDirectory: true)) }
  static var extensionsDir: URL { ensure(documents.appendingPathComponent("Extensions", isDirectory: true)) }

  static var libraryFile: URL { documents.appendingPathComponent("library.json") }
  static var settingsFile: URL { documents.appendingPathComponent("settings.json") }
  static var extensionsFile: URL { documents.appendingPathComponent("extensions.json") }

  static func url(for relative: String) -> URL {
    documents.appendingPathComponent(relative)
  }

  @discardableResult
  static func ensure(_ url: URL) -> URL {
    if !FileManager.default.fileExists(atPath: url.path) {
      try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
    return url
  }
}

enum JSONCoding {
  static func encoder() -> JSONEncoder {
    let e = JSONEncoder()
    e.dateEncodingStrategy = .iso8601
    e.outputFormatting = [.prettyPrinted, .sortedKeys]
    return e
  }

  static func decoder() -> JSONDecoder {
    let d = JSONDecoder()
    d.dateDecodingStrategy = .iso8601
    return d
  }
}
