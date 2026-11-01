import SwiftUI

// MARK: - 安卓矢量图标（由 VoiceIcons.kt / drawable 自动生成，勿手改）
//
// 安卓的图标是 Material Symbols 的 ImageVector（pathData 展开成 Kotlin DSL 调用）。
// 这里把同一份路径按绝对坐标重绘成 SwiftUI Path —— 图形与安卓逐点一致，
// 不依赖 SF Symbols，也就不会出现「苹果味」的替代图形。

enum AndroidGlyph {
  case close
  case bedtime
  case bedtimeOff
  case collectionsBookmark
  case speed
  case moreVert
  case chevronLeft
  case chevronRight
  case expandMore
  case undo
  case add
  case remove
  case timer
  case delete
  case check
  case playArrow
  case pause

  /// 原始 viewport，绘制时按这个尺寸等比缩放到目标 frame
  var viewport: CGSize {
    switch self {
    case .close: return CGSize(width: 24, height: 24)
    case .bedtime: return CGSize(width: 24, height: 24)
    case .bedtimeOff: return CGSize(width: 24, height: 24)
    case .collectionsBookmark: return CGSize(width: 24, height: 24)
    case .speed: return CGSize(width: 24, height: 24)
    case .moreVert: return CGSize(width: 24, height: 24)
    case .chevronLeft: return CGSize(width: 24, height: 24)
    case .chevronRight: return CGSize(width: 24, height: 24)
    case .expandMore: return CGSize(width: 24, height: 24)
    case .undo: return CGSize(width: 24, height: 24)
    case .add: return CGSize(width: 24, height: 24)
    case .remove: return CGSize(width: 24, height: 24)
    case .timer: return CGSize(width: 24, height: 24)
    case .delete: return CGSize(width: 24, height: 24)
    case .check: return CGSize(width: 24, height: 24)
    case .playArrow: return CGSize(width: 48, height: 48)
    case .pause: return CGSize(width: 24, height: 24)
    }
  }

  /// 把路径追加进 Path（安卓 viewport 坐标系）
  func append(to p: inout Path) {
    switch self {
    case .close: Self.drawClose(&p)
    case .bedtime: Self.drawBedtime(&p)
    case .bedtimeOff: Self.drawBedtimeOff(&p)
    case .collectionsBookmark: Self.drawCollectionsBookmark(&p)
    case .speed: Self.drawSpeed(&p)
    case .moreVert: Self.drawMoreVert(&p)
    case .chevronLeft: Self.drawChevronLeft(&p)
    case .chevronRight: Self.drawChevronRight(&p)
    case .expandMore: Self.drawExpandMore(&p)
    case .undo: Self.drawUndo(&p)
    case .add: Self.drawAdd(&p)
    case .remove: Self.drawRemove(&p)
    case .timer: Self.drawTimer(&p)
    case .delete: Self.drawDelete(&p)
    case .check: Self.drawCheck(&p)
    case .playArrow: Self.drawPlayArrow(&p)
    case .pause: Self.drawPause(&p)
    }
  }

  /// 关闭
  private static func drawClose(_ p: inout Path) {
    p.move(to: CGPoint(x: 6.4, y: 19))
    p.addLine(to: CGPoint(x: 5, y: 17.6))
    p.addLine(to: CGPoint(x: 10.6, y: 12))
    p.addLine(to: CGPoint(x: 5, y: 6.4))
    p.addLine(to: CGPoint(x: 6.4, y: 5))
    p.addLine(to: CGPoint(x: 12, y: 10.6))
    p.addLine(to: CGPoint(x: 17.6, y: 5))
    p.addLine(to: CGPoint(x: 19, y: 6.4))
    p.addLine(to: CGPoint(x: 13.4, y: 12))
    p.addLine(to: CGPoint(x: 19, y: 17.6))
    p.addLine(to: CGPoint(x: 17.6, y: 19))
    p.addLine(to: CGPoint(x: 12, y: 13.4))
    p.addLine(to: CGPoint(x: 6.4, y: 19))
    p.closeSubpath()
  }

