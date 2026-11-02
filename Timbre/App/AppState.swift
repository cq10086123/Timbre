import Combine
import Foundation
import SwiftUI
import UIKit

final class AppState: ObservableObject {
  let library = LibraryStore()
  let settings = SettingsStore()
  let extensions = ExtensionStore()
  let webdav = WebDavStore()

  lazy var player = PlaybackEngine(
    library: library,
    settings: settings,
    extensions: extensions,
    webdav: webdav
  )

  /// 在线书「缓存整本」的任务与进度
  lazy var cache = OnlineCacheManager(library: library, extensions: extensions)

  /// WebDAV 备份目标（服务器 + 目录 + 上次用的标签）
  let backupTargets = WebDavBackupStore()
  /// 备份 / 恢复
  lazy var backup = WebDavBackupService(
    library: library,
    servers: webdav,
    targets: backupTargets,
    extensions: extensions
  )

  @Published var showPlayer = false
  @Published var showImporter = false
  @Published var showSettings = false
  @Published var searchText = ""
  @Published var isImporting = false
  @Published var toast: String?
  @Published var errorMessage: String?

  // MARK: - 公告与更新（与安卓 main 同源）

  /// 公告弹窗正文；nil = 不显示
  @Published var announcementText: String?
  /// 更新弹窗；nil = 不显示
  @Published var updateNotice: UpdateNotice?

  struct UpdateNotice: Equatable {
    var versionName: String
    var releaseNotes: String?
    var releaseUrl: String?
  }

  var currentAppVersion: String {
    (Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String) ?? ""
  }

  /// 启动后调用（调用方先 delay 10 秒，对齐安卓 CHECK_DELAY_MS）。
  /// 公告每次启动都查；更新每天最多查一次。
  func checkRemoteNotices() async {
    if let text = await RemoteNotices.fetchAnnouncement(),
      RemoteNotices.fingerprint(text) != RemoteNotices.dismissedAnnouncementFingerprint
    {
      announcementText = text
    }

    let today = RemoteNotices.today
    guard today - RemoteNotices.lastCheckDay >= 1 else { return }
    // 请求前先记日期：今天离线的设备不要每次启动都重试（对齐安卓）
    RemoteNotices.lastCheckDay = today
    guard let latest = await RemoteNotices.fetchLatestUpdate() else { return }
    guard RemoteNotices.isNewer(current: currentAppVersion, latest: latest.versionName) else { return }
    updateNotice = UpdateNotice(
      versionName: latest.versionName,
      releaseNotes: latest.releaseNotes,
      releaseUrl: latest.releaseUrl ?? RemoteNotices.releasePageURL
    )
  }

  /// 公告「知道了」：只关本次，下次启动还会弹（对齐安卓 onKnow）
  func acknowledgeAnnouncement() {
    announcementText = nil
  }

  /// 公告「不再提示」：记住这条文本的指纹；main 改了文本会再弹
  func neverShowAnnouncement() {
    if let text = announcementText {
      RemoteNotices.dismissedAnnouncementFingerprint = RemoteNotices.fingerprint(text)
    }
    announcementText = nil
  }

  /// 更新「取消」：这个版本不再自动弹，main 发了更新的版本号才会再弹（对齐安卓 dismiss）
  func dismissUpdateNotice() {
    if let notice = updateNotice {
      RemoteNotices.dismissedUpdateVersion = notice.versionName
    }
    updateNotice = nil
  }

  /// 更新「去更新」：打开发布页；弹窗关掉但不算已读（对齐安卓 onUpdatePageOpened）
  func openUpdatePage() {
    let urlString = updateNotice?.releaseUrl ?? RemoteNotices.releasePageURL
    if let url = URL(string: urlString) {
      UIApplication.shared.open(url)
    }
    updateNotice = nil
  }

  // MARK: - 导入

  func handleImport(urls: [URL]) {
    guard !urls.isEmpty else { return }
    isImporting = true
    // 拷文件 / 解析元数据都是同步 IO，必须离开主线程，否则整本大书导入会卡界面
    let config = settings.settings
    Task.detached(priority: .userInitiated) { [weak self] in
      var imported: [Book] = []
      var failures: [String] = []
      for url in urls {
        do {
          let book = try await AudioImporter.importItem(url: url, settings: config)
          imported.append(book)
        } catch {
          failures.append("\(url.lastPathComponent)：\(error.localizedDescription)")
        }
      }
      let books = imported
      let errorText = failures.joined(separator: "\n")

      await MainActor.run { [weak self] in
        guard let self else { return }
        for book in books { self.library.add(book) }
        self.isImporting = false
        if !books.isEmpty {
          self.showToast("已导入 \(books.count) 本书")
        }
        if !errorText.isEmpty {
          self.errorMessage = errorText
        }
      }

      // 后台补齐时长 + 内嵌章节，不挡播放
      for book in books {
        let analysis = await AudioImporter.analyze(
          bookId: book.id,
          chapters: book.chapters,
          readMarks: true
        )
        let bookId = book.id
        await MainActor.run { [weak self] in
          guard let self else { return }
          self.library.updateChapterDurations(bookId: bookId, durations: analysis.durations)
          self.library.updateChapterMarks(bookId: bookId, marks: analysis.marks)
        }
      }
    }
  }

