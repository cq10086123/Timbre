import Foundation
import SwiftUI
import UIKit

/// 偏好设置主页：条目顺序、标题、副标题、开关全部对齐安卓
/// `features/settings/.../views/Settings.kt`。
struct SettingsView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @EnvironmentObject private var webdav: WebDavStore
  // 这里以前挂着一个没用的 @EnvironmentObject var player ——
  // 它让整个设置页跟着播放进度每 0.5 秒重建一次，删掉（本文件根本没读它）

  @State private var showExtensions = false
  @State private var showPlayerSettings = false
  @State private var showThemeDialog = false
  @State private var showColorSchemeDialog = false
  @State private var showFolderSheet = false
  @State private var showWebDavSheet = false
  @State private var showDebugLog = false
  @State private var sleepTimeTarget: SleepTimeField?

  var body: some View {
    ZStack {
      if showPlayerSettings {
        PlayerSettingsView(onBack: { showPlayerSettings = false })
          .environmentObject(settings)
          .transition(.move(edge: .trailing))
      } else {
        mainList
          .transition(.move(edge: .leading))
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .animation(.easeInOut(duration: 0.2), value: showPlayerSettings)
    .fullScreenCover(isPresented: $showExtensions) {
      ExtensionsView()
        .environmentObject(app)
        .environmentObject(extensions)
    }
    .sheet(isPresented: $showFolderSheet) { folderSheet }
    .fullScreenCover(isPresented: $showWebDavSheet) {
      WebDavSettingsView()
        .environmentObject(app)
        .environmentObject(webdav)
        .environmentObject(library)
    }
    .fullScreenCover(isPresented: $showDebugLog) {
      DebugLogView()
    }
    .sheet(item: $sleepTimeTarget) { target in
      sleepTimePicker(target)
    }
    .confirmationDialog("主题", isPresented: $showThemeDialog, titleVisibility: .visible) {
      ForEach(ThemeMode.allCases) { mode in
        Button(mode.title) { settings.settings.themeMode = mode }
      }
      Button("取消", role: .cancel) {}
    }
    .confirmationDialog("配色方案", isPresented: $showColorSchemeDialog, titleVisibility: .visible) {
      Button("Timbre 蓝") {}
      Button("取消", role: .cancel) {}
    } message: {
      Text("iOS 版固定使用 Timbre 蓝，由种子色 #003B7F 生成一整套 Material 3 配色。")
    }
  }

  // MARK: - 列表

  private var mainList: some View {
    VStack(spacing: 0) {
      topBar(title: "偏好设置", leadingIcon: "xmark") {
        app.showSettings = false
      }

      ScrollView {
        VStack(spacing: 0) {
          // 「有声书文件夹」与「最新源分享」按需求隐藏（iOS 不做本地文件夹播放）。
          // folderSheet 的实现保留在文件底部，恢复显示只需取消注释这两行。
          // M3ListItem(
          //   systemName: "books.vertical",
          //   title: "有声书文件夹",
          //   subtitle: "管理扫描有声书的文件夹",
          //   action: { showFolderSheet = true }
          // )

          M3ListItem(
            // 安卓 Material 图标是 language（地球），SF 对应 globe
            systemName: "globe",
            title: "WebDAV",
            subtitle: "导入书籍、备份与恢复、服务器",
            action: { showWebDavSheet = true }
          )

          M3ListItem(
            // 安卓 Material 图标是 download（下箭头到横线），SF 对应 arrow.down.to.line
            systemName: "arrow.down.to.line",
            title: "接口源",
            subtitle: "导入和管理 .jdr 接口源",
            action: { showExtensions = true }
          )

          // M3ListItem(
          //   systemName: "square.and.arrow.up",
          //   title: "最新源分享",
          //   action: {
          //     if let url = URL(string: "https://github.com/cq10086123/Timbre/tree/main/samples") {
          //       UIApplication.shared.open(url)
          //     }
          //   }
          // )

          M3ListItem(
            systemName: "wifi",
            title: "仅在 Wi-Fi 下自动刷新书架",
            subtitle: "避免使用移动数据自动刷新 WebDAV 书架"
          ) {
            M3Switch(isOn: $settings.settings.autoRefreshWifi)
          }

          selectionRow(
            title: "主题",
            value: settings.settings.themeMode.title
          ) { showThemeDialog = true }

          selectionRow(title: "配色方案", value: "Timbre 蓝") {
            showColorSchemeDialog = true
          }

          M3ListItem(systemName: gridIcon, title: "使用网格布局") {
            M3Switch(isOn: $settings.settings.useGridLayout)
          }

          M3ListItem(
            systemName: "timer",
            title: "播放器设置",
            subtitle: "跳过时间、自动回退、在线预加载等播放器选项",
            action: { showPlayerSettings = true }
          )

          autoSleepTimerCard

          M3ListItem(
            systemName: "chevron.left.forwardslash.chevron.right",
            title: "开源项目",
            action: {
              if let url = URL(string: "https://github.com/cq10086123/Timbre") {
                UIApplication.shared.open(url)
              }
            }
          )

          M3ListItem(
            systemName: nil,
            title: "软件版本",
            subtitle: appVersion
          )

          // 调试阶段先保留日志入口，正式收口时再隐藏
          M3ListItem(
            systemName: "doc.text.magnifyingglass",
            title: "调试日志",
            subtitle: "已记录 \(DebugLog.shared.count) 条 · 出问题时导出这里的内容发给开发者",
            action: { showDebugLog = true }
          )

          Spacer().frame(height: 32)
        }
      }
    }
  }

  private var gridIcon: String {
    settings.settings.useGridLayout ? "square.grid.2x2" : "list.bullet"
  }

  /// 安卓 SelectionRow：只有标题 + 右侧取值，没有图标
  private func selectionRow(
    title: String,
    value: String,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 0) {
        Text(title)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer(minLength: 8)
        Text(value)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 14)
      .frame(minHeight: 56)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  // MARK: - 自动睡眠定时器卡

  private var autoSleepTimerCard: some View {
    VStack(alignment: .leading, spacing: 0) {
      HStack(spacing: 0) {
        Text("自动睡眠定时器")
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer(minLength: 8)
        M3Switch(isOn: $settings.settings.autoSleepEnabled)
      }
      .frame(minHeight: 32)

      Text(
        "在 \(AutoSleepWindow.label(minute: settings.settings.autoSleepStartMinute)) 至 "
          + "\(AutoSleepWindow.label(minute: settings.settings.autoSleepEndMinute)) "
          + "之间开始播放时，自动开启睡眠定时器"
      )
      .font(AppFont.bodySmall())
      .foregroundColor(AppColor.onSurfaceVariant)
      .fixedSize(horizontal: false, vertical: true)
      .padding(.top, 4)

      HStack(spacing: 4) {
        sleepTimeButton("设置开始时间", field: .start)
        sleepTimeButton("设置结束时间", field: .end)
        Spacer(minLength: 0)
      }
      .padding(.top, 4)
    }
    .padding(.horizontal, 8)
    .padding(.vertical, 14)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.medium, style: .continuous)
        .strokeBorder(AppColor.outlineVariant, lineWidth: 1)
    )
    .padding(.horizontal, 8)
    .padding(.vertical, 16)
    .opacity(settings.settings.autoSleepEnabled ? 1 : 0.7)
  }

  private func sleepTimeButton(_ title: String, field: SleepTimeField) -> some View {
    Button {
      sleepTimeTarget = field
    } label: {
      Text(title)
        .font(AppFont.labelLarge())
        .foregroundColor(
          settings.settings.autoSleepEnabled ? AppColor.primary : AppColor.onSurfaceVariant
        )
        .padding(.horizontal, 12)
        .frame(height: 40)
    }
    .buttonStyle(.plain)
    .disabled(!settings.settings.autoSleepEnabled)
  }

  private func sleepTimePicker(_ target: SleepTimeField) -> some View {
    let binding = Binding<Date>(
      get: {
        let minute =
          target == .start
          ? settings.settings.autoSleepStartMinute
          : settings.settings.autoSleepEndMinute
        var components = DateComponents()
        components.hour = minute / 60
        components.minute = minute % 60
        return Calendar.current.date(from: components) ?? Date()
      },
      set: { newValue in
        let components = Calendar.current.dateComponents([.hour, .minute], from: newValue)
        let minute = (components.hour ?? 0) * 60 + (components.minute ?? 0)
        if target == .start {
          settings.settings.autoSleepStartMinute = minute
        } else {
          settings.settings.autoSleepEndMinute = minute
        }
      }
    )
    return VStack(spacing: 0) {
      Text(target == .start ? "设置开始时间" : "设置结束时间")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 24)
        .padding(.top, 24)
        .padding(.bottom, 8)
      DatePicker("", selection: binding, displayedComponents: .hourAndMinute)
        .datePickerStyle(.wheel)
        .labelsHidden()
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
      Spacer(minLength: 0)
      HStack {
        Spacer()
        Button("完成") { sleepTimeTarget = nil }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 16)
          .frame(height: 48)
      }
      .padding(.horizontal, 8)
      .padding(.bottom, 8)
    }
    .presentationDetents([.height(360)])
    .background(AppColor.surfaceContainerHigh.ignoresSafeArea())
  }

  // MARK: - 说明弹窗

  private var folderSheet: some View {
    infoSheet(
      title: "有声书文件夹",
      message: "iOS 没有「扫描文件夹」的权限模型，改为从「文件」App 或「共享」菜单把音频导入本应用。"
        + "\n\n导入后音频会复制到应用沙盒的 Books 目录，可在「文件」App 的「我的 iPhone › Timbre」里直接查看和增删。",
      confirmAction: {
        app.showSettings = false
        app.showImporter = true
      },
      confirmTitle: "导入文件"
    )
  }

  private func infoSheet(
    title: String,
    message: String,
    confirmAction: (() -> Void)? = nil,
    confirmTitle: String = "知道了"
  ) -> some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(title)
        .font(AppFont.headlineSmall())
        .foregroundColor(AppColor.onSurface)
        .padding(.bottom, 12)
      Text(message)
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)
      Spacer(minLength: 16)
      HStack(spacing: 8) {
        Spacer()
        Button("取消") { dismissSheet() }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.horizontal, 12)
          .frame(height: 44)
        Button(confirmTitle) {
          dismissSheet()
          if let confirmAction {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.25) { confirmAction() }
          }
        }
        .font(AppFont.labelLarge())
        .foregroundColor(AppColor.primary)
        .padding(.horizontal, 12)
        .frame(height: 44)
      }
    }
    .padding(24)
    .presentationDetents([.height(320)])
    .background(AppColor.surfaceContainerHigh.ignoresSafeArea())
  }

  private func dismissSheet() {
    showFolderSheet = false
    showWebDavSheet = false
  }

  // MARK: - 顶栏

  private func topBar(
    title: String,
    leadingIcon: String,
    action: @escaping () -> Void
  ) -> some View {
    HStack(spacing: 0) {
      IconButton48(systemName: leadingIcon, label: "关闭", action: action)
      Text(title)
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
      Spacer()
      // 构建指纹：确认手里装的是哪一次构建，避免「装了新包看着还是旧界面」这类问题
      Text(buildStamp)
        .font(AppFont.labelSmall())
        .foregroundColor(AppColor.onSurfaceVariant)
        .lineLimit(1)
        .padding(.trailing, 16)
    }
    .frame(height: 64)
  }

  /// CI 注入的 commit 与构建时间，本地编译显示 dev
  private var buildStamp: String {
    let info = Bundle.main.infoDictionary
    let commit = info?["TimbreCommit"] as? String ?? "dev"
    let date = info?["TimbreBuildDate"] as? String ?? "dev"
    return "\(commit) · \(date)"
  }

  private var appVersion: String {
    let version = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    let build = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "1"
    return "\(version) (\(build)) · \(buildStamp)"
  }
}

// MARK: - 睡眠时间字段

enum SleepTimeField: String, Identifiable {
  case start
  case end
  var id: String { rawValue }
}

// MARK: - M3 Switch

/// Material 3 Switch 形状的开关，避免各页面自己写 `.tint`
struct M3Switch: View {
  @Binding var isOn: Bool

  var body: some View {
    Toggle("", isOn: $isOn)
      .labelsHidden()
      .tint(AppColor.primary)
  }
}