  /// 睡眠定时（关）
  private static func drawBedtime(_ p: inout Path) {
    p.move(to: CGPoint(x: 12.1, y: 22))
    p.addQuadCurve(to: CGPoint(x: 8.16, y: 21.2), control: CGPoint(x: 10, y: 22))
    p.addQuadCurve(to: CGPoint(x: 4.96, y: 19.04), control: CGPoint(x: 6.33, y: 20.4))
    p.addQuadCurve(to: CGPoint(x: 2.8, y: 15.84), control: CGPoint(x: 3.59, y: 17.68))
    p.addQuadCurve(to: CGPoint(x: 2, y: 11.9), control: CGPoint(x: 2.01, y: 14))
    p.addQuadCurve(to: CGPoint(x: 4.33, y: 5.46), control: CGPoint(x: 2, y: 8.25))
    p.addQuadCurve(to: CGPoint(x: 10.25, y: 2), control: CGPoint(x: 6.66, y: 2.67))
    p.addQuadCurve(to: CGPoint(x: 10.53, y: 6.84), control: CGPoint(x: 9.8, y: 4.47))
    p.addQuadCurve(to: CGPoint(x: 13.03, y: 10.98), control: CGPoint(x: 11.26, y: 9.21))
    p.addQuadCurve(to: CGPoint(x: 17.17, y: 13.48), control: CGPoint(x: 14.8, y: 12.75))
    p.addQuadCurve(to: CGPoint(x: 22, y: 13.75), control: CGPoint(x: 19.54, y: 14.21))
    p.addQuadCurve(to: CGPoint(x: 18.55, y: 19.68), control: CGPoint(x: 21.35, y: 17.35))
    p.addQuadCurve(to: CGPoint(x: 12.1, y: 22), control: CGPoint(x: 15.75, y: 22.01))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12.1, y: 20))
    p.addQuadCurve(to: CGPoint(x: 16.17, y: 18.9), control: CGPoint(x: 14.3, y: 20))
    p.addQuadCurve(to: CGPoint(x: 19.12, y: 15.88), control: CGPoint(x: 18.04, y: 17.8))
    p.addQuadCurve(to: CGPoint(x: 15.05, y: 14.79), control: CGPoint(x: 16.97, y: 15.68))
    p.addQuadCurve(to: CGPoint(x: 11.6, y: 12.38), control: CGPoint(x: 13.13, y: 13.9))
    p.addQuadCurve(to: CGPoint(x: 9.18, y: 8.92), control: CGPoint(x: 10.07, y: 10.86))
    p.addQuadCurve(to: CGPoint(x: 8.1, y: 4.85), control: CGPoint(x: 8.29, y: 6.98))
    p.addQuadCurve(to: CGPoint(x: 5.09, y: 7.81), control: CGPoint(x: 6.18, y: 5.93))
    p.addQuadCurve(to: CGPoint(x: 4, y: 11.9), control: CGPoint(x: 4, y: 9.69))
    p.addQuadCurve(to: CGPoint(x: 6.36, y: 17.64), control: CGPoint(x: 4, y: 15.28))
    p.addQuadCurve(to: CGPoint(x: 12.1, y: 20), control: CGPoint(x: 8.72, y: 20))
    p.closeSubpath()
    p.move(to: CGPoint(x: 11.6, y: 12.38))
    p.closeSubpath()
  }

  /// 睡眠定时（开）
  private static func drawBedtimeOff(_ p: inout Path) {
    p.move(to: CGPoint(x: 19.15, y: 19.13))
    p.addLine(to: CGPoint(x: 17.73, y: 17.7))
    p.addQuadCurve(to: CGPoint(x: 18.5, y: 16.84), control: CGPoint(x: 18.16, y: 17.3))
    p.addQuadCurve(to: CGPoint(x: 19.13, y: 15.88), control: CGPoint(x: 18.85, y: 16.38))
    p.addQuadCurve(to: CGPoint(x: 16.78, y: 15.43), control: CGPoint(x: 17.93, y: 15.75))
    p.addQuadCurve(to: CGPoint(x: 14.58, y: 14.55), control: CGPoint(x: 15.63, y: 15.11))
    p.addLine(to: CGPoint(x: 9.4, y: 9.38))
    p.addQuadCurve(to: CGPoint(x: 8.53, y: 7.19), control: CGPoint(x: 8.85, y: 8.32))
    p.addQuadCurve(to: CGPoint(x: 8.1, y: 4.85), control: CGPoint(x: 8.21, y: 6.06))
    p.addQuadCurve(to: CGPoint(x: 7.14, y: 5.47), control: CGPoint(x: 7.6, y: 5.13))
    p.addQuadCurve(to: CGPoint(x: 6.28, y: 6.25), control: CGPoint(x: 6.68, y: 5.81))
    p.addLine(to: CGPoint(x: 4.88, y: 4.85))
    p.addQuadCurve(to: CGPoint(x: 7.31, y: 3.02), control: CGPoint(x: 5.95, y: 3.75))
    p.addQuadCurve(to: CGPoint(x: 10.25, y: 2), control: CGPoint(x: 8.67, y: 2.29))
    p.addQuadCurve(to: CGPoint(x: 10.53, y: 6.84), control: CGPoint(x: 9.8, y: 4.47))
    p.addQuadCurve(to: CGPoint(x: 13.03, y: 10.98), control: CGPoint(x: 11.26, y: 9.21))
    p.addQuadCurve(to: CGPoint(x: 17.17, y: 13.48), control: CGPoint(x: 14.8, y: 12.75))
    p.addQuadCurve(to: CGPoint(x: 22, y: 13.75), control: CGPoint(x: 19.54, y: 14.21))
    p.addQuadCurve(to: CGPoint(x: 20.99, y: 16.69), control: CGPoint(x: 21.73, y: 15.32))
    p.addQuadCurve(to: CGPoint(x: 19.15, y: 19.13), control: CGPoint(x: 20.25, y: 18.06))
    p.closeSubpath()
    p.move(to: CGPoint(x: 18.37, y: 24.06))
    p.addLine(to: CGPoint(x: 15.67, y: 21.36))
    p.addQuadCurve(to: CGPoint(x: 13.93, y: 21.85), control: CGPoint(x: 14.82, y: 21.68))
    p.addQuadCurve(to: CGPoint(x: 12.1, y: 22), control: CGPoint(x: 13.04, y: 22.02))
    p.addQuadCurve(to: CGPoint(x: 8.16, y: 21.2), control: CGPoint(x: 10, y: 22))
    p.addQuadCurve(to: CGPoint(x: 4.96, y: 19.04), control: CGPoint(x: 6.33, y: 20.4))
    p.addQuadCurve(to: CGPoint(x: 2.8, y: 15.84), control: CGPoint(x: 3.59, y: 17.68))
    p.addQuadCurve(to: CGPoint(x: 2, y: 11.9), control: CGPoint(x: 2.01, y: 14))
    p.addQuadCurve(to: CGPoint(x: 2.16, y: 10.06), control: CGPoint(x: 2, y: 10.95))
    p.addQuadCurve(to: CGPoint(x: 2.65, y: 8.32), control: CGPoint(x: 2.33, y: 9.17))
    p.addLine(to: CGPoint(x: -0.03, y: 5.65))
    p.addLine(to: CGPoint(x: 1.4, y: 4.22))
    p.addLine(to: CGPoint(x: 19.8, y: 22.62))
    p.addLine(to: CGPoint(x: 18.37, y: 24.05))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12.1, y: 20))
    p.addQuadCurve(to: CGPoint(x: 13.1, y: 19.94), control: CGPoint(x: 12.6, y: 20))
    p.addQuadCurve(to: CGPoint(x: 14.07, y: 19.75), control: CGPoint(x: 13.6, y: 19.88))
    p.addLine(to: CGPoint(x: 4.25, y: 9.92))
    p.addQuadCurve(to: CGPoint(x: 4.06, y: 10.91), control: CGPoint(x: 4.12, y: 10.42))
    p.addQuadCurve(to: CGPoint(x: 4, y: 11.9), control: CGPoint(x: 4, y: 11.4))
    p.addQuadCurve(to: CGPoint(x: 6.36, y: 17.64), control: CGPoint(x: 4, y: 15.28))
    p.addQuadCurve(to: CGPoint(x: 12.1, y: 20), control: CGPoint(x: 8.72, y: 20))
    p.closeSubpath()
    p.move(to: CGPoint(x: 9.15, y: 14.83))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12, y: 11.98))
    p.closeSubpath()
  }

  /// 书签
  private static func drawCollectionsBookmark(_ p: inout Path) {
    p.move(to: CGPoint(x: 8, y: 16))
    p.addLine(to: CGPoint(x: 20, y: 16))
    p.addLine(to: CGPoint(x: 20, y: 4))
    p.addLine(to: CGPoint(x: 18, y: 4))
    p.addLine(to: CGPoint(x: 18, y: 11))
    p.addLine(to: CGPoint(x: 15.5, y: 9.5))
    p.addLine(to: CGPoint(x: 13, y: 11))
    p.addLine(to: CGPoint(x: 13, y: 4))
    p.addLine(to: CGPoint(x: 8, y: 4))
    p.addLine(to: CGPoint(x: 8, y: 16))
    p.closeSubpath()
    p.move(to: CGPoint(x: 8, y: 18))
    p.addQuadCurve(to: CGPoint(x: 6.59, y: 17.41), control: CGPoint(x: 7.18, y: 18))
    p.addQuadCurve(to: CGPoint(x: 6, y: 16), control: CGPoint(x: 6, y: 16.82))
    p.addLine(to: CGPoint(x: 6, y: 4))
    p.addQuadCurve(to: CGPoint(x: 6.59, y: 2.59), control: CGPoint(x: 6, y: 3.17))
    p.addQuadCurve(to: CGPoint(x: 8, y: 2), control: CGPoint(x: 7.18, y: 2.01))
    p.addLine(to: CGPoint(x: 20, y: 2))
    p.addQuadCurve(to: CGPoint(x: 21.41, y: 2.59), control: CGPoint(x: 20.83, y: 2))
    p.addQuadCurve(to: CGPoint(x: 22, y: 4), control: CGPoint(x: 21.99, y: 3.18))
    p.addLine(to: CGPoint(x: 22, y: 16))
    p.addQuadCurve(to: CGPoint(x: 21.41, y: 17.41), control: CGPoint(x: 22, y: 16.82))
    p.addQuadCurve(to: CGPoint(x: 20, y: 18), control: CGPoint(x: 20.82, y: 18))
    p.addLine(to: CGPoint(x: 8, y: 18))
    p.closeSubpath()
    p.move(to: CGPoint(x: 4, y: 22))
    p.addQuadCurve(to: CGPoint(x: 2.59, y: 21.41), control: CGPoint(x: 3.18, y: 22))
    p.addQuadCurve(to: CGPoint(x: 2, y: 20), control: CGPoint(x: 2, y: 20.82))
    p.addLine(to: CGPoint(x: 2, y: 6))
    p.addLine(to: CGPoint(x: 4, y: 6))
    p.addLine(to: CGPoint(x: 4, y: 20))
    p.addLine(to: CGPoint(x: 18, y: 20))
    p.addLine(to: CGPoint(x: 18, y: 22))
    p.addLine(to: CGPoint(x: 4, y: 22))
    p.closeSubpath()
    p.move(to: CGPoint(x: 13, y: 4))
    p.addLine(to: CGPoint(x: 15.5, y: 4))
    p.addLine(to: CGPoint(x: 18, y: 4))
    p.addLine(to: CGPoint(x: 13, y: 4))
    p.closeSubpath()
    p.move(to: CGPoint(x: 8, y: 4))
    p.addLine(to: CGPoint(x: 13, y: 4))
    p.addLine(to: CGPoint(x: 15.5, y: 4))
    p.addLine(to: CGPoint(x: 18, y: 4))
    p.addLine(to: CGPoint(x: 20, y: 4))
    p.addLine(to: CGPoint(x: 8, y: 4))
    p.closeSubpath()
  }

  /// 播放速度
  private static func drawSpeed(_ p: inout Path) {
    p.move(to: CGPoint(x: 12, y: 16.09))
    p.addQuadCurve(to: CGPoint(x: 13.4, y: 15.4), control: CGPoint(x: 12.95, y: 16.08))
    p.addLine(to: CGPoint(x: 19, y: 7))
    p.addLine(to: CGPoint(x: 10.6, y: 12.6))
    p.addQuadCurve(to: CGPoint(x: 9.89, y: 13.98), control: CGPoint(x: 9.93, y: 13.05))
    p.addQuadCurve(to: CGPoint(x: 10.45, y: 15.51), control: CGPoint(x: 9.85, y: 14.91))
    p.addQuadCurve(to: CGPoint(x: 12, y: 16.09), control: CGPoint(x: 11.05, y: 16.11))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12, y: 4))
    p.addQuadCurve(to: CGPoint(x: 14.84, y: 4.41), control: CGPoint(x: 13.48, y: 4))
    p.addQuadCurve(to: CGPoint(x: 17.4, y: 5.65), control: CGPoint(x: 16.2, y: 4.82))
    p.addLine(to: CGPoint(x: 15.5, y: 6.85))
    p.addQuadCurve(to: CGPoint(x: 13.79, y: 6.21), control: CGPoint(x: 14.68, y: 6.43))
    p.addQuadCurve(to: CGPoint(x: 12, y: 6), control: CGPoint(x: 12.9, y: 5.99))
    p.addQuadCurve(to: CGPoint(x: 6.34, y: 8.34), control: CGPoint(x: 8.68, y: 6))
    p.addQuadCurve(to: CGPoint(x: 4, y: 14), control: CGPoint(x: 4, y: 10.68))
    p.addQuadCurve(to: CGPoint(x: 4.29, y: 16.07), control: CGPoint(x: 4, y: 15.05))
    p.addQuadCurve(to: CGPoint(x: 5.1, y: 18), control: CGPoint(x: 4.58, y: 17.09))
    p.addLine(to: CGPoint(x: 18.9, y: 18))
    p.addQuadCurve(to: CGPoint(x: 19.74, y: 16.02), control: CGPoint(x: 19.47, y: 17.05))
    p.addQuadCurve(to: CGPoint(x: 20, y: 13.9), control: CGPoint(x: 20.01, y: 14.99))
    p.addQuadCurve(to: CGPoint(x: 19.79, y: 12.15), control: CGPoint(x: 20, y: 13))
    p.addQuadCurve(to: CGPoint(x: 19.15, y: 10.5), control: CGPoint(x: 19.58, y: 11.3))
    p.addLine(to: CGPoint(x: 20.35, y: 8.6))
    p.addQuadCurve(to: CGPoint(x: 21.54, y: 11.1), control: CGPoint(x: 21.1, y: 9.77))
    p.addQuadCurve(to: CGPoint(x: 22, y: 13.85), control: CGPoint(x: 21.98, y: 12.43))
    p.addQuadCurve(to: CGPoint(x: 21.68, y: 16.57), control: CGPoint(x: 22.02, y: 15.27))
    p.addQuadCurve(to: CGPoint(x: 20.66, y: 19.05), control: CGPoint(x: 21.34, y: 17.87))
    p.addQuadCurve(to: CGPoint(x: 19.91, y: 19.75), control: CGPoint(x: 20.38, y: 19.5))
    p.addQuadCurve(to: CGPoint(x: 18.9, y: 20), control: CGPoint(x: 19.44, y: 20))
    p.addLine(to: CGPoint(x: 5.1, y: 20))
    p.addQuadCurve(to: CGPoint(x: 4.1, y: 19.75), control: CGPoint(x: 4.57, y: 20))
    p.addQuadCurve(to: CGPoint(x: 3.35, y: 19.05), control: CGPoint(x: 3.63, y: 19.5))
    p.addQuadCurve(to: CGPoint(x: 2.35, y: 16.66), control: CGPoint(x: 2.7, y: 17.93))
    p.addQuadCurve(to: CGPoint(x: 2, y: 14), control: CGPoint(x: 2, y: 15.39))
    p.addQuadCurve(to: CGPoint(x: 2.79, y: 10.11), control: CGPoint(x: 2, y: 11.93))
    p.addQuadCurve(to: CGPoint(x: 4.94, y: 6.94), control: CGPoint(x: 3.58, y: 8.29))
    p.addQuadCurve(to: CGPoint(x: 8.13, y: 4.79), control: CGPoint(x: 6.3, y: 5.57))
    p.addQuadCurve(to: CGPoint(x: 12, y: 4), control: CGPoint(x: 9.96, y: 4.01))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12.18, y: 11.82))
    p.closeSubpath()
  }

  /// 更多（竖三点）
  private static func drawMoreVert(_ p: inout Path) {
    p.move(to: CGPoint(x: 12, y: 20))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 19.41), control: CGPoint(x: 11.18, y: 20))
    p.addQuadCurve(to: CGPoint(x: 10, y: 18), control: CGPoint(x: 10, y: 18.82))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 16.59), control: CGPoint(x: 10, y: 17.18))
    p.addQuadCurve(to: CGPoint(x: 12, y: 16), control: CGPoint(x: 11.18, y: 16))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 16.59), control: CGPoint(x: 12.82, y: 16))
    p.addQuadCurve(to: CGPoint(x: 14, y: 18), control: CGPoint(x: 14, y: 17.18))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 19.41), control: CGPoint(x: 14, y: 18.82))
    p.addQuadCurve(to: CGPoint(x: 12, y: 20), control: CGPoint(x: 12.82, y: 20))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12, y: 14))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 13.41), control: CGPoint(x: 11.18, y: 14))
    p.addQuadCurve(to: CGPoint(x: 10, y: 12), control: CGPoint(x: 10, y: 12.82))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 10.59), control: CGPoint(x: 10, y: 11.18))
    p.addQuadCurve(to: CGPoint(x: 12, y: 10), control: CGPoint(x: 11.18, y: 10))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 10.59), control: CGPoint(x: 12.82, y: 10))
    p.addQuadCurve(to: CGPoint(x: 14, y: 12), control: CGPoint(x: 14, y: 11.18))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 13.41), control: CGPoint(x: 14, y: 12.82))
    p.addQuadCurve(to: CGPoint(x: 12, y: 14), control: CGPoint(x: 12.82, y: 14))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12, y: 8))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 7.41), control: CGPoint(x: 11.18, y: 8))
    p.addQuadCurve(to: CGPoint(x: 10, y: 6), control: CGPoint(x: 10, y: 6.82))
    p.addQuadCurve(to: CGPoint(x: 10.59, y: 4.59), control: CGPoint(x: 10, y: 5.18))
    p.addQuadCurve(to: CGPoint(x: 12, y: 4), control: CGPoint(x: 11.18, y: 4))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 4.59), control: CGPoint(x: 12.82, y: 4))
    p.addQuadCurve(to: CGPoint(x: 14, y: 6), control: CGPoint(x: 14, y: 5.18))
    p.addQuadCurve(to: CGPoint(x: 13.41, y: 7.41), control: CGPoint(x: 14, y: 6.82))
    p.addQuadCurve(to: CGPoint(x: 12, y: 8), control: CGPoint(x: 12.82, y: 8))
    p.closeSubpath()
  }

  /// 上一节
  private static func drawChevronLeft(_ p: inout Path) {
    p.move(to: CGPoint(x: 14, y: 18))
    p.addLine(to: CGPoint(x: 8, y: 12))
    p.addLine(to: CGPoint(x: 14, y: 6))
    p.addLine(to: CGPoint(x: 15.4, y: 7.4))
    p.addLine(to: CGPoint(x: 10.8, y: 12))
    p.addLine(to: CGPoint(x: 15.4, y: 16.6))
    p.addLine(to: CGPoint(x: 14, y: 18))
    p.closeSubpath()
  }

  /// 下一节
  private static func drawChevronRight(_ p: inout Path) {
    p.move(to: CGPoint(x: 12.6, y: 12))
    p.addLine(to: CGPoint(x: 8, y: 7.4))
    p.addLine(to: CGPoint(x: 9.4, y: 6))
    p.addLine(to: CGPoint(x: 15.4, y: 12))
    p.addLine(to: CGPoint(x: 9.4, y: 18))
    p.addLine(to: CGPoint(x: 8, y: 16.6))
    p.addLine(to: CGPoint(x: 12.6, y: 12))
    p.closeSubpath()
  }

  /// 展开章节
  private static func drawExpandMore(_ p: inout Path) {
    p.move(to: CGPoint(x: 12, y: 15.38))
    p.addLine(to: CGPoint(x: 6, y: 9.38))
    p.addLine(to: CGPoint(x: 7.4, y: 7.97))
    p.addLine(to: CGPoint(x: 12, y: 12.57))
    p.addLine(to: CGPoint(x: 16.6, y: 7.97))
    p.addLine(to: CGPoint(x: 18, y: 9.38))
    p.addLine(to: CGPoint(x: 12, y: 15.38))
    p.closeSubpath()
  }

  /// 快退 / 快进（镜像）
  private static func drawUndo(_ p: inout Path) {
    p.move(to: CGPoint(x: 7, y: 19))
    p.addLine(to: CGPoint(x: 7, y: 17))
    p.addLine(to: CGPoint(x: 14.1, y: 17))
    p.addQuadCurve(to: CGPoint(x: 16.84, y: 16), control: CGPoint(x: 15.67, y: 17))
    p.addQuadCurve(to: CGPoint(x: 18, y: 13.5), control: CGPoint(x: 18.01, y: 15))
    p.addQuadCurve(to: CGPoint(x: 16.84, y: 11), control: CGPoint(x: 17.99, y: 12))
    p.addQuadCurve(to: CGPoint(x: 14.1, y: 10), control: CGPoint(x: 15.69, y: 10))
    p.addLine(to: CGPoint(x: 7.8, y: 10))
    p.addLine(to: CGPoint(x: 10.4, y: 12.6))
    p.addLine(to: CGPoint(x: 9, y: 14))
    p.addLine(to: CGPoint(x: 4, y: 9))
    p.addLine(to: CGPoint(x: 9, y: 4))
    p.addLine(to: CGPoint(x: 10.4, y: 5.4))
    p.addLine(to: CGPoint(x: 7.8, y: 8))
    p.addLine(to: CGPoint(x: 14.1, y: 8))
    p.addQuadCurve(to: CGPoint(x: 18.26, y: 9.57), control: CGPoint(x: 16.53, y: 8))
    p.addQuadCurve(to: CGPoint(x: 20, y: 13.5), control: CGPoint(x: 19.99, y: 11.14))
    p.addQuadCurve(to: CGPoint(x: 18.26, y: 17.43), control: CGPoint(x: 20.01, y: 15.86))
    p.addQuadCurve(to: CGPoint(x: 14.1, y: 19), control: CGPoint(x: 16.53, y: 19))
    p.addLine(to: CGPoint(x: 7, y: 19))
    p.closeSubpath()
  }

  /// 增加
  private static func drawAdd(_ p: inout Path) {
    p.move(to: CGPoint(x: 11, y: 13))
    p.addLine(to: CGPoint(x: 5, y: 13))
    p.addLine(to: CGPoint(x: 5, y: 11))
    p.addLine(to: CGPoint(x: 11, y: 11))
    p.addLine(to: CGPoint(x: 11, y: 5))
    p.addLine(to: CGPoint(x: 13, y: 5))
    p.addLine(to: CGPoint(x: 13, y: 11))
    p.addLine(to: CGPoint(x: 19, y: 11))
    p.addLine(to: CGPoint(x: 19, y: 13))
    p.addLine(to: CGPoint(x: 13, y: 13))
    p.addLine(to: CGPoint(x: 13, y: 19))
    p.addLine(to: CGPoint(x: 11, y: 19))
    p.addLine(to: CGPoint(x: 11, y: 13))
    p.closeSubpath()
  }

  /// 减少
  private static func drawRemove(_ p: inout Path) {
    p.move(to: CGPoint(x: 5, y: 13))
    p.addLine(to: CGPoint(x: 5, y: 11))
    p.addLine(to: CGPoint(x: 19, y: 11))
    p.addLine(to: CGPoint(x: 19, y: 13))
    p.addLine(to: CGPoint(x: 5, y: 13))
    p.closeSubpath()
  }

  /// 定时
  private static func drawTimer(_ p: inout Path) {
    p.move(to: CGPoint(x: 9, y: 3))
    p.addLine(to: CGPoint(x: 9, y: 1))
    p.addLine(to: CGPoint(x: 15, y: 1))
    p.addLine(to: CGPoint(x: 15, y: 3))
    p.addLine(to: CGPoint(x: 9, y: 3))
    p.closeSubpath()
    p.move(to: CGPoint(x: 11, y: 14))
    p.addLine(to: CGPoint(x: 13, y: 14))
    p.addLine(to: CGPoint(x: 13, y: 8))
    p.addLine(to: CGPoint(x: 11, y: 8))
    p.addLine(to: CGPoint(x: 11, y: 14))
    p.closeSubpath()
    p.move(to: CGPoint(x: 8.51, y: 21.29))
    p.addQuadCurve(to: CGPoint(x: 5.65, y: 19.35), control: CGPoint(x: 6.88, y: 20.58))
    p.addQuadCurve(to: CGPoint(x: 3.71, y: 16.49), control: CGPoint(x: 4.42, y: 18.12))
    p.addQuadCurve(to: CGPoint(x: 3, y: 13), control: CGPoint(x: 3, y: 14.86))
    p.addQuadCurve(to: CGPoint(x: 3.71, y: 9.51), control: CGPoint(x: 3, y: 11.14))
    p.addQuadCurve(to: CGPoint(x: 5.65, y: 6.65), control: CGPoint(x: 4.42, y: 7.88))
    p.addQuadCurve(to: CGPoint(x: 8.51, y: 4.71), control: CGPoint(x: 6.88, y: 5.43))
    p.addQuadCurve(to: CGPoint(x: 12, y: 4), control: CGPoint(x: 10.14, y: 3.99))
    p.addQuadCurve(to: CGPoint(x: 14.98, y: 4.5), control: CGPoint(x: 13.55, y: 4))
    p.addQuadCurve(to: CGPoint(x: 17.66, y: 5.95), control: CGPoint(x: 16.41, y: 5))
    p.addLine(to: CGPoint(x: 19.06, y: 4.55))
    p.addLine(to: CGPoint(x: 20.46, y: 5.95))
    p.addLine(to: CGPoint(x: 19.06, y: 7.35))
    p.addQuadCurve(to: CGPoint(x: 20.5, y: 10.02), control: CGPoint(x: 20, y: 8.6))
    p.addQuadCurve(to: CGPoint(x: 21, y: 13), control: CGPoint(x: 21, y: 11.44))
    p.addQuadCurve(to: CGPoint(x: 20.29, y: 16.49), control: CGPoint(x: 21, y: 14.85))
    p.addQuadCurve(to: CGPoint(x: 18.35, y: 19.35), control: CGPoint(x: 19.58, y: 18.13))
    p.addQuadCurve(to: CGPoint(x: 15.49, y: 21.29), control: CGPoint(x: 17.12, y: 20.57))
    p.addQuadCurve(to: CGPoint(x: 12, y: 22), control: CGPoint(x: 13.86, y: 22.01))
    p.addQuadCurve(to: CGPoint(x: 8.51, y: 21.29), control: CGPoint(x: 10.14, y: 21.99))
    p.closeSubpath()
    p.move(to: CGPoint(x: 16.95, y: 17.95))
    p.addQuadCurve(to: CGPoint(x: 19, y: 13), control: CGPoint(x: 19, y: 15.9))
    p.addQuadCurve(to: CGPoint(x: 16.95, y: 8.05), control: CGPoint(x: 19, y: 10.1))
    p.addQuadCurve(to: CGPoint(x: 12, y: 6), control: CGPoint(x: 14.9, y: 6))
    p.addQuadCurve(to: CGPoint(x: 7.05, y: 8.05), control: CGPoint(x: 9.1, y: 6))
    p.addQuadCurve(to: CGPoint(x: 5, y: 13), control: CGPoint(x: 5, y: 10.1))
    p.addQuadCurve(to: CGPoint(x: 7.05, y: 17.95), control: CGPoint(x: 5, y: 15.9))
    p.addQuadCurve(to: CGPoint(x: 12, y: 20), control: CGPoint(x: 9.1, y: 20))
    p.addQuadCurve(to: CGPoint(x: 16.95, y: 17.95), control: CGPoint(x: 14.9, y: 20))
    p.closeSubpath()
    p.move(to: CGPoint(x: 12, y: 13))
    p.closeSubpath()
  }

  /// 删除
  private static func drawDelete(_ p: inout Path) {
    p.move(to: CGPoint(x: 7, y: 21))
    p.addQuadCurve(to: CGPoint(x: 5.59, y: 20.41), control: CGPoint(x: 6.18, y: 21))
    p.addQuadCurve(to: CGPoint(x: 5, y: 19), control: CGPoint(x: 5, y: 19.82))
    p.addLine(to: CGPoint(x: 5, y: 6))
    p.addLine(to: CGPoint(x: 4, y: 6))
    p.addLine(to: CGPoint(x: 4, y: 4))
    p.addLine(to: CGPoint(x: 9, y: 4))
    p.addLine(to: CGPoint(x: 9, y: 3))
    p.addLine(to: CGPoint(x: 15, y: 3))
    p.addLine(to: CGPoint(x: 15, y: 4))
    p.addLine(to: CGPoint(x: 20, y: 4))
    p.addLine(to: CGPoint(x: 20, y: 6))
    p.addLine(to: CGPoint(x: 19, y: 6))
    p.addLine(to: CGPoint(x: 19, y: 19))
    p.addQuadCurve(to: CGPoint(x: 18.41, y: 20.41), control: CGPoint(x: 19, y: 19.82))
    p.addQuadCurve(to: CGPoint(x: 17, y: 21), control: CGPoint(x: 17.82, y: 21))
    p.addLine(to: CGPoint(x: 7, y: 21))
    p.closeSubpath()
    p.move(to: CGPoint(x: 17, y: 6))
    p.addLine(to: CGPoint(x: 7, y: 6))
    p.addLine(to: CGPoint(x: 7, y: 19))
    p.addLine(to: CGPoint(x: 17, y: 19))
    p.addLine(to: CGPoint(x: 17, y: 6))
    p.closeSubpath()
    p.move(to: CGPoint(x: 9, y: 17))
    p.addLine(to: CGPoint(x: 11, y: 17))
    p.addLine(to: CGPoint(x: 11, y: 8))
    p.addLine(to: CGPoint(x: 9, y: 8))
    p.addLine(to: CGPoint(x: 9, y: 17))
    p.closeSubpath()
    p.move(to: CGPoint(x: 13, y: 17))
    p.addLine(to: CGPoint(x: 15, y: 17))
    p.addLine(to: CGPoint(x: 15, y: 8))
    p.addLine(to: CGPoint(x: 13, y: 8))
    p.addLine(to: CGPoint(x: 13, y: 17))
    p.closeSubpath()
    p.move(to: CGPoint(x: 7, y: 6))
    p.addLine(to: CGPoint(x: 7, y: 19))
    p.addLine(to: CGPoint(x: 7, y: 6))
    p.closeSubpath()
  }

  /// 勾选
  private static func drawCheck(_ p: inout Path) {
    p.move(to: CGPoint(x: 9.55, y: 18))
    p.addLine(to: CGPoint(x: 3.85, y: 12.3))
    p.addLine(to: CGPoint(x: 5.28, y: 10.88))
    p.addLine(to: CGPoint(x: 9.56, y: 15.16))
    p.addLine(to: CGPoint(x: 18.73, y: 5.97))
    p.addLine(to: CGPoint(x: 20.15, y: 7.4))
    p.addLine(to: CGPoint(x: 9.55, y: 18))
    p.closeSubpath()
  }

  /// 播放
  private static func drawPlayArrow(_ p: inout Path) {
    p.move(to: CGPoint(x: 16, y: 37.85))
    p.addLine(to: CGPoint(x: 16, y: 9.85))
    p.addLine(to: CGPoint(x: 38, y: 23.85))
    p.closeSubpath()
    p.move(to: CGPoint(x: 19, y: 23.85))
    p.closeSubpath()
    p.move(to: CGPoint(x: 19, y: 32.4))
    p.addLine(to: CGPoint(x: 32.45, y: 23.85))
    p.addLine(to: CGPoint(x: 19, y: 15.3))
    p.closeSubpath()
  }

  /// 暂停
  private static func drawPause(_ p: inout Path) {
    p.move(to: CGPoint(x: 6, y: 19))
    p.addLine(to: CGPoint(x: 10, y: 19))
    p.addLine(to: CGPoint(x: 10, y: 5))
    p.addLine(to: CGPoint(x: 6, y: 5))
    p.closeSubpath()
    p.move(to: CGPoint(x: 14, y: 5))
    p.addLine(to: CGPoint(x: 14, y: 19))
    p.addLine(to: CGPoint(x: 18, y: 19))
    p.addLine(to: CGPoint(x: 18, y: 5))
    p.closeSubpath()
  }

}

