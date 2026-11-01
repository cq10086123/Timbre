import Foundation

/// 网络封面搜索 —— 对齐安卓 `features/cover` 的做法：
/// 先请求 DuckDuckGo 首页拿到 `vqd` 令牌，再带着它打 `/i.js` 图片搜索接口。
enum CoverSearchClient {
  struct Item: Identifiable, Hashable {
    var id: String { image }
    /// 原图
    var image: String
    /// 缩略图（列表里用，省流量）
    var thumbnail: String
    var title: String
  }

  enum SearchError: LocalizedError {
    case token
    case http(Int)
    case shape

    var errorDescription: String? {
      switch self {
      case .token: return "拿不到搜索令牌，稍后再试"
      case .http(let code): return "图片搜索失败（HTTP \(code)）"
      case .shape: return "图片搜索返回的数据看不懂"
      }
    }
  }

  /// 安卓用的是一个固定 UA，这里保持一致，否则 DDG 会返回 HTML
  private static let userAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

  static func search(query: String, page: Int = 1) async throws -> [Item] {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return [] }
    let token = try await fetchToken(query: trimmed)
    return try await fetchResults(query: trimmed, token: token, page: page)
  }

  // MARK: - vqd 令牌

  private static func fetchToken(query: String) async throws -> String {
    var components = URLComponents(string: "https://duckduckgo.com/")!
    components.queryItems = [
      URLQueryItem(name: "q", value: query),
      URLQueryItem(name: "iax", value: "images"),
      URLQueryItem(name: "ia", value: "images"),
    ]
    var request = URLRequest(url: components.url!)
    request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
    request.timeoutInterval = 15

    let (data, response) = try await URLSession.shared.data(for: request)
    if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
      throw SearchError.http(http.statusCode)
    }
    let html = String(data: data, encoding: .utf8) ?? ""
    guard let value = firstMatch(#"vqd=([\d-]+)"#, in: html) else { throw SearchError.token }
    return value
  }

  // MARK: - 结果

  private static func fetchResults(query: String, token: String, page: Int) async throws -> [Item] {
    var components = URLComponents(string: "https://duckduckgo.com/i.js")!
    components.queryItems = [
      URLQueryItem(name: "l", value: "wt-wt"),
      URLQueryItem(name: "o", value: "json"),
      URLQueryItem(name: "q", value: query),
      URLQueryItem(name: "vqd", value: token),
      URLQueryItem(name: "f", value: ",,,"),
      URLQueryItem(name: "p", value: "\(max(1, page))"),
    ]
    var request = URLRequest(url: components.url!)
    request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
    request.setValue("https://duckduckgo.com/", forHTTPHeaderField: "Referer")
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    request.timeoutInterval = 20

    let (data, response) = try await URLSession.shared.data(for: request)
    if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
      throw SearchError.http(http.statusCode)
    }
    guard
      let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
      let results = root["results"] as? [[String: Any]]
    else { throw SearchError.shape }

    return results.compactMap { dict in
      guard let image = dict["image"] as? String, !image.isEmpty else { return nil }
      let thumb = (dict["thumbnail"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? image
      return Item(image: image, thumbnail: thumb, title: dict["title"] as? String ?? "")
    }
  }

  private static func firstMatch(_ pattern: String, in text: String) -> String? {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    guard let match = regex.firstMatch(in: text, range: range), match.numberOfRanges > 1,
      let captured = Range(match.range(at: 1), in: text)
    else { return nil }
    return String(text[captured])
  }

  // MARK: - 下载

  static func download(_ urlString: String, referer: String? = nil) async -> Data? {
    guard let url = URL(string: urlString) else { return nil }
    var request = URLRequest(url: url)
    request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
    // 图站大多拦外链，带上搜索页当 referer 通过率高很多
    request.setValue(referer ?? "https://duckduckgo.com/", forHTTPHeaderField: "Referer")
    request.timeoutInterval = 25
    guard let (data, _) = try? await URLSession.shared.data(for: request), !data.isEmpty else {
      return nil
    }
    return data
  }
}
