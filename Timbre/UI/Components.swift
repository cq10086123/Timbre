import SwiftUI
import UIKit

// MARK: - 播放键（Material3 FAB，播放中变圆角方块）

struct M3PlayButton: View {
  var playing: Bool
  var size: CGFloat = 56
  var iconSize: CGFloat = 24
  var action: () -> Void

  private var corner: CGFloat { playing ? 16 : size / 2 }

  var body: some View {
    Button(action: action) {
      ZStack {
        RoundedRectangle(cornerRadius: corner, style: .continuous)
          .fill(AppColor.primaryContainer)
        // 安卓用 avd_pause_to_play 把「两条横杠」旋转 270° 并变形为三角形，
        // 静止的两端就是这两枚 Material Symbols 图形
        AndroidIcon(
          glyph: playing ? .pause : .playArrow,
          size: iconSize,
          color: AppColor.onPrimaryContainer
        )
      }
      .frame(width: size, height: size)
      .animation(.easeInOut(duration: 0.25), value: corner)
      .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
    .buttonStyle(.plain)
    .accessibilityLabel(playing ? "暂停" : "播放")
  }
}

// MARK: - 安卓矢量图标按钮（48dp 触摸区）

/// 与 `IconButton48` 等价，但画的是安卓那套 `ImageVector`，
/// 不用 SF Symbols —— 图形与安卓逐点一致。
struct GlyphButton: View {
  var glyph: AndroidGlyph
  var iconSize: CGFloat = 24
  var label: String
  var tint: Color = AppColor.onSurfaceVariant
  var mirrored: Bool = false
  var touchSize: CGFloat = 48
  var action: () -> Void

  var body: some View {
    Button(action: action) {
      AndroidIcon(glyph: glyph, size: iconSize, color: tint, mirrored: mirrored)
        .frame(width: touchSize, height: touchSize)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .accessibilityLabel(label)
  }
}

// MARK: - Material3 不定进度圈（88dp / 描边 3dp）

/// 安卓播放页的加载圈是 `CircularProgressIndicator(size = 88.dp, strokeWidth = 3.dp)`。
/// 它只是一个画出来的弧，**不消费点击**；SF 的 `ProgressView` 会吃掉手势，
/// 叠在播放键上就变成「要点两次才能播」。
struct M3CircularProgress: View {
  var size: CGFloat = 88
  var stroke: CGFloat = 3

  @State private var spin = false

  var body: some View {
    Circle()
      .trim(from: 0, to: 0.78)
      .stroke(
        AppColor.primary,
        style: StrokeStyle(lineWidth: stroke, lineCap: .round)
      )
      .frame(width: size - stroke, height: size - stroke)
      .rotationEffect(.degrees(spin ? 360 : 0))
      .frame(width: size, height: size)
      .onAppear {
        withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) {
          spin = true
        }
      }
      // 关键：装饰性加载圈绝不能挡在播放键上面抢手势
      .allowsHitTesting(false)
      .accessibilityHidden(true)
  }
}

// MARK: - 48dp 图标按钮

struct IconButton48: View {
  var systemName: String
  var iconSize: CGFloat = 24
  var label: String
  var tint: Color = AppColor.onSurfaceVariant
  var action: () -> Void

  var body: some View {
    Button(action: action) {
      Image(systemName: systemName)
        .font(.system(size: iconSize, weight: .regular))
        .foregroundColor(tint)
        .frame(width: 48, height: 48)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .accessibilityLabel(label)
  }
}

// MARK: - 列表项（ListItem：左侧 24dp 图标 + 主副文案）

struct M3ListItem<Trailing: View>: View {
  var systemName: String?
  var title: String
  var subtitle: String?
  var tint: Color = AppColor.onSurface
  var trailing: Trailing
  var action: (() -> Void)?

  init(
    systemName: String? = nil,
    title: String,
    subtitle: String? = nil,
    tint: Color = AppColor.onSurface,
    action: (() -> Void)? = nil,
    @ViewBuilder trailing: () -> Trailing
  ) {
    self.systemName = systemName
    self.title = title
    self.subtitle = subtitle
    self.tint = tint
    self.action = action
    self.trailing = trailing()
  }

