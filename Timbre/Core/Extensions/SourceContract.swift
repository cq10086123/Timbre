import Foundation

enum SourceContractError: LocalizedError {
  case notJSON(String)
  case shape(String)
  case audio(String)

  var errorDescription: String? {
    switch self {
    case .notJSON(let reason): return reason
    case .shape(let reason): return reason
    case .audio(let reason): return reason
    }
  }
}

/// 校验并归一化源脚本三个阶段的返回值，字段别名与 Android 版 SourceContract 一致
enum SourceContract {

  // MARK: - search

  static func searchItems(_ text: String) throws -> [SourceSearchItem] {
    let root = try anyObject(text)
    guard let array = root as? [Any] else {
      throw SourceContractError.shape("search 必须返回数组")
    }
    var items: [SourceSearchItem] = []
    items.reserveCapacity(array.count)
    for raw in array {
      guard let dict = raw as? [String: Any] else { continue }
      guard let id = firstString(dict, ["id"]) else { continue }
      items.append(
        SourceSearchItem(
          id: id,
          title: firstString(dict, ["bookTitle", "title"]) ?? "未命名",
          author: firstString(dict, ["bookAnchor", "author", "teller"]) ?? "",
          cover: firstString(dict, ["bookImage", "cover"]) ?? "",
          intro: firstString(dict, ["bookDesc", "intro", "description"]) ?? "",
          trackCount: firstInt(dict, ["count", "trackCount", "total"]) ?? 0,
          extraJSON: jsonText(dict)
        )
      )
    }
    if items.isEmpty {
      throw SourceContractError.shape("该源没有搜到结果")
    }
    return items
  }

  // MARK: - chapters

  static func chapters(_ text: String) throws -> [Chapter] {
    let root = try anyObject(text)
    guard let array = root as? [Any] else {
      throw SourceContractError.shape("chapters 必须返回数组")
    }
    var pairs: [(order: Int, chapter: Chapter)] = []
    for (index, raw) in array.enumerated() {
      guard let dict = raw as? [String: Any] else { continue }
      guard let id = firstString(dict, ["chapter_id", "id", "trackId"]) else { continue }
      let title = firstString(dict, ["title", "name"]) ?? "第 \(index + 1) 集"
      let duration = firstDouble(dict, ["duration", "durationSeconds"]) ?? 0
      let order = firstInt(dict, ["order", "index", "episode_num"]) ?? (index + 1)
      pairs.append(
        (
          order,
          Chapter(
            id: id,
            title: title,
            duration: max(0, duration),
            extraJSON: jsonText(dict)
          )
        )
      )
    }
    if pairs.isEmpty {
      throw SourceContractError.shape("该源没有返回这本书的章节")
    }
    return pairs.sorted { $0.order < $1.order }.map { $0.chapter }
  }

  // MARK: - audio

  static func audio(_ text: String) throws -> (url: String, headers: [String: String]) {
    let root = try anyObject(text)
    var url: String?
    var headers: [String: String] = [:]
    if let plain = root as? String {
      url = plain
    } else if let dict = root as? [String: Any] {
      url = firstString(dict, ["url", "audio_url", "playUrl", "play_url"])
      if let rawHeaders = dict["headers"] as? [String: Any] {
        for (key, value) in rawHeaders {
          if let text = stringValue(value) { headers[key] = text }
        }
      }
    }
    guard let resolved = url, !resolved.isEmpty else {
      throw SourceContractError.audio("audio 未返回音频 URL")
    }
    guard resolved.hasPrefix("http://") || resolved.hasPrefix("https://") else {
      throw SourceContractError.audio("audio 返回的 URL 不是 http(s) 链接")
    }
    return (resolved, headers)
  }

  // MARK: - 工具

  static func anyObject(_ text: String) throws -> Any {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { throw SourceContractError.notJSON("源返回了空内容") }
    guard var value = try? JSONSerialization.jsonObject(
      with: Data(trimmed.utf8),
      options: [.allowFragments]
    ) else {
      throw SourceContractError.notJSON("源返回的不是合法 JSON")
    }
    // 脚本有时会把 JSON 再包一层字符串
    if let nested = value as? String {
      if let inner = try? JSONSerialization.jsonObject(
        with: Data(nested.utf8),
        options: [.allowFragments]
      ) {
        value = inner
      }
    }
    return value
  }

  static func jsonText(_ dict: [String: Any]) -> String? {
    guard JSONSerialization.isValidJSONObject(dict),
      let data = try? JSONSerialization.data(withJSONObject: dict),
      let text = String(data: data, encoding: .utf8)
    else { return nil }
    return text
  }

  static func stringValue(_ value: Any?) -> String? {
    switch value {
    case let text as String: return text
    case let number as NSNumber: return number.stringValue
    case .none: return nil
    case .some(let other): return "\(other)"
    }
  }

  private static func firstString(_ dict: [String: Any], _ keys: [String]) -> String? {
    for key in keys {
      if let value = dict[key], let text = stringValue(value), !text.isEmpty { return text }
    }
    return nil
  }

  private static func firstInt(_ dict: [String: Any], _ keys: [String]) -> Int? {
    for key in keys {
      if let number = dict[key] as? NSNumber { return number.intValue }
      if let text = dict[key] as? String, let value = Int(text) { return value }
    }
    return nil
  }

  private static func firstDouble(_ dict: [String: Any], _ keys: [String]) -> Double? {
    for key in keys {
      if let number = dict[key] as? NSNumber { return number.doubleValue }
      if let text = dict[key] as? String, let value = Double(text) { return value }
    }
    return nil
  }
}
