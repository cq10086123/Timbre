import Foundation

/// 书架自动刷新（对齐安卓 `MediaScanTrigger.automaticScan` + `autoRefreshWifi` 网络策略）。
///
/// iOS 的「刷新」含义和安卓一致：把书架上每本书的章节清单重新对齐存储介质上的现状。
/// 本地书的文件就在沙盒里，增删由导入/删除那一步本身负责，没什么可对齐的；
/// 真正会变的是 WebDAV：NAS 上加了新的一集、或者删掉了几集，书架不会自己知道。
/// 所以这里只刷 WebDAV 书：重新列一遍它所在的目录，把章节补上/去掉，
/// 进度、书签、跳过片头片尾、倍速、封面这些用户资产全部保留。
@MainActor
enum WebDavRefresher {

  /// 安卓 `MIN_SCAN_INTERVAL_MS` = 10 分钟：服务端的目录遍历也是有限的资源，
  /// 每次冷启动都全量拉一遍，NAS 会被书架拖慢。
  private static let minimumInterval: TimeInterval = 600
  private static var lastRun: Date?

  /// 冷启动入口：先过网络策略，再刷。
  static func refreshIfAllowed(
    library: LibraryStore,
    webdav: WebDavStore,
    settings: AppSettings
  ) async {
    if settings.autoRefreshWifi {
      // 冷启动时 NWPathMonitor 还没回报，等一个准确答案再判断是不是 Wi-Fi
      await NetworkStatus.waitForFirstVerdict()
      guard NetworkStatus.isWiFi else {
        DLog("webdav", "自动刷新跳过：当前不在 Wi-Fi（设置里开了「仅 Wi-Fi」）")
        return
      }
    }
    await refresh(library: library, webdav: webdav)
  }

  static func refresh(library: LibraryStore, webdav: WebDavStore) async {
    let targets = library.books.filter { $0.kind == .webdav }
    guard !targets.isEmpty else { return }
    if let lastRun, Date().timeIntervalSince(lastRun) < minimumInterval {
      DLog("webdav", "自动刷新跳过：距上次不足 \(Int(minimumInterval / 60)) 分钟")
      return
    }
    lastRun = Date()

    var changed = 0
    for book in targets {
      if await refreshOne(book: book, library: library, webdav: webdav) { changed += 1 }
    }
    if changed > 0 {
      library.save()
      DLog("webdav", "自动刷新完成：\(changed)/\(targets.count) 本有变化")
    } else {
      DLog("webdav", "自动刷新完成：\(targets.count) 本均无变化")
    }
  }

  private static func refreshOne(
    book: Book,
    library: LibraryStore,
    webdav: WebDavStore
  ) async -> Bool {
    guard let serverId = book.webdavServerId,
      let server = webdav.server(withId: serverId)
    else { return false }

    // 书 ID 里塞了目录路径，但那是百分号编码前的原始值，反解容易出错；
    // 直接拿第一章的地址去掉最后一段，稳。
    guard let sample = book.chapters.first?.remoteURL,
      let fileURL = URL(string: sample)
    else { return false }
    let folder = fileURL.deletingLastPathComponent()

    let items: [WebDavResource]
    do {
      items = try await WebDavClient.list(
        url: folder,
        server: server,
        password: webdav.password(for: serverId)
      )
    } catch {
      // 一台服务器连不上不能影响其他书
      DLog("webdav", "自动刷新失败 \(book.title)：\(error.localizedDescription)")
      return false
    }

    let files = WebDavAudio.sorted(
      items.filter { !$0.isDirectory && WebDavAudio.isAudio($0.name) }
    )
    // 目录整个空了（挂了 / 权限变了 / 正在同步）：宁可不改，也不要把整本书清空
    guard !files.isEmpty else { return false }

    // 已知时长按章节 id 带过去，别因为刷新一次就全变回 0
    var knownDurations: [String: Double] = [:]
    for chapter in book.chapters { knownDurations[chapter.id] = chapter.duration }

    let chapters = files.map { file in
      let id = "\(book.id)#\(file.url)"
      return Chapter(
        id: id,
        title: file.name,
        remoteURL: file.url,
        duration: knownDurations[id] ?? 0
      )
    }

    // 顺序和内容都没变就别写盘（save 会整本库编码一次）
    guard chapters.map(\.id) != book.chapters.map(\.id) else { return false }

    // 进度按「当前章节的 id」重新对位；对不上就退回原序号，且不越界
    let currentId = book.chapters.indices.contains(book.position.chapterIndex)
      ? book.chapters[book.position.chapterIndex].id
      : nil

    var updated = book
    updated.chapters = chapters
    if let currentId, let index = chapters.firstIndex(where: { $0.id == currentId }) {
      updated.position.chapterIndex = index
    } else {
      updated.position.chapterIndex = min(book.position.chapterIndex, chapters.count - 1)
    }

    library.update(updated)
    DLog("webdav", "自动刷新 \(book.title)：\(book.chapters.count) → \(chapters.count) 集")
    return true
  }
}