  var body: some View {
    let row = HStack(spacing: 0) {
      if let systemName {
        Image(systemName: systemName)
          .font(.system(size: 24))
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(width: 24, height: 24)
          .padding(.trailing, 16)
      }
      VStack(alignment: .leading, spacing: 0) {
        Text(title)
          .font(AppFont.bodyLarge())
          .foregroundColor(tint)
          .lineLimit(2)
        if let subtitle {
          Text(subtitle)
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
            .lineLimit(3)
        }
      }
      Spacer(minLength: 8)
      trailing
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 14)
    .frame(minHeight: 56)
    .contentShape(Rectangle())

    if let action {
      Button(action: action) { row }.buttonStyle(.plain)
    } else {
      row
    }
  }
}

extension M3ListItem where Trailing == EmptyView {
  init(
    systemName: String? = nil,
    title: String,
    subtitle: String? = nil,
    tint: Color = AppColor.onSurface,
    action: (() -> Void)? = nil
  ) {
    self.init(
      systemName: systemName,
      title: title,
      subtitle: subtitle,
      tint: tint,
      action: action
    ) {
      EmptyView()
    }
  }
}

// MARK: - 分组标题（headlineSmall 24sp）

struct SectionHeader: View {
  var title: String

  var body: some View {
    Text(title)
      .font(AppFont.headlineSmall())
      .foregroundColor(AppColor.onSurface)
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(.horizontal, 8)
      .padding(.vertical, 8)
  }
}

// MARK: - 进度条（4dp）

struct M3ProgressBar: View {
  var fraction: Double
  var track: Color = AppColor.surfaceVariant
  var fill: Color = AppColor.primary
  var height: CGFloat = 4
  var rounded: Bool = true

  var body: some View {
    GeometryReader { geo in
      ZStack(alignment: .leading) {
        Rectangle().fill(track)
        Rectangle()
          .fill(fill)
          .frame(width: max(0, min(1, fraction)) * geo.size.width)
      }
      .clipShape(
        RoundedRectangle(cornerRadius: rounded ? height / 2 : 0, style: .continuous)
      )
    }
    .frame(height: height)
  }
}

// MARK: - 底部抽屉容器（原生 sheet，只有一个展开态）

extension View {
  func m3Sheet<C: View>(isPresented: Binding<Bool>, @ViewBuilder content: @escaping () -> C)
    -> some View
  {
    sheet(isPresented: isPresented) {
      content()
        .presentationDragIndicator(.visible)
    }
  }
}

// MARK: - 时间胶囊（睡眠定时器角标）

struct TimeBadge: View {
  var text: String

  var body: some View {
    Text(text)
      .font(AppFont.bodyLarge())
      .foregroundColor(.white)
      .padding(.horizontal, 20)
      .padding(.vertical, 16)
      .background(
        RoundedRectangle(cornerRadius: 20, style: .continuous)
          .fill(Color.black.opacity(0.49))
      )
  }
}

// MARK: - 跑马灯文字（对齐安卓 AppBarTitle 的 basicMarquee）

/// 单行文字，放得下就静止，放不下就左右来回滚动。
/// 安卓播放页标题用的是 `Modifier.basicMarquee()`，直接截断会丢信息，
/// 所以这里按同样的行为实现：停 1.5s → 滚到底 → 停 1.5s → 回到开头。
///
/// 实现换成 UIKit + CAKeyframeAnimation 的原因（播放中「一卡一卡」）：
/// 播放进度每 0.5 秒发布一次，播放页整棵 body 跟着重算 —— 纯 SwiftUI 版本靠
/// `GeometryReader` 量宽 + `.task(id:)` 驱动 `withAnimation(offset:)`，
/// 父视图一重算就有机会把滚动状态拉回 0（表现为书名突然跳回开头再滚），
/// 动画本身也在主线程上跟 body 重算抢时间。
/// CAKeyframeAnimation 跑在渲染服务器上，主线程再忙也不影响它，
/// 只有「文字 / 字号 / 可用宽度 / 速度」真的变了才重装一次动画。
struct MarqueeText: UIViewRepresentable {
  var text: String
  var size: CGFloat = 28
  var weight: UIFont.Weight = .regular
  var color: Color = AppColor.onSurface
  /// pt / 秒
  var speed: CGFloat = 34

  func makeUIView(context: Context) -> MarqueeLabelView {
    MarqueeLabelView()
  }

  func updateUIView(_ view: MarqueeLabelView, context: Context) {
    view.apply(text: text, size: size, weight: weight, color: UIColor(color), speed: speed)
  }

