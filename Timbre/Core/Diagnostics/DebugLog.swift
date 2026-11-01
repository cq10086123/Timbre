import Foundation
import UIKit

/// 环形诊断日志：只保留最近若干条，用于「在设置里导出 txt 发我」这条排查路径。
/// 同时写 NSLog，接数据线时也能用控制台工具实时看到。
final class DebugLog {
  static let shared = DebugLog()

  struct Entry {
    var time: Date
    var tag: String
    var message: String
  }

  private let lock = NSLock()
  private var entries: [Entry] = []
  private let limit = 1500

  private static let clock: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "HH:mm:ss.SSS"
    return formatter
  }()

  private static let stamp: DateFormatter = {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "yyyy-MM-dd HH:mm:ss"
    return formatter
  }()

  func add(_ tag: String, _ message: String) {
    let entry = Entry(time: Date(), tag: tag, message: message)
    lock.lock()
    entries.append(entry)
    if entries.count > limit {
      entries.removeFirst(entries.count - limit)
    }
    lock.unlock()
    // 系统日志：接上电脑用 idevicesyslog / Console 可以实时看
    NSLog("[Timbre][%@] %@", tag, message)
  }

  func text() -> String {
    lock.lock()
    let snapshot = entries
    lock.unlock()

    var lines: [String] = []
    lines.append("Timbre 诊断日志")
    lines.append("生成时间：\(Self.stamp.string(from: Date()))")
    lines.append("条目数：\(snapshot.count)")
    lines.append("版本：\(Self.appVersion)")
    lines.append("设备：\(Self.deviceInfo)")
    lines.append("")
    for entry in snapshot {
      lines.append("\(Self.clock.string(from: entry.time)) [\(entry.tag)] \(entry.message)")
    }
    return lines.joined(separator: "\n")
  }

  /// 机型 + 系统版本：有些播放问题是特定机型/系统才出的，导出时一起带上省一轮来回
  static var deviceInfo: String {
    let device = UIDevice.current
    return "\(device.model) · \(device.systemName) \(device.systemVersion)"
  }

  func clear() {
    lock.lock()
    entries.removeAll()
    lock.unlock()
  }

  var count: Int {
    lock.lock()
    defer { lock.unlock() }
    return entries.count
  }

  static var appVersion: String {
    let info = Bundle.main.infoDictionary
    let version = info?["CFBundleShortVersionString"] as? String ?? "?"
    let build = info?["CFBundleVersion"] as? String ?? "?"
    let commit = info?["TimbreCommit"] as? String ?? "dev"
    let date = info?["TimbreBuildDate"] as? String ?? "dev"
    return "\(version) (\(build)) · \(commit) · \(date)"
  }
}

/// 打点简写：DLog("search", "开始 关键词=xxx")
func DLog(_ tag: String, _ message: @autoclosure () -> String) {
  DebugLog.shared.add(tag, message())
}