// MARK: - 渲染

struct AndroidIcon: View {
  var glyph: AndroidGlyph
  var size: CGFloat = 24
  var color: Color = AppColor.onSurfaceVariant
  /// 安卓 SkipButton 的做法：快进直接镜像同一个 Undo 图标
  var mirrored: Bool = false

  var body: some View {
    AndroidGlyphShape(glyph: glyph)
      .fill(color)
      .frame(width: size, height: size)
      .scaleEffect(x: mirrored ? -1 : 1, y: 1)
  }
}

struct AndroidGlyphShape: Shape {
  var glyph: AndroidGlyph

  func path(in rect: CGRect) -> Path {
    var p = Path()
    glyph.append(to: &p)
    let vp = glyph.viewport
    guard vp.width > 0, vp.height > 0 else { return p }
    let s = min(rect.width / vp.width, rect.height / vp.height)
    var scaled = p.applying(CGAffineTransform(scaleX: s, y: s))
    let dx = rect.minX + (rect.width - vp.width * s) / 2
    let dy = rect.minY + (rect.height - vp.height * s) / 2
    scaled = scaled.applying(CGAffineTransform(translationX: dx, y: dy))
    return scaled
  }
}

// MARK: - 封面占位图（对齐安卓 core/ui/res/drawable/album_art.xml）
//
// 安卓在没有封面时画的是一张「黑胶唱片」：暗底 + 大圆盘 + 唱针 + 内圈，
// 不是系统图标。这里按原始 viewport 1440 逐层重绘，颜色取自 values/colors.xml。

