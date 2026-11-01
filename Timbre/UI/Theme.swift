import SwiftUI
import UIKit

// MARK: - 颜色

extension Color {
  init(hex: UInt32) {
    self.init(
      .sRGB,
      red: Double((hex >> 16) & 0xFF) / 255,
      green: Double((hex >> 8) & 0xFF) / 255,
      blue: Double(hex & 0xFF) / 255,
      opacity: 1
    )
  }

  /// 明暗两套色值，跟随系统/用户选择自动切换
  static func adaptive(light: UInt32, dark: UInt32) -> Color {
    Color(
      UIColor { traits in
        let hex = traits.userInterfaceStyle == .dark ? dark : light
        return UIColor(
          red: CGFloat((hex >> 16) & 0xFF) / 255,
          green: CGFloat((hex >> 8) & 0xFF) / 255,
          blue: CGFloat(hex & 0xFF) / 255,
          alpha: 1
        )
      }
    )
  }
}

/// Material 3 Expressive 色板（种子色 #003B7F「Timbre 蓝」）
/// 数值取自安卓版真机截图的像素采样，role 名与 Material3 一一对应。
enum AppColor {
  // 基础
  static let background = Color.adaptive(light: 0xF8F9FD, dark: 0x0B0F11)
  static let surface = background
  static let surfaceContainerLowest = Color.adaptive(light: 0xFFFFFF, dark: 0x060909)
  static let surfaceContainerLow = Color.adaptive(light: 0xF1F4F9, dark: 0x121618)
  static let surfaceContainer = Color.adaptive(light: 0xECEEF3, dark: 0x161A1C)
  static let surfaceContainerHigh = Color.adaptive(light: 0xE4E9EF, dark: 0x1F2426)
  static let surfaceContainerHighest = Color.adaptive(light: 0xDDE3EB, dark: 0x272D2F)
  /// 滑轨等「未激活」底色
  static let surfaceVariant = Color.adaptive(light: 0xDDE3EB, dark: 0x272D2F)

  // 文字
  static let onSurface = Color.adaptive(light: 0x2D3339, dark: 0xE0E6EE)
  static let onSurfaceVariant = Color.adaptive(light: 0x596066, dark: 0xA5ACB3)

  // 强调
  static let primary = Color.adaptive(light: 0x346384, dark: 0xA8CBE8)
  static let onPrimary = Color.adaptive(light: 0xFFFFFF, dark: 0x0F2E44)
  static let primaryContainer = Color.adaptive(light: 0xA6D4FA, dark: 0x20435B)
  static let onPrimaryContainer = Color.adaptive(light: 0x06131F, dark: 0xC9E6FF)
  static let secondaryContainer = Color.adaptive(light: 0xCBE0EB, dark: 0x2A3A42)
  static let onSecondaryContainer = Color.adaptive(light: 0x0F2833, dark: 0xCBE0EB)
  /// 「需要用户注意但不报错」的第三色（缓存等流量确认）
  static let tertiary = Color.adaptive(light: 0x3F6374, dark: 0xA5CCDE)

  // 描边 / 语义
  static let outline = Color.adaptive(light: 0x757A7F, dark: 0x757A7F)
  static let outlineVariant = Color.adaptive(light: 0xC3C7CE, dark: 0x42494F)
  static let error = Color.adaptive(light: 0xBA1A1A, dark: 0xFFB4AB)
  static let scrim = Color.black.opacity(0.32)

  // 兼容旧命名（其它界面仍在用）
  static let surfaceHigh = surfaceContainerHigh
  static let accent = primary
  static let accentSoft = primaryContainer
  static let textPrimary = onSurface
  static let textSecondary = onSurfaceVariant
  static let divider = outlineVariant
  static let danger = error
}

// MARK: - 字体（Material3 type scale）

enum AppFont {
  static func headlineSmall() -> Font { .system(size: 24, weight: .regular) }
  static func headlineMedium() -> Font { .system(size: 28, weight: .regular) }
  static func titleLarge() -> Font { .system(size: 22, weight: .regular) }
  static func titleMedium() -> Font { .system(size: 16, weight: .medium) }
  static func titleSmall() -> Font { .system(size: 14, weight: .medium) }
  static func bodyLarge() -> Font { .system(size: 16, weight: .regular) }
  static func bodyMedium() -> Font { .system(size: 14, weight: .regular) }
  static func bodySmall() -> Font { .system(size: 12, weight: .regular) }
  static func labelLarge() -> Font { .system(size: 14, weight: .medium) }
  static func labelMedium() -> Font { .system(size: 12, weight: .medium) }
  static func labelSmall() -> Font { .system(size: 11, weight: .medium) }
}

// MARK: - 形状

enum AppRadius {
  static let small: CGFloat = 8
  static let medium: CGFloat = 12
  static let large: CGFloat = 16
  static let extraLarge: CGFloat = 28
}

// MARK: - 时间格式化（逐字对齐安卓 FormatTime.kt）

enum Format {
  static func time(_ value: Double) -> String { clockString(value) }

  /// `formatTime(timeMs, durationMs)` 的等价实现：
  /// 补零宽度取决于「两者中较大的那个」，不是固定的 HH:mm:ss
  static func clockString(_ seconds: Double, duration: Double = 0) -> String {
    let ms = max(0, seconds)
    let reference = max(ms, duration)
    let total = Int(ms)
    let h = total / 3600
    let m = (total / 60) % 60
    let s = total % 60
    if hours(reference) > 0 {
      let hourDigits = digits(hours(reference))
      return String(format: "%0\(hourDigits)d:%02d:%02d", h, m, s)
    }
    let minuteDigits = digits(minutes(reference))
    if minuteDigits == 0 { return String(format: "%d:%02d", m, s) }
    return String(format: "%0\(minuteDigits)d:%02d", m, s)
  }

  private static func hours(_ v: Double) -> Int { Int(v) / 3600 }
  private static func minutes(_ v: Double) -> Int { (Int(v) / 60) % 60 }
  private static func digits(_ v: Int) -> Int { v == 0 ? 0 : String(v).count }

  static func remaining(_ seconds: Double) -> String {
    let v = max(0, seconds)
    let h = Int(v) / 3600
    let m = (Int(v) / 60) % 60
    let s = Int(v) % 60
    if h > 0 { return String(format: "%d:%02d:%02d", h, m, s) }
    return String(format: "%d:%02d", m, s)
  }

  /// 书籍卡片上的「剩余时长」
  static func humanDuration(_ seconds: Double) -> String { remaining(seconds) }

  static func percent(_ fraction: Double) -> String {
    "\(Int((min(1, max(0, fraction)) * 100).rounded(.down)))%"
  }

  static func speed(_ value: Double) -> String { String(format: "%.2f x", value) }
  static func gain(_ value: Double) -> String { String(format: "%.1f dB", value) }

  /// 字节数（对齐安卓 android.text.format.Formatter 的短格式）
  static func bytes(_ value: Int64) -> String {
    guard value > 0 else { return "0 MB" }
    return ByteCountFormatter.string(fromByteCount: value, countStyle: .file)
  }
}

// MARK: - 主题模式

extension ThemeMode {
  var colorScheme: ColorScheme? {
    switch self {
    case .system: return nil
    case .light: return .light
    case .dark: return .dark
    }
  }
}