  // MARK: - 播放

  /// 打开播放页。默认**不自动播放** —— 安卓点书架上的书只是 `goTo(Playback(bookId))`，
  /// 播放要等用户按播放键；只有「播这一集」这种明确意图才传 `autoplay: true`。
  func openBook(_ book: Book, chapterIndex: Int? = nil, autoplay: Bool = false) {
    DLog("shelf", "打开播放页 书=\(book.title) 集=\((chapterIndex ?? book.position.chapterIndex) + 1)")
    let index = chapterIndex ?? (book.position.isStarted ? book.position.chapterIndex : 0)
    player.open(book, chapterIndex: index, autoplay: autoplay)
    showPlayer = true
  }

  func playChapter(bookId: String, index: Int) {
    guard let book = library.book(withId: bookId) else { return }
    // 同一本书里跳集：保持当前播放状态（暂停就还是暂停）；
    // 换到另一本书：不要替用户按下播放
    let autoplay = player.currentBook?.id == book.id && player.wantsToPlay
    player.open(book, chapterIndex: index, autoplay: autoplay)
    showPlayer = true
  }

  // MARK: - 书架操作

  func delete(_ book: Book) {
    DLog("shelf", "删除书 \(book.title)")
    if player.currentBook?.id == book.id { player.stop() }
    // 在线书删掉后，手动缓存下来的章节也一并清掉（对齐安卓 book.delete.dialog.message_online）
    if book.kind == .jdr {
      let bookId = book.id
      let source = OnlineChapterFileCache.sourceSegment(sourceId: book.sourceId)
      Task { @MainActor in cache.cancel(bookId: bookId) }
      Task.detached(priority: .utility) {
        OnlineChapterFileCache.clearBook(source: source, bookId: bookId)
      }
    }
    library.delete(book)
    showToast("已删除《\(book.title)》")
  }

  func rename(_ book: Book, title: String, author: String) {
    library.rename(book, title: title, author: author)
  }

  // MARK: - 接口源

  /// 从「文件」里选中的 .jdr 安装。解析放到后台，成败都直接抛回给调用方显示，
  /// 不再走全局 errorMessage —— 接口源页是 fullScreenCover，全局弹窗会被它盖住看不见。
  func installExtension(url: URL) async throws -> InstalledExtension {
    let accessed = url.startAccessingSecurityScopedResource()
    defer { if accessed { url.stopAccessingSecurityScopedResource() } }

    let data: Data
    do {
      data = try Data(contentsOf: url)
    } catch {
      throw ExtensionInstallError.readFailed(url.lastPathComponent, error.localizedDescription)
    }
    return try await installExtension(data: data)
  }

