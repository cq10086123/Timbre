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

  // MARK: 自动睡眠定时器（安卓 SleepTimerPreference）

  var autoSleepEnabled: Bool = false
  /// 一天内第几分钟，1320 = 22:00
  var autoSleepStartMinute: Int = 1320
  /// 360 = 06:00
  var autoSleepEndMinute: Int = 360

  init() {}

  private enum CodingKeys: String, CodingKey {
    case themeMode, useGridLayout, seekSeconds, autoRewindSeconds, defaultSpeed
    case sleepFadeOut, defaultSkipIntro, defaultSkipOutro, sortOrder, lastPlayedBookId
    case autoRefreshWifi, btSkipChapter
    case onlinePreloadEnabled, onlinePreloadTriggerSeconds, onlinePreloadChapterCount
    case onlinePreloadIntervalSeconds
    case autoSleepEnabled, autoSleepStartMinute, autoSleepEndMinute
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
    autoSleepEnabled = try c.decodeIfPresent(Bool.self, forKey: .autoSleepEnabled) ?? false
    autoSleepStartMinute =
      try c.decodeIfPresent(Int.self, forKey: .autoSleepStartMinute) ?? 1320
    autoSleepEndMinute = try c.decodeIfPresent(Int.self, forKey: .autoSleepEndMinute) ?? 360
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
