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
    case cacheMinDuration
    case cacheMaxBytes
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
            DurationText.afterPlayback(settings.settings.onlinePreloadTriggerSeconds),
            valueFont: AppFont.bodyLarge()
          ) { dialog = .preloadTrigger }

          valueRow(
            "预加载章节数",
            DurationText.atMost(settings.settings.onlinePreloadChapterCount),
            valueFont: AppFont.bodyLarge()
          ) { dialog = .preloadCount }

          valueRow(
            "章节预加载间隔",
            DurationText.interval(settings.settings.onlinePreloadIntervalSeconds),
            valueFont: AppFont.bodyLarge()
          ) { dialog = .preloadInterval }

          M3ListItem(
            systemName: nil,
            title: "播放后预加载",
            subtitle: "只在播放中按下方的设置预加载后续章节，进播放器不提前请求。本地与 WebDAV 书籍不受影响。"
          ) {
            M3Switch(isOn: $settings.settings.onlinePreloadEnabled)
          }

          M3ListItem(
            systemName: nil,
            title: "预加载时缓存到本地",
            subtitle: "预加载时不止解析地址，还把这集音频存进手机：听到时秒开，断网也能听。与「缓存书籍」共用同一份缓存。"
          ) {
            M3Switch(isOn: $settings.settings.onlinePreloadCacheEnabled)
          }

          if settings.settings.onlinePreloadCacheEnabled {
            valueRow(
              "缓存最短时长",
              DurationText.atLeast(settings.settings.onlinePreloadCacheMinSeconds),
              valueFont: AppFont.bodyLarge()
            ) { dialog = .cacheMinDuration }

            valueRow(
              "缓存空间上限",
              Format.bytes(settings.settings.onlinePreloadCacheMaxBytes),
              valueFont: AppFont.bodyLarge()
            ) { dialog = .cacheMaxBytes }

            M3ListItem(
              systemName: nil,
              title: "仅在 Wi-Fi 下缓存",
              subtitle: "移动数据下仍照旧只预解析地址，不下载音频文件。"
            ) {
              M3Switch(isOn: $settings.settings.onlinePreloadCacheWifiOnly)
            }
          }

          M3ListItem(
            systemName: nil,
            title: "蓝牙上一集/下一集切换章节",
            subtitle: "开启后，蓝牙与锁屏的上一集/下一集按钮切换到上/下一章；关闭则为快退/快进"
          ) {
            M3Switch(isOn: $settings.settings.btSkipChapter)
          }

          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    // 安卓这几个都是居中的 AlertDialog（TimeSettingDialog / OnlinePreloadSliderDialog），
    // 不是底部弹窗
    .overlay {
      if let which = dialog {
        SliderSettingDialog(
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
    }
  }

  /// 安卓 SeekTimeRow / AutoRewindRow / OnlinePreloadValueRow：标题 + 副标题取值，无图标。
  /// 前三者的取值是默认的 bodyMedium，预加载三项是 bodyLarge —— 安卓自己也不一致，照搬。
  private func valueRow(
    _ title: String,
    _ value: String,
    valueFont: Font = AppFont.bodyMedium(),
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 0) {
        VStack(alignment: .leading, spacing: 2) {
          Text(title)
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
          Text(value)
            .font(valueFont)
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

  private func title(for which: SliderDialog) -> String {
    switch which {
    case .seek: return "跳过时间"
    case .rewind: return "自动回退"
    case .preloadTrigger: return "预加载触发时间"
    case .preloadCount: return "预加载章节数"
    case .preloadInterval: return "章节预加载间隔"
    case .cacheMinDuration: return "缓存最短时长"
    case .cacheMaxBytes: return "缓存空间上限"
    }
  }

  /// 安卓只有 OnlinePreloadSliderDialog 带说明，TimeSettingDialog（跳过时间/自动回退）没有
  private func explanation(for which: SliderDialog) -> String? {
    switch which {
    case .seek, .rewind: return nil
    case .preloadTrigger: return "播放满该秒数后开始预加载。短于该时长的章节不触发预加载，切章后全部重置。"
    case .preloadCount: return "当前章之后最多预加载多少章的播放地址。"
    case .preloadInterval: return "同一次预加载中，两章之间的等待时间。"
    case .cacheMinDuration:
      return "只有不短于该时长的章节才在预加载时下载到本地；更短的预告、片头不占空间。时长未知时会先向服务器问一次。"
    case .cacheMaxBytes:
      return "预加载缓存占用的上限。超出后按最久没听过的顺序删除自动缓存，你手动「缓存书籍」的章节不会被删。"
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
    // 0 秒 = 不限；5 秒起才有意义，1 小时以上基本没有这种书
    case .cacheMinDuration: return 0...3600
    // 单位 MB，512MB – 8GB；确认时再量化到 256MB 的整倍数
    case .cacheMaxBytes: return 512...8192
    }
  }

  private func current(for which: SliderDialog) -> Int {
    switch which {
    case .seek: return settings.settings.seekSeconds
    case .rewind: return settings.settings.autoRewindSeconds
    case .preloadTrigger: return settings.settings.onlinePreloadTriggerSeconds
    case .preloadCount: return settings.settings.onlinePreloadChapterCount
    case .preloadInterval: return settings.settings.onlinePreloadIntervalSeconds
    case .cacheMinDuration: return settings.settings.onlinePreloadCacheMinSeconds
    case .cacheMaxBytes: return Int(settings.settings.onlinePreloadCacheMaxBytes / (1024 * 1024))
    }
  }

  private func value(for which: SliderDialog, _ raw: Double) -> String {
    let value = Int(raw.rounded())
    switch which {
    case .seek, .rewind: return DurationText.seconds(value)
    case .preloadTrigger: return DurationText.afterPlayback(value)
    case .preloadCount: return DurationText.atMost(value)
    case .preloadInterval: return DurationText.interval(value)
    case .cacheMinDuration: return DurationText.atLeast(value)
    case .cacheMaxBytes: return Format.bytes(Int64(value) * 1024 * 1024)
    }
  }

  private func apply(_ which: SliderDialog, _ value: Int) {
    switch which {
    case .seek: settings.settings.seekSeconds = value
    case .rewind: settings.settings.autoRewindSeconds = value
    case .preloadTrigger: settings.settings.onlinePreloadTriggerSeconds = value
    case .preloadCount: settings.settings.onlinePreloadChapterCount = value
    case .preloadInterval: settings.settings.onlinePreloadIntervalSeconds = value
    case .cacheMinDuration: settings.settings.onlinePreloadCacheMinSeconds = max(0, value)
    case .cacheMaxBytes:
      // 滑块分辨率只有 1MB，量化到 256MB，免得显示成「1.03 GB」这种数
      let mb = max(256, (value / 256) * 256)
      settings.settings.onlinePreloadCacheMaxBytes = Int64(mb) * 1024 * 1024
      // 上限调小以后立刻生效，不用等到下一次预加载
      let maxBytes = settings.settings.onlinePreloadCacheMaxBytes
      _ = Task.detached(priority: .utility) {
        OnlinePreloadIndex.evictIfNeeded(maxBytes: maxBytes)
      }
    }
    dialog = nil
  }
}

// MARK: - 通用滑块设置对话框（安卓 TimeSettingDialog / OnlinePreloadSliderDialog）

/// 居中 AlertDialog：标题 →（可选）说明 bodySmall → 取值 bodyLarge → 滑块 → 取消/确定。
/// 安卓这两个对话框都**没有**最小值/最大值标注，也没有把取值放大成主题色，
/// 之前那套底部弹窗的排版是照搬别的页面来的，对不上。
struct SliderSettingDialog: View {
  var title: String
  var explanation: String?
  var range: ClosedRange<Double>
  var format: (Double) -> String
  var initialValue: Double
  var onConfirm: (Double) -> Void
  var onDismiss: () -> Void

  @State private var value: Double?

  private var current: Double { value ?? initialValue }

  private var normalized: Double {
    let span = max(range.upperBound - range.lowerBound, 0.0001)
    return (min(max(current, range.lowerBound), range.upperBound) - range.lowerBound) / span
  }

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

        if let explanation, !explanation.isEmpty {
          Text(explanation)
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
          Color.clear.frame(height: 8)
        }

        Text(format(current))
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurfaceVariant)
        ExpressiveSlider(
          value: Binding(
            get: { normalized },
            set: { ratio in
              let span = range.upperBound - range.lowerBound
              value = (range.lowerBound + ratio * span).rounded()
            }
          ),
          onEditingChanged: { _ in }
        )

        Color.clear.frame(height: 8)
        HStack(spacing: 8) {
          Spacer(minLength: 0)
          Button(action: onDismiss) {
            Text("取消")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(height: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
          Button {
            onConfirm(current)
          } label: {
            Text("确定")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(height: 40)
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
