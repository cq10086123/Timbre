import Foundation

/// 接口源的异步门面：把阻塞的 JS 引擎调用放到后台串行队列
final class ExtensionSourceService {
  static let shared = ExtensionSourceService()

  private let queue = DispatchQueue(label: "timbre.jdr.engine", qos: .userInitiated)

  // MARK: - 三个阶段

  func search(
    script: String,
    allowInsecure: Bool,
    keyword: String,
    page: Int = 1,
    limit: Int = 20
  ) async throws -> [SourceSearchItem] {
    DLog("search", "开始 关键词「\(keyword)」page=\(page) limit=\(limit)")
    let json = try await run(
      script: script,
      allowInsecure: allowInsecure,
      stage: "search",
      params: ["keyword": keyword, "page": page, "limit": limit]
    )
    do {
      let items = try SourceContract.searchItems(json)
      DLog("search", "完成，解析出 \(items.count) 条")
      return items
    } catch {
      DLog("search", "解析失败：\(error.localizedDescription) 原始=\(String(json.prefix(300)))")
      throw error
    }
  }

  func chapters(
    script: String,
    allowInsecure: Bool,
    bookId: String,
    extraJSON: String?,
    page: Int = 1,
    size: Int = 500
  ) async throws -> [Chapter] {
    var params = Self.extraParams(extraJSON)
    params["bookId"] = bookId
    params["page"] = page
    params["size"] = size
    let json = try await run(
      script: script,
      allowInsecure: allowInsecure,
      stage: "chapters",
      params: params
    )
    return try SourceContract.chapters(json)
  }

  func audio(
    script: String,
    allowInsecure: Bool,
    bookId: String,
    chapterId: String,
    extraJSON: String?
  ) async throws -> (url: String, headers: [String: String]) {
    var params = Self.extraParams(extraJSON)
    params["bookId"] = bookId
    params["chapterId"] = chapterId
    let json = try await run(
      script: script,
      allowInsecure: allowInsecure,
      stage: "audio",
      params: params
    )
    do {
      let resolved = try SourceContract.audio(json)
      // 记下 host、后缀和头名：播放失败时这三项就能判断是「防盗链拒绝」还是「容器不认」
      if let parsed = URL(string: resolved.url) {
        DLog(
          "audio",
          "直链 host=\(parsed.host ?? "-") 后缀=\(parsed.pathExtension) 头=\(resolved.headers.keys.sorted())"
        )
      }
      return resolved
    } catch {
      DLog("audio", "解析失败：\(error.localizedDescription) 原始=\(String(json.prefix(400)))")
      throw error
    }
  }

  // MARK: - 底层

  private func run(
    script: String,
    allowInsecure: Bool,
    stage: String,
    params: [String: Any]
  ) async throws -> String {
    let paramsJSON = Self.paramsJSON(params)
    return try await withCheckedThrowingContinuation { continuation in
      queue.async {
        do {
          let json = try JavaScriptSourceEngine.run(
            script: script,
            stage: stage,
            paramsJSON: paramsJSON,
            allowInsecure: allowInsecure
          )
          continuation.resume(returning: json)
        } catch {
          continuation.resume(throwing: error)
        }
      }
    }
  }

  static func extraParams(_ extraJSON: String?) -> [String: Any] {
    guard let extraJSON, let data = extraJSON.data(using: .utf8),
      let root = try? JSONSerialization.jsonObject(with: data),
      let dict = root as? [String: Any]
    else { return [:] }
    var params: [String: Any] = [:]
    for (key, value) in dict {
      if value is NSNull { continue }
      params[key] = value
    }
    return params
  }

  static func paramsJSON(_ params: [String: Any]) -> String {
    guard JSONSerialization.isValidJSONObject(params),
      let data = try? JSONSerialization.data(withJSONObject: params),
      let text = String(data: data, encoding: .utf8)
    else { return "{}" }
    return text
  }
}