struct AlbumArtPlaceholder: View {
  var body: some View {
    ZStack {
      Color(hex: 0x1B1B1B)
      AlbumArtLayer(index: 1).fill(Color(hex: 0x5E5E5E))
      AlbumArtLayer(index: 2).fill(Color(hex: 0x464646))
      AlbumArtLayer(index: 3).fill(Color(hex: 0x464646))
      AlbumArtLayer(index: 4).fill(Color(hex: 0x757575))
      AlbumArtLayer(index: 5).fill(Color(hex: 0x464646))
    }
    .clipped()
  }
}

private struct AlbumArtLayer: Shape {
  var index: Int

  func path(in rect: CGRect) -> Path {
    var p = Path()
    switch index {
    case 1:
      p.move(to: CGPoint(x: 372, y: 720))
      p.addCurve(to: CGPoint(x: 546, y: 1021.377), control1: CGPoint(x: 372, y: 844.328), control2: CGPoint(x: 438.328, y: 959.213))
      p.addCurve(to: CGPoint(x: 894, y: 1021.377), control1: CGPoint(x: 653.672, y: 1083.541), control2: CGPoint(x: 786.328, y: 1083.541))
      p.addCurve(to: CGPoint(x: 1068, y: 720), control1: CGPoint(x: 1001.672, y: 959.213), control2: CGPoint(x: 1068, y: 844.328))
      p.move(to: CGPoint(x: 372, y: 720))
      p.addCurve(to: CGPoint(x: 546, y: 418.623), control1: CGPoint(x: 372, y: 595.672), control2: CGPoint(x: 438.328, y: 480.787))
      p.addCurve(to: CGPoint(x: 894, y: 418.623), control1: CGPoint(x: 653.672, y: 356.459), control2: CGPoint(x: 786.328, y: 356.459))
      p.addCurve(to: CGPoint(x: 1068, y: 720), control1: CGPoint(x: 1001.672, y: 480.787), control2: CGPoint(x: 1068, y: 595.672))
    case 2:
      p.move(to: CGPoint(x: 710, y: 460.7))
      p.addCurve(to: CGPoint(x: 736, y: 460.7), control1: CGPoint(x: 718.8, y: 459.4), control2: CGPoint(x: 727.3, y: 460.3))
      p.addCurve(to: CGPoint(x: 803, y: 473.3), control1: CGPoint(x: 759, y: 462.5), control2: CGPoint(x: 781, y: 466))
      p.addCurve(to: CGPoint(x: 833, y: 485.6), control1: CGPoint(x: 813.302, y: 476.621), control2: CGPoint(x: 823.332, y: 480.733))
      p.addCurve(to: CGPoint(x: 819, y: 528), control1: CGPoint(x: 850.5, y: 496.9), control2: CGPoint(x: 845.6, y: 529.1))
      p.addCurve(to: CGPoint(x: 787, y: 516.7), control1: CGPoint(x: 812.1, y: 527.7), control2: CGPoint(x: 795, y: 519.4))
      p.addCurve(to: CGPoint(x: 728, y: 506), control1: CGPoint(x: 767.902, y: 510.505), control2: CGPoint(x: 748.057, y: 506.906))
      p.addCurve(to: CGPoint(x: 570, y: 569), control1: CGPoint(x: 668.863, y: 504.11), control2: CGPoint(x: 611.612, y: 526.939))
      p.addCurve(to: CGPoint(x: 508, y: 709), control1: CGPoint(x: 532.598, y: 606.302), control2: CGPoint(x: 510.485, y: 656.235))
      p.addLine(to: CGPoint(x: 508, y: 729))
      p.addCurve(to: CGPoint(x: 519.7, y: 789), control1: CGPoint(x: 508.787, y: 749.483), control2: CGPoint(x: 512.733, y: 769.722))
      p.addCurve(to: CGPoint(x: 529.7, y: 817), control1: CGPoint(x: 522.2, y: 796.6), control2: CGPoint(x: 529, y: 810.5))
      p.addCurve(to: CGPoint(x: 511, y: 841.7), control1: CGPoint(x: 530.778, y: 828.826), control2: CGPoint(x: 522.675, y: 839.529))
      p.addCurve(to: CGPoint(x: 481, y: 818), control1: CGPoint(x: 493.1, y: 844.8), control2: CGPoint(x: 486.7, y: 831.8))
      p.addLine(to: CGPoint(x: 474.3, y: 800))
      p.addCurve(to: CGPoint(x: 470.3, y: 653), control1: CGPoint(x: 459.104, y: 752.362), control2: CGPoint(x: 457.717, y: 701.394))
      p.addCurve(to: CGPoint(x: 688, y: 462.3), control1: CGPoint(x: 496.926, y: 550.824), control2: CGPoint(x: 583.208, y: 475.243))
      p.addLine(to: CGPoint(x: 710, y: 460.7))
      p.closeSubpath()
    case 3:
      p.move(to: CGPoint(x: 931, y: 596.5))
      p.addCurve(to: CGPoint(x: 961, y: 621), control1: CGPoint(x: 949, y: 594.1), control2: CGPoint(x: 955.3, y: 606.7))
      p.addLine(to: CGPoint(x: 967.7, y: 639))
      p.addCurve(to: CGPoint(x: 971.6, y: 786), control1: CGPoint(x: 982.702, y: 686.674), control2: CGPoint(x: 984.053, y: 737.597))
      p.addCurve(to: CGPoint(x: 797, y: 967.3), control1: CGPoint(x: 948.827, y: 872.65), control2: CGPoint(x: 882.731, y: 941.281))
      p.addCurve(to: CGPoint(x: 705, y: 978), control1: CGPoint(x: 762.6, y: 977.7), control2: CGPoint(x: 740.3, y: 978.4))
      p.addLine(to: CGPoint(x: 695, y: 977))
      p.addCurve(to: CGPoint(x: 623, y: 959.6), control1: CGPoint(x: 670.3, y: 975.1), control2: CGPoint(x: 646, y: 968.8))
      p.addCurve(to: CGPoint(x: 598, y: 935), control1: CGPoint(x: 611, y: 954.8), control2: CGPoint(x: 598.7, y: 950.2))
      p.addCurve(to: CGPoint(x: 627, y: 911), control1: CGPoint(x: 597.3, y: 918.5), control2: CGPoint(x: 611, y: 906.7))
      p.addLine(to: CGPoint(x: 653, y: 921))
      p.addCurve(to: CGPoint(x: 713, y: 932), control1: CGPoint(x: 669.5, y: 926.6), control2: CGPoint(x: 695.7, y: 932))
      p.addLine(to: CGPoint(x: 728, y: 932))
      p.addCurve(to: CGPoint(x: 771, y: 925.9), control1: CGPoint(x: 740.7, y: 932), control2: CGPoint(x: 758.6, y: 929))
      p.addCurve(to: CGPoint(x: 882.7, y: 857), control1: CGPoint(x: 814.584, y: 915.326), control2: CGPoint(x: 853.692, y: 891.203))
      p.addCurve(to: CGPoint(x: 933, y: 739), control1: CGPoint(x: 910.8, y: 824.5), control2: CGPoint(x: 928.7, y: 781.8))
      p.addLine(to: CGPoint(x: 933, y: 731))
      p.addLine(to: CGPoint(x: 934, y: 721))
      p.addLine(to: CGPoint(x: 934, y: 717))
      p.addLine(to: CGPoint(x: 930.7, y: 684))
      p.addCurve(to: CGPoint(x: 920.6, y: 646), control1: CGPoint(x: 929, y: 673), control2: CGPoint(x: 924.5, y: 656.4))
      p.addCurve(to: CGPoint(x: 912, y: 618), control1: CGPoint(x: 916.7, y: 635.9), control2: CGPoint(x: 911.7, y: 629))
      p.addCurve(to: CGPoint(x: 931, y: 596.5), control1: CGPoint(x: 912.3, y: 606.2), control2: CGPoint(x: 920, y: 599.1))
      p.closeSubpath()
    case 4:
      p.move(to: CGPoint(x: 600, y: 720))
      p.addCurve(to: CGPoint(x: 660, y: 823.923), control1: CGPoint(x: 600, y: 762.872), control2: CGPoint(x: 622.872, y: 802.487))
      p.addCurve(to: CGPoint(x: 780, y: 823.923), control1: CGPoint(x: 697.128, y: 845.359), control2: CGPoint(x: 742.872, y: 845.359))
      p.addCurve(to: CGPoint(x: 840, y: 720), control1: CGPoint(x: 817.128, y: 802.487), control2: CGPoint(x: 840, y: 762.872))
      p.move(to: CGPoint(x: 600, y: 720))
      p.addCurve(to: CGPoint(x: 660, y: 616.077), control1: CGPoint(x: 600, y: 677.128), control2: CGPoint(x: 622.872, y: 637.513))
      p.addCurve(to: CGPoint(x: 780, y: 616.077), control1: CGPoint(x: 697.128, y: 594.641), control2: CGPoint(x: 742.872, y: 594.641))
      p.addCurve(to: CGPoint(x: 840, y: 720), control1: CGPoint(x: 817.128, y: 637.513), control2: CGPoint(x: 840, y: 677.128))
    case 5:
      p.move(to: CGPoint(x: 690, y: 720))
      p.addCurve(to: CGPoint(x: 705, y: 745.981), control1: CGPoint(x: 690, y: 730.718), control2: CGPoint(x: 695.718, y: 740.622))
      p.addCurve(to: CGPoint(x: 735, y: 745.981), control1: CGPoint(x: 714.282, y: 751.34), control2: CGPoint(x: 725.718, y: 751.34))
      p.addCurve(to: CGPoint(x: 750, y: 720), control1: CGPoint(x: 744.282, y: 740.622), control2: CGPoint(x: 750, y: 730.718))
      p.move(to: CGPoint(x: 690, y: 720))
      p.addCurve(to: CGPoint(x: 705, y: 694.019), control1: CGPoint(x: 690, y: 709.282), control2: CGPoint(x: 695.718, y: 699.378))
      p.addCurve(to: CGPoint(x: 735, y: 694.019), control1: CGPoint(x: 714.282, y: 688.66), control2: CGPoint(x: 725.718, y: 688.66))
      p.addCurve(to: CGPoint(x: 750, y: 720), control1: CGPoint(x: 744.282, y: 699.378), control2: CGPoint(x: 750, y: 709.282))
    default: break
    }
    // ContentScale.Crop：等比放大到盖满，居中，多出的部分由上层 clipped 裁掉
    let s = max(rect.width, rect.height) / 1440
    var scaled = p.applying(CGAffineTransform(scaleX: s, y: s))
    let dx = rect.minX + (rect.width - 1440 * s) / 2
    let dy = rect.minY + (rect.height - 1440 * s) / 2
    scaled = scaled.applying(CGAffineTransform(translationX: dx, y: dy))
    return scaled
  }
}
