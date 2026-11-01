import Foundation
import SwiftUI

struct SettingsView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.dismiss) private var dismiss

  @State private var storageText = "计算中…"
  @State private var showExtensions = false
  @State private var showLicense = false

  private let seekOptions = [10, 15, 30, 45, 60]
  private let rewindOptions = [0, 3, 5, 10, 15]

  var body: some View {
    NavigationStack {
      Form {
        Section("外观") {
          Picker("主题", selection: $settings.settings.themeMode) {
            ForEach(ThemeMode.allCases) { mode in
              Text(mode.title).tag(mode)
            }
          }
          .pickerStyle(.segmented)
          .listRowBackground(AppColor.surface)

          Toggle("使用网格布局", isOn: $settings.settings.useGridLayout)
            .listRowBackground(AppColor.surface)
        }

        Section("播放") {
          Picker("跳过时间", selection: $settings.settings.seekSeconds) {
            ForEach(seekOptions, id: \.self) { value in
              Text("\(value) 秒").tag(value)
            }
          }
          .listRowBackground(AppColor.surface)

          Picker("暂停后自动回退", selection: $settings.settings.autoRewindSeconds) {
            ForEach(rewindOptions, id: \.self) { value in
              Text(value == 0 ? "关闭" : "\(value) 秒").tag(value)
            }
          }
          .listRowBackground(AppColor.surface)

          HStack {
            Text("新书默认倍速")
            Spacer()
            Text(Format.speed(settings.settings.defaultSpeed))
              .foregroundColor(AppColor.textSecondary)
              .monospacedDigit()
          }
          .listRowBackground(AppColor.surface)

          Slider(
            value: $settings.settings.defaultSpeed,
            in: 0.5...3.0,
            step: 0.05
          )
          .tint(AppColor.accent)
          .listRowBackground(AppColor.surface)

          Picker("新书默认跳过片头", selection: $settings.settings.defaultSkipIntro) {
            ForEach([0, 3, 5, 10, 15, 30], id: \.self) { value in
              Text(value == 0 ? "关闭" : "\(value) 秒").tag(value)
            }
          }
          .listRowBackground(AppColor.surface)

          Picker("新书默认跳过片尾", selection: $settings.settings.defaultSkipOutro) {
            ForEach([0, 5, 10, 15, 30], id: \.self) { value in
              Text(value == 0 ? "关闭" : "\(value) 秒").tag(value)
            }
          }
          .listRowBackground(AppColor.surface)

          Toggle("睡眠定时器渐弱淡出", isOn: $settings.settings.sleepFadeOut)
            .listRowBackground(AppColor.surface)
        }

        Section("接口源") {
          Button {
            showExtensions = true
          } label: {
            HStack {
              Text("管理接口源")
                .foregroundColor(AppColor.textPrimary)
              Spacer()
              Text("\(extensions.extensions.count) 个")
                .foregroundColor(AppColor.textSecondary)
              Image(systemName: "chevron.right")
                .font(.system(size: 12, weight: .semibold))
                .foregroundColor(AppColor.textSecondary)
            }
          }
          .listRowBackground(AppColor.surface)
        }

        Section("存储") {
          HStack {
            Text("已占用空间")
            Spacer()
            Text(storageText)
              .foregroundColor(AppColor.textSecondary)
          }
          .listRowBackground(AppColor.surface)

          Button {
            CoverStore.clear()
            app.showToast("封面缓存已清理")
          } label: {
            Text("清理封面缓存")
              .foregroundColor(AppColor.accent)
          }
          .listRowBackground(AppColor.surface)
        }

        Section("关于") {
          HStack {
            Text("版本")
            Spacer()
            Text(appVersion)
              .foregroundColor(AppColor.textSecondary)
          }
          .listRowBackground(AppColor.surface)

          Button {
            showLicense = true
          } label: {
            Text("开源许可（GPLv3）")
              .foregroundColor(AppColor.accent)
          }
          .listRowBackground(AppColor.surface)

          HStack {
            Text("书籍数量")
            Spacer()
            Text("\(library.books.count) 本")
              .foregroundColor(AppColor.textSecondary)
          }
          .listRowBackground(AppColor.surface)
        }
      }
      .scrollContentBackground(.hidden)
      .background(AppColor.background)
      .navigationTitle("设置")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("完成") { dismiss() }
        }
      }
      .navigationDestination(isPresented: $showExtensions) {
        ExtensionsView()
          .environmentObject(app)
          .environmentObject(extensions)
          .environmentObject(settings)
      }
      .sheet(isPresented: $showLicense) {
        LicenseView()
      }
      .task {
        storageText = await storageDescription()
      }
    }
  }

  private var appVersion: String {
    let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    return "\(version) (\(build))"
  }

  private func storageDescription() async -> String {
    let documents = AppPaths.documents
    var total: Int64 = 0
    if let enumerator = FileManager.default.enumerator(
      at: documents,
      includingPropertiesForKeys: [.fileSizeKey],
      options: [.skipsHiddenFiles]
    ) {
      for case let url as URL in enumerator {
        if let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize {
          total += Int64(size)
        }
      }
    }
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    return formatter.string(fromByteCount: total)
  }
}

struct LicenseView: View {
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 14) {
          Text("Timbre iOS 版")
            .font(.headline)
          Text(
            """
            本项目是 Android 版 Timbre（听书）的 iOS 独立实现，界面与功能保持一致的思路，
            播放内核改用苹果原生的 AVPlayer，并支持免签 IPA 安装。

            原项目以 GPLv3 协议开源，本项目同样遵循 GPLv3。
            """
          )
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(18)
      }
      .background(AppColor.background)
      .navigationTitle("开源许可")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .confirmationAction) {
          Button("完成") { dismiss() }
        }
      }
    }
  }
}
