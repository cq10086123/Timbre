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
  /// 快进 / 快退秒数
  var seekSeconds: Int = 15
  /// 暂停后自动回退秒数
  var autoRewindSeconds: Int = 5
  /// 新书默认倍速
  var defaultSpeed: Double = 1.0
  /// 睡眠定时器到点淡出
  var sleepFadeOut: Bool = true
  /// 新书默认跳过片头秒数
  var defaultSkipIntro: Int = 0
  var defaultSkipOutro: Int = 0
  var sortOrder: BookSortOrder = .recent
  var lastPlayedBookId: String?

  init() {}

  private enum CodingKeys: String, CodingKey {
    case themeMode, useGridLayout, seekSeconds, autoRewindSeconds, defaultSpeed
    case sleepFadeOut, defaultSkipIntro, defaultSkipOutro, sortOrder, lastPlayedBookId
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    themeMode = try c.decodeIfPresent(ThemeMode.self, forKey: .themeMode) ?? .dark
    useGridLayout = try c.decodeIfPresent(Bool.self, forKey: .useGridLayout) ?? true
    seekSeconds = try c.decodeIfPresent(Int.self, forKey: .seekSeconds) ?? 15
    autoRewindSeconds = try c.decodeIfPresent(Int.self, forKey: .autoRewindSeconds) ?? 5
    defaultSpeed = try c.decodeIfPresent(Double.self, forKey: .defaultSpeed) ?? 1.0
    sleepFadeOut = try c.decodeIfPresent(Bool.self, forKey: .sleepFadeOut) ?? true
    defaultSkipIntro = try c.decodeIfPresent(Int.self, forKey: .defaultSkipIntro) ?? 0
    defaultSkipOutro = try c.decodeIfPresent(Int.self, forKey: .defaultSkipOutro) ?? 0
    sortOrder = try c.decodeIfPresent(BookSortOrder.self, forKey: .sortOrder) ?? .recent
    lastPlayedBookId = try c.decodeIfPresent(String.self, forKey: .lastPlayedBookId)
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