  /// 不自带理想宽度：宽度由外部 `.frame(...)` 决定（书名比屏幕宽时也要能截住）
  func sizeThatFits(
    _ proposal: ProposedViewSize,
    uiView: MarqueeLabelView,
    context: Context
  ) -> CGSize? {
    CGSize(
      width: proposal.width ?? max(uiView.bounds.width, 1),
      height: proposal.height ?? uiView.intrinsicContentSize.height
    )
  }
}

final class MarqueeLabelView: UIView {
  private let label = UILabel()
  private var speed: CGFloat = 34
  /// 上一次装动画时的「内容指纹」：没变就别重装，否则播放页每秒重绘两次会把滚动打断
  private var signature = ""

  override init(frame: CGRect) {
    super.init(frame: frame)
    backgroundColor = .clear
    isOpaque = false
    clipsToBounds = true
    label.numberOfLines = 1
    label.lineBreakMode = .byClipping
    label.backgroundColor = .clear
    addSubview(label)
  }

  required init?(coder: NSCoder) {
    fatalError("init(coder:) has not been implemented")
  }

  /// 高度按字体行高报，宽度交给外部约束（书名再长也不该撑宽父容器）
  override var intrinsicContentSize: CGSize {
    CGSize(
      width: UIView.noIntrinsicMetric,
      height: ceil(label.font?.lineHeight ?? 20)
    )
  }

  func apply(text: String, size: CGFloat, weight: UIFont.Weight, color: UIColor, speed: CGFloat) {
    let font = UIFont.systemFont(ofSize: size, weight: weight)
    if label.text != text { label.text = text }
    if label.font != font {
      label.font = font
      invalidateIntrinsicContentSize()
    }
    if label.textColor != color { label.textColor = color }
    self.speed = speed
    setNeedsLayout()
  }

  override func layoutSubviews() {
    super.layoutSubviews()
    guard bounds.width > 1, let font = label.font, let text = label.text else { return }

    let textWidth = ceil((text as NSString).size(withAttributes: [.font: font]).width) + 2
    // label 的 frame 变化会带隐式动画（滚动到一半改宽度会看到文字滑一下），关掉
    CATransaction.begin()
    CATransaction.setDisableActions(true)
    label.frame = CGRect(x: 0, y: 0, width: textWidth, height: bounds.height)
    CATransaction.commit()

    let next = "\(text)|\(font.fontName)|\(Int(font.pointSize))"
      + "|\(Int(bounds.width.rounded()))|\(Int(speed))"
    guard next != signature else { return }
    signature = next
    startScrolling(textWidth: textWidth)
  }

  private func startScrolling(textWidth: CGFloat) {
    label.layer.removeAnimation(forKey: "marquee")
    label.layer.transform = CATransform3DIdentity
    let overflow = textWidth - bounds.width
    guard overflow > 2 else { return }

    let scroll = Double(overflow) / Double(max(speed, 1))
    let hold = 1.5
    let total = hold + scroll + hold
    let curve = CAMediaTimingFunction(name: .linear)

    let animation = CAKeyframeAnimation(keyPath: "transform.translation.x")
    // 停 → 滚到底 → 停；循环回到第 0 帧时自然跳回开头，不需要回滚动画
    animation.values = [CGFloat(0), 0, -overflow, -overflow]
    animation.keyTimes = [
      NSNumber(value: 0),
      NSNumber(value: hold / total),
      NSNumber(value: (hold + scroll) / total),
      NSNumber(value: 1),
    ]
    animation.timingFunctions = [curve, curve, curve]
    animation.duration = total
    animation.repeatCount = .infinity
    animation.isRemovedOnCompletion = false
    label.layer.add(animation, forKey: "marquee")
  }
}

// MARK: - M3 居中对话框（对齐安卓 AlertDialog）

/// 安卓那边这三个都是 `AlertDialog`：屏幕居中、圆角 28dp、背景 surfaceContainerHigh。
/// iOS 的 `.alert` 放不了滑块，`.sheet` 又是底部弹窗，形态对不上，所以自己搭一个。
struct M3AlertDialog<Content: View>: View {
  var title: String?
  /// 底部右侧的文字按钮（安卓 AlertDialog 的 confirmButton）。
  /// 「播放出错」用它放「知道了」；倍速/音量提升/跳过片头片尾在安卓那边
  /// `confirmButton = {}`，所以留空即可 —— 靠点遮罩关闭。
  var actionTitle: String? = nil
  var onAction: (() -> Void)? = nil
  var onDismiss: () -> Void
  @ViewBuilder var content: () -> Content

