import SwiftUI
import UIKit

extension UIColor {
  convenience init(hex: UInt32, alpha: CGFloat = 1) {
    let red = CGFloat((hex & 0xFF0000) >> 16) / 255
    let green = CGFloat((hex & 0x00FF00) >> 8) / 255
    let blue = CGFloat(hex & 0x0000FF) / 255
    self.init(red: red, green: green, blue: blue, alpha: alpha)
  }
}

extension Color {
  init(hex: UInt32, alpha: Double = 1) {
    self.init(UIColor(hex: hex, alpha: CGFloat(alpha)))
  }

  /// 浅色 / 深色两套主题色
  static func adaptive(light: UInt32, dark: UInt32) -> Color {
    Color(
      UIColor { traits in
        traits.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light)
      }
    )
  }
}

/// 与安卓版一致的深蓝底 + 浅蓝强调色
enum AppColor {
  static let background = Color.adaptive(light: 0xF4F6FB, dark: 0x12151F)
  static let surface = Color.adaptive(light: 0xFFFFFF, dark: 0x1B1F2E)
  static let surfaceHigh = Color.adaptive(light: 0xEDF1F9, dark: 0x232838)
  static let accent = Color.adaptive(light: 0x2F5DA8, dark: 0xA8C7FA)
  static let accentSoft = Color.adaptive(light: 0xDCE7FA, dark: 0x2C3550)
  static let textPrimary = Color.adaptive(light: 0x12151F, dark: 0xE7EAF3)
  static let textSecondary = Color.adaptive(light: 0x5C6479, dark: 0x9AA4BF)
  static let divider = Color.adaptive(light: 0xE2E7F0, dark: 0x2A3044)
  static let danger = Color.adaptive(light: 0xC0392B, dark: 0xFF8A80)
}

extension ThemeMode {
  var colorScheme: ColorScheme? {
    switch self {
    case .system: return nil
    case .light: return .light
    case .dark: return .dark
    }
  }
}

enum Format {
  /// 00:00 / 1:02:03
  static func time(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds >= 0 else { return "00:00" }
    let total = Int(seconds.rounded(.down))
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    if hours > 0 {
      return String(format: "%d:%02d:%02d", hours, minutes, secs)
    }
    return String(format: "%02d:%02d", minutes, secs)
  }

  /// -12:05 形式
  static func remaining(_ seconds: Double) -> String {
    "-" + time(max(0, seconds))
  }

  /// 3 小时 21 分
  static func humanDuration(_ seconds: Double) -> String {
    guard seconds.isFinite, seconds > 0 else { return "未知时长" }
    let total = Int(seconds)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    if hours > 0 { return "\(hours) 小时 \(minutes) 分" }
    if minutes > 0 { return "\(minutes) 分" }
    return "\(total) 秒"
  }

  static func percent(_ value: Double) -> String {
    "\(Int((min(1, max(0, value)) * 100).rounded()))%"
  }

  static func speed(_ value: Double) -> String {
    let text = String(format: "%.2f", value)
    var trimmed = text
    while trimmed.hasSuffix("0") { trimmed.removeLast() }
    if trimmed.hasSuffix(".") { trimmed.removeLast() }
    return trimmed + "x"
  }
}
