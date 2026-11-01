import Foundation

/// 一个 WebDAV 服务器配置。密码不落在这个结构里，单独存钥匙串。
struct WebDavServer: Codable, Identifiable, Hashable {
  var id: String = UUID().uuidString
  var name: String
  /// 不含结尾斜杠，例如 https://nas.example.com:5006/dav
  var baseUrl: String
  var username: String
  var trustAllCertificates: Bool = false

  /// 地址规范化：去掉首尾空白与结尾斜杠，避免拼出 // 路径
  var normalizedBase: String {
    var text = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
    while text.hasSuffix("/") { text.removeLast() }
    return text
  }
}

/// PROPFIND 返回的一个条目
struct WebDavResource: Identifiable, Hashable {
  /// 完整 URL，仍带百分号编码
  var url: String
  /// 可读名称，已解码
  var name: String
  var isDirectory: Bool
  var contentLength: Int64
  var lastModified: TimeInterval

  var id: String { url }
}

enum WebDavError: LocalizedError {
  case badURL
  case auth
  case digestUnsupported
  case status(Int)
  case notFound
  case parse
  case transport(String)

  var errorDescription: String? {
    switch self {
    case .badURL:
      return "服务器地址不合法，需要以 http:// 或 https:// 开头"
    case .auth:
      return "认证失败，请检查用户名和密码"
    case .digestUnsupported:
      return "该服务器要求 Digest 认证，当前版本暂不支持，请改用 Basic 认证或应用密码"
    case .status(let code):
      return "服务器返回 HTTP \(code)"
    case .notFound:
      return "服务器上没有这个目录"
    case .parse:
      return "服务器返回的目录列表无法解析"
    case .transport(let text):
      return text
    }
  }
}

enum WebDavAudio {
  static let fileExtensions: Set<String> = [
    "mp3", "m4a", "m4b", "m4r", "aac", "flac", "wav", "ogg", "opus", "wma", "ape", "aiff", "aif",
  ]

  static func isAudio(_ name: String) -> Bool {
    fileExtensions.contains((name as NSString).pathExtension.lowercased())
  }

  /// 按自然顺序排，让「第 2 集」排在「第 10 集」前面
  static func sorted(_ items: [WebDavResource]) -> [WebDavResource] {
    items.sorted { NaturalSort.compare($0.name, $1.name) == .orderedAscending }
  }
}
