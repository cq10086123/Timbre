import SwiftUI

/// 播放器设置子页：对齐安卓 `features/settings/.../player/PlayerSettings.kt`。
/// 条目全部是「主标题 + 副标题」、无图标；数值项点开是滑块对话框。
struct PlayerSettingsView: View {
  var onBack: () -> Void

  @EnvironmentObject private var settings: SettingsStore

  @State private var dialog: SliderDialog?

  private enum SliderDialog: String, Identifiable {
    case seek
    case rewind
    case preloadTrigger
    case preloadCount
    case preloadInterval
    var id: String { rawValue }
  }

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        IconButton48(systemName: "chevron.left", label: "返回", action: onBack)
        Text("播放器设置")
          .font(AppFont.titleLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer()
      }
      .frame(height: 64)

      ScrollView {
        VStack(spacing: 0) {
          valueRow("跳过时间", DurationText.seconds(settings.settings.seekSeconds)) {
            dialog = .seek
          }

          valueRow("自动回退", DurationText.seconds(settings.settings.autoRewindSeconds)) {
            dialog = .rewind
          }

          valueRow(
            "预加载触发时间",
            DurationText.afterPlayback(settings.settings.onlinePreloadTriggerSeconds)
          ) { dialog = .preloadTrigger }

          valueRow(
            "预加载章节数",
            DurationText.atMost(settings.settings.onlinePreloadChapterCount)
          ) { dialog = .preloadCount }

          valueRow(
            "章节预加载间隔",
            DurationText.interval(settings.settings.onlinePreloadIntervalSeconds)
          ) { dialog = .preloadInterval }

          M3ListItem(
            systemName: nil,
            title: "蓝牙上一集/下一集切换章节",
            subtitle: "开启后，蓝牙与锁屏的上一集/下一集按钮切换到上/下一章；关闭则为快退/快进"
          ) {
            M3Switch(isOn: $settings.settings.btSkipChapter)
          }

          M3ListItem(
            systemName: nil,
            title: "播放后预加载",
            subtitle: "只在播放中按下方的设置预加载后续章节，进播放器不提前请求。本地与 WebDAV 书籍不受影响。"
          ) {
            M3Switch(isOn: $settings.settings.onlinePreloadEnabled)
          }

          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .sheet(item: $dialog) { which in
      sliderSheet(which)
    }
  }

  /// 安卓 OnlinePreloadValueRow / SeekTimeRow / AutoRewindRow：标题 + 右侧取值，无图标
  private func valueRow(
    _ title: String,
    _ value: String,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 0) {
        VStack(alignment: .leading, spacing: 2) {
          Text(title)
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
          Text(value)
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
        Spacer(minLength: 8)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 14)
      .frame(minHeight: 56)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  // MARK: - 滑块对话框

  private func sliderSheet(_ which: SliderDialog) -> some View {
    SliderSettingSheet(
      title: title(for: which),
      explanation: explanation(for: which),
      range: range(for: which),
      format: { value(for: which, $0) },
      initialValue: Double(current(for: which)),
      onConfirm: { newValue in
        apply(which, Int(newValue.rounded()))
      },
      onDismiss: { dialog = nil }
    )
  }

  private func title(for which: SliderDialog) -> String {
    switch which {
    case .seek: return "跳过时间"
    case .rewind: return "自动回退"
    case .preloadTrigger: return "预加载触发时间"
    case .preloadCount: return "预加载章节数"
    case .preloadInterval: return "章节预加载间隔"
    }
  }

  private func explanation(for which: SliderDialog) -> String {
    switch which {
    case .seek: return "快进 / 快退按钮每次跳过的秒数。"
    case .rewind: return "暂停后重新播放时，往回退多少秒以便接上前文。设为 0 关闭。"
    case .preloadTrigger: return "播放满该秒数后开始预加载。短于该时长的章节不触发预加载，切章后全部重置。"
    case .preloadCount: return "当前章之后最多预加载多少章的播放地址。"
    case .preloadInterval: return "同一次预加载中，两章之间的等待时间。"
    }
  }

  /// 范围与安卓一致：Seek 3–60、AutoRewind 0–20、预加载 5–300 / 1–10 / 1–600
  private func range(for which: SliderDialog) -> ClosedRange<Double> {
    switch which {
    case .seek: return 3...60
    case .rewind: return 0...20
    case .preloadTrigger: return 5...300
    case .preloadCount: return 1...10
    case .preloadInterval: return 1...600
    }
  }

  private func current(for which: SliderDialog) -> Int {
    switch which {
    case .seek: return settings.settings.seekSeconds
    case .rewind: return settings.settings.autoRewindSeconds
    case .preloadTrigger: return settings.settings.onlinePreloadTriggerSeconds
    case .preloadCount: return settings.settings.onlinePreloadChapterCount
    case .preloadInterval: return settings.settings.onlinePreloadIntervalSeconds
    }
  }

  private func value(for which: SliderDialog, _ raw: Double) -> String {
    let value = Int(raw.rounded())
    switch which {
    case .seek, .rewind: return DurationText.seconds(value)
    case .preloadTrigger: return DurationText.afterPlayback(value)
    case .preloadCount: return DurationText.atMost(value)
    case .preloadInterval: return DurationText.interval(value)
    }
  }

  private func apply(_ which: SliderDialog, _ value: Int) {
    switch which {
    case .seek: settings.settings.seekSeconds = value
    case .rewind: settings.settings.autoRewindSeconds = value
    case .preloadTrigger: settings.settings.onlinePreloadTriggerSeconds = value
    case .preloadCount: settings.settings.onlinePreloadChapterCount = value
    case .preloadInterval: settings.settings.onlinePreloadIntervalSeconds = value
    }
    dialog = nil
  }
}

// MARK: - 通用滑块设置对话框（安卓 OnlinePreloadSliderDialog / TimeSettingDialog）

struct SliderSettingSheet: View {
  var title: String
  var explanation: String
  var range: ClosedRange<Double>
  var format: (Double) -> String
  var initialValue: Double
  var onConfirm: (Double) -> Void
  var onDismiss: () -> Void

  @State private var value: Double?

  private var current: Double { value ?? initialValue }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(title)
        .font(AppFont.headlineSmall())
        .foregroundColor(AppColor.onSurface)
        .padding(.bottom, 12)

      Text(explanation)
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)

      Text(format(current))
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.primary)
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.top, 20)

      Slider(
        value: Binding(
          get: { current },
          set: { value = $0.rounded() }
        ),
        in: range,
        step: 1
      )
      .tint(AppColor.primary)
      .padding(.top, 4)

      HStack {
        Text(format(range.lowerBound))
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
        Spacer()
        Text(format(range.upperBound))
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
      }

      Spacer(minLength: 16)

      HStack(spacing: 8) {
        Spacer()
        Button("取消") { onDismiss() }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.horizontal, 12)
          .frame(height: 44)
        Button("确定") { onConfirm(current) }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 12)
          .frame(height: 44)
      }
    }
    .padding(24)
    .presentationDetents([.height(340)])
    .background(AppColor.surfaceContainerHigh.ignoresSafeArea())
  }
}
