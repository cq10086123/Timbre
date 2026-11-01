import CryptoKit
import Foundation

/// 公告与更新检查 —— **与安卓 main 分支完全同源**：
/// main 写了什么公告（`announcement.txt`）和更新（`update.json`），iOS 就弹什么。
///
/// - 公告：main 分支 `announcement.txt` 纯文本，内容即弹窗正文，无解析无格式约定；
/// - 更新：main 分支 `update.json`（`versionName` / `releaseNotes` / `releaseUrl`）；
/// - 端点顺序与安卓 `AnnouncementChecker` / `UpdateChecker` 一致：
///   raw 最快反映 main 的改动，jsDelivr / fastly 兜底国内可达（分支 URL 有最长 12h 缓存）；
/// - 指纹（SHA-256）去重与安卓 `fingerprintOf` 同规则：「知道了」只关本次，
///   「不再提示」记住这条文本的指纹，下次改动文本会再弹。
enum RemoteNotices {

  // MARK: - 端点（与安卓逐字一致）

  private static let announcementEndpoints = [
    "https://raw.githubusercontent.com/cq10086123/Timbre/main/announcement.txt",
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/announcement.txt",
    "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@main/announcement.txt",
  ]

  private static let updateEndpoints = [
    "https://raw.githubusercontent.com/cq10086123/Timbre/main/update.json",
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
    "https://fastly.jsdelivr.net/gh/cq10086123/Timbre@main/update.json",
  ]

  /// 更新按钮打开的页面（update.json 的 releaseUrl 缺失时兜底）
  static let releasePageURL = "https://github.com/cq10086123/Timbre/releases/latest"

  // MARK: - 公告

  /// main 分支公告的当前内容；nil = 没有公告或所有端点都没响应（正常离线情形）
  static func fetchAnnouncement() async -> String? {
    for endpoint in announcementEndpoints {
      if let text = try? await fetchText(endpoint, maxChars: 8_000),
        let normalized = normalize(text)
      {
        return normalized
      }
    }
    return nil
  }

  // MARK: - 更新

  struct Update: Equatable {
    var versionName: String
    var releaseNotes: String?
    var releaseUrl: String?
  }

  static func fetchLatestUpdate() async -> Update? {
    for endpoint in updateEndpoints {
      if let text = try? await fetchText(endpoint, maxChars: 64_000),
        let update = parseUpdate(text)
      {
        return update
      }
    }
    return nil
  }

  /// 与安卓 `UpdateChecker.parseRelease` 同字段：versionName 必填，其余可缺
  static func parseUpdate(_ body: String) -> Update? {
    guard let data = body.data(using: .utf8),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    guard let versionName = object["versionName"] as? String else { return nil }
    return Update(
      versionName: versionName,
      releaseNotes: object["releaseNotes"] as? String,
      releaseUrl: object["releaseUrl"] as? String
    )
  }

  // MARK: - 版本比较（与安卓 isNewer 同规则：逐段数字，"1.0.9" < "1.0.10"）

  static func isNewer(current: String, latest: String) -> Bool {
    let currentParts = versionParts(current)
    let latestParts = versionParts(latest)
    for index in 0..<max(currentParts.count, latestParts.count) {
      let a = index < currentParts.count ? currentParts[index] : 0
      let b = index < latestParts.count ? latestParts[index] : 0
      if a != b { return b > a }
    }
    return false
  }

  private static func versionParts(_ version: String) -> [Int] {
    version
      .dropPrefix("v")
      .split(separator: ".")
      .map { Int($0) ?? 0 }
  }

  // MARK: - 指纹与已读状态（UserDefaults，语义与安卓 DataStore 一致）

  static func fingerprint(_ text: String) -> String {
    let digest = SHA256.hash(data: Data(text.utf8))
    return digest.map { String(format: "%02x", $0) }.joined()
  }

  private enum Keys {
    static let announcementDismissed = "update.announcement.dismissedFingerprint"
    static let updateDismissed = "update.dismissedVersion"
    static let lastCheckDay = "update.lastCheckDay"
  }

  static var dismissedAnnouncementFingerprint: String {
    get { UserDefaults.standard.string(forKey: Keys.announcementDismissed) ?? "" }
    set { UserDefaults.standard.set(newValue, forKey: Keys.announcementDismissed) }
  }

  static var dismissedUpdateVersion: String {
    get { UserDefaults.standard.string(forKey: Keys.updateDismissed) ?? "" }
    set { UserDefaults.standard.set(newValue, forKey: Keys.updateDismissed) }
  }

  /// 「每天最多查一次」的记号（安卓存 epochDay）
  static var lastCheckDay: Int {
    get { UserDefaults.standard.integer(forKey: Keys.lastCheckDay) }
    set { UserDefaults.standard.set(newValue, forKey: Keys.lastCheckDay) }
  }

  static var today: Int { Int(Date().timeIntervalSince1970 / 86_400) }

  // MARK: - 底层

  private static func fetchText(_ endpoint: String, maxChars: Int) async throws -> String {
    guard let url = URL(string: endpoint) else { throw URLError(.badURL) }
    var request = URLRequest(url: url)
    request.timeoutInterval = 10
    let (data, response) = try await URLSession.shared.data(for: request)
    guard let http = response as? HTTPURLResponse, http.statusCode == 200 else {
      throw URLError(.badServerResponse)
    }
    // 超长正文截断：维护者贴整份 changelog 也不能把弹窗撑爆（安卓同样限 8000 字符）
    guard let text = String(data: data.prefix(maxChars * 4), encoding: .utf8) else {
      throw URLError(.cannotDecodeContentData)
    }
    return String(text.prefix(maxChars))
  }

  /// 换行统一成 \n、去首尾空白；空文件 = 没有公告
  static func normalize(_ text: String) -> String? {
    let unified = text
      .replacingOccurrences(of: "\r\n", with: "\n")
      .replacingOccurrences(of: "\r", with: "\n")
    let trimmed = unified.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty ? nil : trimmed
  }
}

private extension String {
  func dropPrefix(_ prefix: String) -> String {
    hasPrefix(prefix) ? String(dropFirst(prefix.count)) : self
  }
}
