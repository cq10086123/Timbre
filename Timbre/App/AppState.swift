import Combine
import Foundation
import SwiftUI

final class AppState: ObservableObject {
  let library = LibraryStore()
  let settings = SettingsStore()
  let extensions = ExtensionStore()

  lazy var player = PlaybackEngine(
    library: library,
    settings: settings,
    extensions: extensions
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
    Task {
      var imported: [Book] = []
      var failures: [String] = []
      for url in urls {
        do {
          let book = try await AudioImporter.importItem(url: url, settings: self.settings.settings)
          imported.append(book)
        } catch {
          failures.append("\(url.lastPathComponent)：\(error.localizedDescription)")
        }
      }
      let books = imported
      let errorText = failures.joined(separator: "\n")
      await MainActor.run {
        for book in books { self.library.add(book) }
        self.isImporting = false
        if !books.isEmpty {
          self.showToast("已导入 \(books.count) 本书")
        }
        if !errorText.isEmpty {
          self.errorMessage = errorText
        }
      }
      // 后台补齐时长，不挡播放
      for book in books {
        let durations = await AudioImporter.fillDurations(
          bookId: book.id,
          chapters: book.chapters
        )
        let bookId = book.id
        await MainActor.run {
          self.library.updateChapterDurations(bookId: bookId, durations: durations)
        }
      }
    }
  }

  // MARK: - 播放

  func openBook(_ book: Book, chapterIndex: Int? = nil) {
    let index = chapterIndex ?? (book.position.isStarted ? book.position.chapterIndex : 0)
    player.open(book, chapterIndex: index, autoplay: true)
    showPlayer = true
  }

  func playChapter(bookId: String, index: Int) {
    guard let book = library.book(withId: bookId) else { return }
    player.open(book, chapterIndex: index, autoplay: true)
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

  func installExtension(urls: [URL]) {
    guard let url = urls.first else { return }
    let accessed = url.startAccessingSecurityScopedResource()
    defer {
      if accessed { url.stopAccessingSecurityScopedResource() }
    }
    do {
      let data = try Data(contentsOf: url)
      let installed = try extensions.install(data: data)
      showToast("已安装接口源：\(installed.name)")
    } catch {
      errorMessage = error.localizedDescription
    }
  }

  func installExtension(fromLink link: String) {
    guard let url = URL(string: link), link.hasPrefix("http") else {
      errorMessage = "链接必须以 http:// 或 https:// 开头"
      return
    }
    Task {
      do {
        let (data, _) = try await URLSession.shared.data(from: url)
        await MainActor.run {
          do {
            let installed = try self.extensions.install(data: data)
            self.showToast("已安装接口源：\(installed.name)")
          } catch {
            self.errorMessage = error.localizedDescription
          }
        }
      } catch {
        await MainActor.run {
          self.errorMessage = "下载失败：\(error.localizedDescription)"
        }
      }
    }
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

  var themeColorScheme: ColorScheme? { settings.settings.themeMode.colorScheme }
}