  var body: some View {
    ZStack {
      AppColor.scrim
        .ignoresSafeArea()
        .onTapGesture { onDismiss() }

      VStack(alignment: .leading, spacing: 0) {
        if let title, !title.isEmpty {
          Text(title)
            .font(AppFont.headlineSmall())
            .foregroundColor(AppColor.onSurface)
            .fixedSize(horizontal: false, vertical: true)
          Color.clear.frame(height: 16)
        }
        content()
        if let actionTitle, !actionTitle.isEmpty {
          Color.clear.frame(height: 8)
          HStack(spacing: 0) {
            Spacer(minLength: 0)
            Button {
              if let onAction { onAction() } else { onDismiss() }
            } label: {
              Text(actionTitle)
                .font(AppFont.labelLarge())
                .foregroundColor(AppColor.primary)
                .padding(.horizontal, 12)
                .frame(minWidth: 64, minHeight: 40)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
          }
        }
      }
      .padding(24)
      .frame(maxWidth: 320, alignment: .leading)
      .background(
        RoundedRectangle(cornerRadius: 28, style: .continuous)
          .fill(AppColor.surfaceContainerHigh)
      )
      .padding(.horizontal, 24)
    }
  }
}

// MARK: - M3 确认对话框（标题 + 正文 + 取消 / 危险动作）

/// 「删除有声书」这类二次确认。
///
/// 不用系统 `.confirmationDialog` / `.alert` 的原因（用户报过「删不掉，重启又能删」）：
/// 长按抽屉刚 dismiss 的那一刻去弹系统弹窗，UIKit 会把这次呈现请求丢掉，
/// 而 SwiftUI 的状态（`deleteTarget`）已经置上了 —— 绑定 getter 恒为 true，
/// 之后对同一本书再怎么点都不会有状态变化，于是整个会话里都删不掉，
/// 只有重启 App（@State 重置）才恢复。自己画的遮罩层不经过呈现机制，不存在这个问题。
struct M3ConfirmDialog: View {
  var title: String
  var message: String
  var confirmTitle: String = "确定"
  var dismissTitle: String = "取消"
  /// 危险动作（删除等）用 error 色文字
  var destructive: Bool = true
  var onConfirm: () -> Void
  var onDismiss: () -> Void

