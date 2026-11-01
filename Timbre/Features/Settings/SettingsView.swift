import Foundation
import SwiftUI
import UIKit

struct SettingsView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @EnvironmentObject private var player: PlaybackEngine

  @State private var showExtensions = false
  @State private var showSeekPicker = false
  @State private var showRewindPicker = false

  private let seekOptions = [10, 15, 20, 30, 45, 60]
  private let rewindOptions = [0, 3, 5, 10, 15, 30]

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        IconButton48(systemName: "xmark", label: "关闭") {
          app.showSettings = false
        }
        Text("偏好设置")
          .font(AppFont.titleLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer()
      }
      .frame(height: 64)

      ScrollView {
        VStack(spacing: 0) {
          M3ListItem(
            systemName: "books.vertical",
            title: "有声书文件夹",
            subtitle: "管理扫描有声书的文件夹",
            action: {
              app.showSettings = false
              app.showImporter = true
            }
          )

          M3ListItem(
            systemName: "puzzlepiece",
            title: "接口源",
            subtitle: "导入和管理 .jdr 接口源",
            action: { showExtensions = true }
          )

          M3ListItem(
            systemName: "square.grid.2x2",
            title: "使用网格布局"
          ) {
            Toggle("", isOn: $settings.settings.useGridLayout)
              .labelsHidden()
              .tint(AppColor.primary)
          }

          M3ListItem(
            systemName: "clock.arrow.circlepath",
            title: "跳过时间",
            subtitle: "\(settings.settings.seekSeconds) 秒",
            action: { showSeekPicker = true }
          )

          M3ListItem(
            systemName: "gobackward",
            title: "自动回退",
            subtitle: settings.settings.autoRewindSeconds == 0
              ? "关闭" : "\(settings.settings.autoRewindSeconds) 秒",
            action: { showRewindPicker = true }
          )

          M3ListItem(
            systemName: "circle.lefthalf.filled",
            title: "主题",
            subtitle: settings.settings.themeMode.title
          ) {
            Menu {
              ForEach(ThemeMode.allCases) { mode in
                Button(mode.title) { settings.settings.themeMode = mode }
              }
            } label: {
              Image(systemName: "chevron.right")
                .font(.system(size: 16))
                .foregroundColor(AppColor.onSurfaceVariant)
                .frame(width: 48, height: 48)
            }
          }

          M3ListItem(
            systemName: "chevron.left.forwardslash.chevron.right",
            title: "开源项目",
            subtitle: "GitHub · cq10086123/Timbre",
            action: {
              if let url = URL(string: "https://github.com/cq10086123/Timbre") {
                UIApplication.shared.open(url)
              }
            }
          )

          M3ListItem(
            systemName: "number",
            title: "软件版本",
            subtitle: appVersion
          ) {
            EmptyView()
          }

          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .fullScreenCover(isPresented: $showExtensions) {
      ExtensionsView()
        .environmentObject(app)
        .environmentObject(extensions)
    }
    .confirmationDialog("跳过时间", isPresented: $showSeekPicker, titleVisibility: .visible) {
      ForEach(seekOptions, id: \.self) { value in
        Button("\(value) 秒") { settings.settings.seekSeconds = value }
      }
      Button("取消", role: .cancel) {}
    }
    .confirmationDialog("自动回退", isPresented: $showRewindPicker, titleVisibility: .visible) {
      ForEach(rewindOptions, id: \.self) { value in
        Button(value == 0 ? "关闭" : "\(value) 秒") {
          settings.settings.autoRewindSeconds = value
        }
      }
      Button("取消", role: .cancel) {}
    }
  }

  private var appVersion: String {
    let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    return "\(version) (\(build))"
  }
}
