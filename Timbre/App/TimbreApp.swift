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
        .environmentObject(app.player.summary)
        .environmentObject(app.cache)
        .tint(AppColor.primary)
        .preferredColorScheme(app.themeColorScheme)
    }
  }
}

struct RootView: View {
  @EnvironmentObject private var app: AppState
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    ShelfView()
      // 冷启动的书架自动刷新：只刷 WebDAV 书，受「仅 Wi-Fi 下自动刷新」约束
      .task {
        // 进程被杀时来不及删的临时文件（一集几十 MB）在后台清一遍
        _ = Task.detached(priority: .background) {
          OnlineChapterFileCache.purgeStaleTmpFiles()
        }
        // 老库补齐：把 m4b 里的内嵌章节读成 markData
        await app.backfillChapterMarks()
        // 没有 封面 的 WebDAV 书补封面（目录图片 / 内嵌 artwork）
        await app.backfillWebDavCovers()
        await WebDavRefresher.refreshIfAllowed(
          library: app.library,
          webdav: app.webdav,
          settings: app.settings.settings
        )
        // 公告与更新：与安卓 main 同源（announcement.txt / update.json），
        // 启动 10 秒后再查 —— 书架先出来，检查是最不重要的事（对齐安卓 CHECK_DELAY_MS）
        try? await Task.sleep(nanoseconds: 10_000_000_000)
        await app.checkRemoteNotices()
      }
      // 公告 / 更新：main 的 announcement.txt 与 update.json 写啥就弹啥。
      //
      // 不用系统 `.alert`：它的 message 字号固定 13pt、还是次级灰，公告十几行压下来
      // 就是一片看不清的小字（用户反馈「下面字体显示不清楚，看不见」）。
      // 换成自绘的 M3ReadingDialog：正文 16pt 主文字色、行距放开、太长可滚动。
      //
      // 两个共用这一层 overlay，排队是天然的：公告优先，用户关掉之后更新才出现。
      // （之前同一条视图链挂两个 `.alert`，后声明的会把公告静默顶掉。）
      .overlay {
        if let text = app.announcementText {
          M3ReadingDialog(
            title: "公告",
            message: text,
            actions: [
              M3ReadingDialog.DialogAction(title: "不再提示") { app.neverShowAnnouncement() },
              M3ReadingDialog.DialogAction(title: "知道了", emphasized: true) {
                app.acknowledgeAnnouncement()
              },
            ],
            onDismiss: { app.acknowledgeAnnouncement() }
          )
        } else if let notice = app.updateNotice {
          M3ReadingDialog(
            title: "发现新版本 \(notice.versionName)",
            message: notice.releaseNotes ?? "打开发布页查看更新内容",
            actions: [
              M3ReadingDialog.DialogAction(title: "取消") { app.dismissUpdateNotice() },
              M3ReadingDialog.DialogAction(title: "去更新", emphasized: true) {
                app.openUpdatePage()
              },
            ],
            onDismiss: { app.dismissUpdateNotice() }
          )
        }
      }
      .onChange(of: scenePhase) { phase in
        // 前后台边界记一条：排查「锁屏能不能继续缓存」时靠它对齐日志时间轴
        switch phase {
        case .active: DLog("life", "回到前台")
        case .background: DLog("life", "进入后台")
        default: DLog("life", "失去活动状态")
        }
        if phase != .active { app.player.flush() }
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