  var body: some View {
    ZStack {
      AppColor.scrim
        .ignoresSafeArea()
        .onTapGesture { onDismiss() }

      VStack(alignment: .leading, spacing: 0) {
        Text(title)
          .font(AppFont.headlineSmall())
          .foregroundColor(AppColor.onSurface)
          .fixedSize(horizontal: false, vertical: true)
        Color.clear.frame(height: 16)
        Text(message)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .fixedSize(horizontal: false, vertical: true)
        Color.clear.frame(height: 20)
        HStack(spacing: 8) {
          Spacer(minLength: 0)
          Button(action: onDismiss) {
            Text(dismissTitle)
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(minWidth: 64, minHeight: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)

          Button(action: onConfirm) {
            Text(confirmTitle)
              .font(AppFont.labelLarge())
              .foregroundColor(destructive ? AppColor.error : AppColor.onPrimary)
              .padding(.horizontal, 16)
              .frame(minWidth: 64, minHeight: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
        }
      }
      .padding(24)
      .frame(maxWidth: 320, alignment: .leading)
      .background(
        RoundedRectangle(cornerRadius: 28, style: .continuous)
          .fill(AppColor.surfaceContainerHigh)
      )
      .padding(.horizontal, 24)
    }
  }
}

// MARK: - M3 下拉菜单（对齐安卓 DropdownMenu）

/// 安卓播放页的「更多」是 `DropdownMenu`：surfaceContainer 底、圆角 4、
/// 单项 48 高、首项 trailing 挂一个 Checkbox。SwiftUI 的 `Menu` 换不了
/// 行尾图形，所以按 M3 规格自己画一个，用全屏透明层负责点外部关闭。
struct M3DropdownMenu<Content: View>: View {
  var onDismiss: () -> Void
  @ViewBuilder var content: () -> Content

  var body: some View {
    ZStack(alignment: .topTrailing) {
      Color.clear
        .contentShape(Rectangle())
        .ignoresSafeArea()
        .onTapGesture { onDismiss() }

      VStack(alignment: .leading, spacing: 0) {
        content()
      }
      .padding(.vertical, 8)
      .frame(minWidth: 168, alignment: .leading)
      // 必须 fixedSize：ZStack 会把「整屏」的尺寸提案传给下面，
      // 而每个菜单项的 HStack 里有 Spacer（贪婪），于是面板会被撑成整屏宽 ——
      // 表现就是「右上一块灰色把上半屏全占了」。fixedSize 让它按内容取宽。
      .fixedSize(horizontal: true, vertical: false)
      .background(
        RoundedRectangle(cornerRadius: 4, style: .continuous)
          .fill(AppColor.surfaceContainer)
      )
      .shadow(color: Color.black.opacity(0.18), radius: 6, y: 2)
    }
  }
}

/// DropdownMenuItem：高 48、左右 12、正文 bodyLarge；
/// `checked` 非 nil 时在行尾画 M3 Checkbox。
struct M3DropdownItem: View {
  var title: String
  var checked: Bool? = nil
  var action: () -> Void

  var body: some View {
    Button(action: action) {
      HStack(spacing: 0) {
        Text(title)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(1)
        Spacer(minLength: 12)
        if let checked {
          M3Checkbox(checked: checked)
        }
      }
      .padding(.horizontal, 12)
      .frame(height: 48)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }
}

// MARK: - M3 复选框（18dp 方框 / 2dp 圆角 / 勾选时 primary 实心）

/// 安卓 DropdownMenuItem 行尾那个三态方框就是 Material3 的 `Checkbox`，
/// SF 里没有对应物（`checkmark.square.fill` 是圆角更大的另一套视觉），所以自己画。
struct M3Checkbox: View {
  var checked: Bool

  var body: some View {
    ZStack {
      RoundedRectangle(cornerRadius: 2, style: .continuous)
        .fill(checked ? AppColor.primary : Color.clear)
      RoundedRectangle(cornerRadius: 2, style: .continuous)
        .stroke(checked ? Color.clear : AppColor.onSurfaceVariant, lineWidth: 2)
      if checked {
        AndroidIcon(glyph: .check, size: 14, color: AppColor.onPrimary)
      }
    }
    .frame(width: 18, height: 18)
  }
}

// MARK: - M3 输入对话框（对齐安卓 AddBookmarkDialog / EditBookmarkDialog）

/// 标题 + OutlinedTextField + 右下「取消 / 设置」。
/// 安卓这两个对话框一进来就 requestFocus，所以标签恒为浮动态。
struct M3TextFieldDialog: View {
  var title: String
  var label: String
  @Binding var text: String
  var confirmTitle: String = "设置"
  var dismissTitle: String = "取消"
  var onConfirm: () -> Void
  var onDismiss: () -> Void

  @FocusState private var focused: Bool

  var body: some View {
    ZStack {
      AppColor.scrim
        .ignoresSafeArea()
        .onTapGesture { onDismiss() }

      VStack(alignment: .leading, spacing: 0) {
        Text(title)
          .font(AppFont.headlineSmall())
          .foregroundColor(AppColor.onSurface)
        Color.clear.frame(height: 20)

        // OutlinedTextField：高 56、圆角 4、聚焦时描边变 primary
        ZStack(alignment: .topLeading) {
          RoundedRectangle(cornerRadius: 4, style: .continuous)
            .stroke(
              focused ? AppColor.primary : AppColor.outline,
              lineWidth: focused ? 2 : 1
            )

          TextField("", text: $text)
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
            .focused($focused)
            .padding(.horizontal, 16)
            .frame(height: 56)

          Text(label)
            .font(AppFont.bodySmall())
            .foregroundColor(focused ? AppColor.primary : AppColor.onSurfaceVariant)
            .padding(.horizontal, 4)
            .background(AppColor.surfaceContainerHigh)
            .offset(x: 12, y: -8)
        }
        .frame(height: 56)

        Color.clear.frame(height: 20)

        HStack(spacing: 8) {
          Spacer(minLength: 0)
          Button(action: onDismiss) {
            Text(dismissTitle)
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(height: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)

          Button(action: onConfirm) {
            Text(confirmTitle)
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.onPrimary)
              .padding(.horizontal, 20)
              .frame(height: 40)
              .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                  .fill(AppColor.primary)
              )
          }
          .buttonStyle(.plain)
        }
      }
      .padding(24)
      .frame(maxWidth: 320, alignment: .leading)
      .background(
        RoundedRectangle(cornerRadius: 28, style: .continuous)
          .fill(AppColor.surfaceContainerHigh)
      )
      .padding(.horizontal, 24)
      .onAppear { focused = true }
    }
  }
}
