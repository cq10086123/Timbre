import SwiftUI

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
        Image(systemName: playing ? "pause.fill" : "play.fill")
          .font(.system(size: iconSize * 0.92, weight: .semibold))
          .foregroundColor(AppColor.onPrimaryContainer)
      }
      .frame(width: size, height: size)
      .animation(.easeInOut(duration: 0.25), value: corner)
      .contentShape(RoundedRectangle(cornerRadius: corner, style: .continuous))
    }
    .buttonStyle(.plain)
    .accessibilityLabel(playing ? "暂停" : "播放")
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
struct MarqueeText: View {
  var text: String
  var font: Font = AppFont.headlineMedium()
  var color: Color = AppColor.onSurface
  var speed: CGFloat = 34  // pt / 秒

  @State private var textWidth: CGFloat = 0
  @State private var offset: CGFloat = 0

  // 有 private 存储属性时自动生成的 memberwise init 也是 private，
  // 跨文件就构造不出来 —— 这里显式给一个 internal init。
  init(
    text: String,
    font: Font = AppFont.headlineMedium(),
    color: Color = AppColor.onSurface,
    speed: CGFloat = 34
  ) {
    self.text = text
    self.font = font
    self.color = color
    self.speed = speed
  }

  var body: some View {
    GeometryReader { geo in
      let container = geo.size.width
      let overflow = max(0, textWidth - container)

      Text(text)
        .font(font)
        .foregroundColor(color)
        .lineLimit(1)
        .fixedSize(horizontal: true, vertical: false)
        .background(
          GeometryReader { proxy in
            Color.clear.preference(key: MarqueeWidthKey.self, value: proxy.size.width)
          }
        )
        .offset(x: overflow > 0 ? offset : 0)
        .frame(width: container, alignment: .leading)
        .onPreferenceChange(MarqueeWidthKey.self) { width in
          if abs(width - textWidth) > 0.5 { textWidth = width }
        }
        .task(id: "\(text)|\(Int(container))|\(Int(textWidth))") {
          // 内容或宽度变了就重来一遍，避免用旧的位移量
          offset = 0
          guard overflow > 0, container > 1 else { return }
          try? await Task.sleep(nanoseconds: 1_500_000_000)
          while !Task.isCancelled {
            let duration = Double(overflow) / Double(max(speed, 1)) + 0.4
            withAnimation(.linear(duration: duration)) { offset = -overflow }
            try? await Task.sleep(nanoseconds: UInt64((duration + 1.6) * 1_000_000_000))
            guard !Task.isCancelled else { return }
            withAnimation(.easeOut(duration: 0.25)) { offset = 0 }
            try? await Task.sleep(nanoseconds: 500_000_000)
            guard !Task.isCancelled else { return }
          }
        }
    }
    .clipped()
  }
}

private struct MarqueeWidthKey: PreferenceKey {
  static var defaultValue: CGFloat { 0 }
  static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
    value = max(value, nextValue())
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
          Image(systemName: checked ? "checkmark.square.fill" : "square")
            .font(.system(size: 18))
            .foregroundColor(checked ? AppColor.primary : AppColor.onSurfaceVariant)
        }
      }
      .padding(.horizontal, 12)
      .frame(height: 48)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
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
