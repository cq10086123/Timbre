import Combine
import Foundation
import SwiftUI

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

  @Published var showPlayer = false
  @Published var showImporter = false
  @Published var showSettings = false
  @Published var searchText = ""
  @Published var isImporting = false
  @Published var toast: String?
  @Published var errorMessage: String?

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
    if player.currentBook?.id == book.id { player.stop() }
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
      if FileManager.default.fileExists(atPath: destination.path) {
        try FileManager.default.removeItem(at: destination)
      }
      try imageData.write(to: destination, options: .atomic)
    } catch {
      return false
    }
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
}