  func installExtension(link: String) async throws -> InstalledExtension {
    let trimmed = link.trimmingCharacters(in: .whitespacesAndNewlines)
    guard let url = URL(string: trimmed), let scheme = url.scheme?.lowercased(),
      scheme == "http" || scheme == "https"
    else {
      throw ExtensionInstallError.badLink
    }
    let data: Data
    do {
      var request = URLRequest(url: url)
      request.timeoutInterval = 30
      let (payload, response) = try await URLSession.shared.data(for: request)
      if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
        throw ExtensionInstallError.downloadFailed("HTTP \(http.statusCode)")
      }
      data = payload
    } catch let error as ExtensionInstallError {
      throw error
    } catch {
      throw ExtensionInstallError.downloadFailed(error.localizedDescription)
    }
    return try await installExtension(data: data)
  }

  private func installExtension(data: Data) async throws -> InstalledExtension {
    guard !data.isEmpty else { throw ExtensionInstallError.emptyFile }
    // 解压 + 校验可能上百毫秒，离开主线程
    let package: JdrPackage
    do {
      package = try await Task.detached(priority: .userInitiated) {
        try JdrArchive.parse(data: data)
      }.value
    } catch let error as JdrError {
      throw ExtensionInstallError.invalidPackage(error.errorDescription ?? "包格式错误")
    } catch let error as ZipArchive.ZipError {
      throw ExtensionInstallError.invalidPackage(error.errorDescription ?? "包格式错误")
    } catch {
      throw ExtensionInstallError.invalidPackage(error.localizedDescription)
    }
    let installed = extensions.install(package: package)
    return installed
  }

  // MARK: - 封面

  /// 在线书源的封面下载到本地
  func cacheCover(bookId: String, remote: String?) {
    guard let remote, let url = URL(string: remote), remote.hasPrefix("http") else { return }
    Task {
      guard let (data, _) = try? await URLSession.shared.data(from: url), !data.isEmpty else {
        return
      }
      let name = "\(bookId).jpg"
      let destination = AppPaths.coversDir.appendingPathComponent(name)
      try? data.write(to: destination)
      await MainActor.run {
        // 源更新了封面但文件名没变：同路径覆盖写必须让解码缓存失效
        CoverStore.clear()
        if let index = self.library.index(of: bookId) {
          self.library.books[index].coverFile = "Covers/\(name)"
          self.library.save()
        }
      }
    }
  }

  // MARK: - 提示

  func showToast(_ text: String) {
    toast = text
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.2) { [weak self] in
      if self?.toast == text { self?.toast = nil }
    }
  }

  // MARK: - 在线书源：刷新章节

  @Published var refreshingBookId: String?

  /// 重新拉一遍章节列表并合并回书架。返回给用户看的一句话。
  @MainActor
  @discardableResult
  func refreshOnlineChapters(_ book: Book) async -> String {
    guard book.kind == .jdr else { return "这本不是在线书源的书" }
    guard
      let installed = extensions.source(withId: book.sourceId),
      let script = extensions.scriptText(for: installed)
    else { return "找不到这本书的接口源，可能已经被卸载了" }

    refreshingBookId = book.id
    defer { refreshingBookId = nil }
    do {
      let fresh = try await ExtensionSourceService.shared.chapters(
        script: script,
        allowInsecure: installed.allowInsecure,
        bookId: book.remoteBookId ?? book.id,
        extraJSON: book.sourceExtraJSON
      )
      guard !fresh.isEmpty else { return "接口源这次没有返回章节" }
      let result = library.mergeChapters(bookId: book.id, fresh: fresh)
      if result.added > 0 { return "已刷新，新增 \(result.added) 集，共 \(result.total) 集" }
      return "已刷新，共 \(result.total) 集（没有新增）"
    } catch {
      return "刷新失败：\(error.localizedDescription)"
    }
  }

  // MARK: - 封面

  /// 把选中的图片文件拷进 Covers/ 并挂到书上
  func applyCover(bookId: String, imageURL: URL) -> Bool {
    let accessed = imageURL.startAccessingSecurityScopedResource()
    defer { if accessed { imageURL.stopAccessingSecurityScopedResource() } }
    guard let data = try? Data(contentsOf: imageURL), !data.isEmpty else { return false }
    return applyCover(bookId: bookId, imageData: data, fileExtension: imageURL.pathExtension)
  }

  func applyCover(bookId: String, imageData: Data, fileExtension: String = "jpg") -> Bool {
    let ext = fileExtension.isEmpty ? "jpg" : fileExtension.lowercased()
    let name = "\(bookId).\(ext)"
    let destination = AppPaths.coversDir.appendingPathComponent(name)
    do {
      // 换封面换了扩展名（jpg→png）时旧文件会成孤儿，先把同书其他后缀的旧封面清掉
      let existing = (try? FileManager.default.contentsOfDirectory(
        at: AppPaths.coversDir, includingPropertiesForKeys: nil)) ?? []
      for old in existing where
        old.deletingPathExtension().lastPathComponent == bookId && old.lastPathComponent != name {
        try? FileManager.default.removeItem(at: old)
      }
      try imageData.write(to: destination, options: .atomic)
    } catch {
      return false
    }
    // 同路径覆盖写会让 CoverStore 的解码缓存指向旧图，必须失效
    CoverStore.clear()
    library.setCover(bookId: bookId, coverFile: "Covers/\(name)")
    return true
  }

  // MARK: - 老库补齐内嵌章节

  /// 1.0.20 之前导入的书没读过内嵌章节（m4b 整本只有一个「第 1 集」）。
  /// 启动后在后台补扫一遍：只碰本地 / WebDAV 且还没有 markData 的书。
  @MainActor
  func backfillChapterMarks() async {
    let pending = library.books.filter { book in
      book.kind != .jdr && !book.marksScanned
        && book.chapters.contains { $0.localFile != nil }
    }
    guard !pending.isEmpty else { return }
    for book in pending {
      let analysis = await AudioImporter.analyze(
        bookId: book.id,
        chapters: book.chapters,
        readMarks: true
      )
      library.updateChapterMarks(bookId: book.id, marks: analysis.marks)
      if !analysis.marks.isEmpty {
        DLog("library", "补齐内嵌章节 书=\(book.title) 命中=\(analysis.marks.count)")
      }
    }
  }

  var themeColorScheme: ColorScheme? { settings.settings.themeMode.colorScheme }

  // MARK: - WebDAV 书补封面

  /// 冷启动给没有封面的 WebDAV 书补封面：目录图片优先，其次第一段音频的内嵌 artwork。
  /// 一本一本串行来，别把 NAS 打挂；这本拿不到就跳过（下次启动再试）。
  @MainActor
  func backfillWebDavCovers() async {
    let pending = library.books.filter { book in
      book.kind == .webdav && (book.coverFile == nil || book.coverFile?.isEmpty == true)
    }
    guard !pending.isEmpty else { return }
    DLog("webdav", "补封面开始：\(pending.count) 本缺封面")
    for book in pending {
      guard let serverId = book.webdavServerId,
        let server = webdav.server(withId: serverId)
      else { continue }
      let password = webdav.password(for: serverId)
      if let path = await WebDavCoverFetcher.fetch(
        book: book,
        server: server,
        password: password,
        listing: nil
      ) {
        library.setCover(bookId: book.id, coverFile: path)
        DLog("webdav", "补封面成功 书=\(book.title)")
      }
    }
  }
}
