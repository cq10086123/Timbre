import SwiftUI

/// WebDAV 二级设置页 —— 对齐安卓 `WebDavSettingsScreen`：
/// 导入书籍配置 / 备份与恢复 / 服务器配置。
struct WebDavSettingsView: View {
  @EnvironmentObject private var webdav: WebDavStore
  @EnvironmentObject private var library: LibraryStore
  @Environment(\.dismiss) private var dismiss

  @State private var showImport = false
  @State private var showBackup = false
  @State private var showServers = false

  var body: some View {
    VStack(spacing: 0) {
      WebDavTopBar(title: "WebDAV 设置", leadingIcon: "chevron.left") { dismiss() }

      ScrollView {
        VStack(spacing: 0) {
          M3ListItem(
            systemName: "books.vertical",
            title: "导入书籍配置",
            subtitle: "书籍文件夹与缓存",
            action: { showImport = true }
          )
          M3ListItem(
            // 安卓 Material 图标是 sync（双环箭头）；带 .icloud 后缀的变体
            // 在 iOS 16.2 上不存在会渲染成空白，用基础符号保证可见
            systemName: "arrow.triangle.2.circlepath",
            title: "备份与恢复",
            subtitle: "备份目录、备份书架与恢复",
            action: { showBackup = true }
          )
          M3ListItem(
            systemName: "globe",
            title: "服务器配置",
            subtitle: "添加、编辑并测试你的 NAS 服务器",
            action: { showServers = true }
          )
          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .fullScreenCover(isPresented: $showImport) {
      WebDavImportSettingsView()
        .environmentObject(webdav)
        .environmentObject(library)
    }
    .fullScreenCover(isPresented: $showBackup) {
      WebDavBackupView()
        .environmentObject(webdav)
        .environmentObject(library)
    }
    .fullScreenCover(isPresented: $showServers) {
      WebDavServersView()
        .environmentObject(webdav)
        .environmentObject(library)
    }
  }
}

/// 导入书籍配置 —— 对齐安卓 `WebDavImportScreen`：
/// 书籍文件夹、书籍缓存配置。
struct WebDavImportSettingsView: View {
  @EnvironmentObject private var webdav: WebDavStore
  @EnvironmentObject private var library: LibraryStore
  @Environment(\.dismiss) private var dismiss

  @State private var showServerPicker = false
  @State private var browsing: WebDavServer?
  @State private var showCache = false
  @State private var showNoServer = false

  var body: some View {
    VStack(spacing: 0) {
      WebDavTopBar(title: "导入书籍配置", leadingIcon: "chevron.left") { dismiss() }

      ScrollView {
        VStack(spacing: 0) {
          M3ListItem(
            systemName: "folder",
            title: "书籍文件夹配置",
            subtitle: "选择 NAS 上存放有声书的目录并导入书架",
            action: pickFolder
          )
          M3ListItem(
            systemName: "arrow.down.circle",
            title: "书籍缓存配置",
            subtitle: "设置缓存上限与章节预取",
            action: { showCache = true }
          )
          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .confirmationDialog(
      "选择服务器",
      isPresented: $showServerPicker,
      titleVisibility: .visible
    ) {
      ForEach(webdav.servers) { server in
        Button(server.name) { browsing = server }
      }
      Button("取消", role: .cancel) {}
    }
    .fullScreenCover(item: $browsing) { server in
      WebDavBrowseView(server: server)
        .environmentObject(webdav)
        .environmentObject(library)
    }
    .fullScreenCover(isPresented: $showCache) {
      WebDavCacheView()
    }
    .alert("还没有 WebDAV 服务器", isPresented: $showNoServer) {
      Button("知道了", role: .cancel) {}
    } message: {
      Text("请先在「WebDAV → 服务器配置」里添加一台 NAS。")
    }
  }

  private func pickFolder() {
    guard !webdav.servers.isEmpty else {
      showNoServer = true
      return
    }
    if webdav.servers.count == 1, let only = webdav.servers.first {
      browsing = only
      return
    }
    showServerPicker = true
  }
}

/// WebDAV 各页共用的顶栏（返回箭头 + 标题）
struct WebDavTopBar: View {
  var title: String
  var leadingIcon: String = "chevron.left"
  var onClose: () -> Void

  var body: some View {
    HStack(spacing: 0) {
      IconButton48(systemName: leadingIcon, label: "关闭") { onClose() }
      Text(title)
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
        .lineLimit(1)
      Spacer(minLength: 8)
    }
    .padding(.horizontal, 4)
    .frame(height: 64)
  }
}
