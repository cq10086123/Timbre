import SwiftUI

@main
struct TimbreApp: App {
  @StateObject private var app = AppState()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(app)
        .environmentObject(app.library)
        .environmentObject(app.settings)
        .environmentObject(app.extensions)
        .environmentObject(app.webdav)
        .environmentObject(app.player)
        .environmentObject(app.cache)
        .tint(AppColor.primary)
        .preferredColorScheme(app.themeColorScheme)
    }
  }
}

struct RootView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    ShelfView()
      // 冷启动的书架自动刷新：只刷 WebDAV 书，受「仅 Wi-Fi 下自动刷新」约束
      .task {
        // 老库补齐：把 m4b 里的内嵌章节读成 markData
        await app.backfillChapterMarks()
        // 没有 封面 的 WebDAV 书补封面（目录图片 / 内嵌 artwork）
        await app.backfillWebDavCovers()
        await WebDavRefresher.refreshIfAllowed(
          library: app.library,
          webdav: app.webdav,
          settings: app.settings.settings
        )
      }
      .onChange(of: scenePhase) { phase in
        if phase != .active { player.flush() }
      }
      // 从「文件」「隔空投送」「分享」里用 Timbre 打开 .jdr 时的第二条导入通道
      .onOpenURL { url in
        guard url.pathExtension.lowercased() == "jdr" else { return }
        Task { @MainActor in
          do {
            let installed = try await app.installExtension(url: url)
            app.showToast(
              "已导入 \(installed.name) v\(installed.version)（\(installed.sources.count) 个源）"
            )
          } catch {
            app.errorMessage = error.localizedDescription
          }
        }
      }
  }
}
