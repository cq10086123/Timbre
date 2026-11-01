import Combine
import Foundation

/// 书架数据仓库：books 持久化在 Documents/library.json
final class LibraryStore: ObservableObject {
  @Published var books: [Book] = []

  init() {
    load()
  }

  // MARK: - 读写

  private func load() {
    guard let data = try? Data(contentsOf: AppPaths.libraryFile) else { return }
    books = (try? JSONCoding.decoder().decode([Book].self, from: data)) ?? []
  }

  /// delay > 0 时合并高频写入（播放进度每秒都在变）
  func save(delay: TimeInterval = 0) {
    if delay <= 0 {
      persist()
    } else {
      DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
        self?.persist()
      }
    }
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(books) else { return }
    DispatchQueue.global(qos: .utility).async {
      try? data.write(to: AppPaths.libraryFile, options: .atomic)
    }
  }

  // MARK: - 查询

  func book(withId id: String) -> Book? {
    books.first { $0.id == id }
  }

  func index(of id: String) -> Int? {
    books.firstIndex { $0.id == id }
  }

  /// 有播放进度的书（“正在播放中”）
  var playingBooks: [Book] {
    books
      .filter { $0.position.isStarted && !$0.chapters.isEmpty }
      .sorted { ($0.position.updatedAt ?? .distantPast) > ($1.position.updatedAt ?? .distantPast) }
  }

  func sorted(_ order: BookSortOrder) -> [Book] {
    switch order {
    case .recent:
      return books.sorted {
        ($0.position.updatedAt ?? $0.addedAt) > ($1.position.updatedAt ?? $1.addedAt)
      }
    case .title:
      return books.sorted { NaturalSort.compare($0.title, $1.title) == .orderedAscending }
    case .added:
      return books.sorted { $0.addedAt > $1.addedAt }
    case .author:
      return books.sorted {
        (NaturalSort.compare($0.author, $1.author) == .orderedAscending)
      }
    }
  }

  func search(_ keyword: String) -> [Book] {
    let key = keyword.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    guard !key.isEmpty else { return [] }
    return books.filter {
      $0.title.lowercased().contains(key) || $0.author.lowercased().contains(key)
    }
  }

  // MARK: - 增删改

  func add(_ book: Book) {
    var copy = book
    if books.contains(where: { $0.id == copy.id }) {
      copy.id = UUID().uuidString
    }
    books.append(copy)
    save()
  }

  func update(_ book: Book) {
    guard let i = index(of: book.id) else { return }
    books[i] = book
    save()
  }

  func delete(_ book: Book) {
    books.removeAll { $0.id == book.id }
    if let folder = book.folder {
      try? FileManager.default.removeItem(at: AppPaths.url(for: folder))
    }
    if let cover = book.coverFile {
      try? FileManager.default.removeItem(at: AppPaths.url(for: cover))
    }
    save()
  }

  func rename(_ book: Book, title: String, author: String) {
    guard let i = index(of: book.id) else { return }
    books[i].title = title.trimmingCharacters(in: .whitespacesAndNewlines)
    books[i].author = author.trimmingCharacters(in: .whitespacesAndNewlines)
    save()
  }

  // MARK: - 播放进度

  func setPosition(bookId: String, chapterIndex: Int, seconds: Double) {
    guard let i = index(of: bookId) else { return }
    books[i].position = PlaybackPosition(
      chapterIndex: chapterIndex,
      seconds: max(0, seconds),
      updatedAt: Date()
    )
    save(delay: 3)
  }

  func markIntroSkipped(bookId: String, chapterIndex: Int) {
    guard let i = index(of: bookId) else { return }
    books[i].introSkippedChapter = chapterIndex
    save(delay: 1)
  }

  func resetPosition(bookId: String) {
    guard let i = index(of: bookId) else { return }
    books[i].position = PlaybackPosition()
    save()
  }

  func markFinished(bookId: String) {
    guard let i = index(of: bookId), !books[i].chapters.isEmpty else { return }
    books[i].position = PlaybackPosition(
      chapterIndex: books[i].chapters.count - 1,
      seconds: books[i].chapters[books[i].chapters.count - 1].duration > 0
        ? books[i].chapters[books[i].chapters.count - 1].duration - 1 : 0,
      updatedAt: Date()
    )
    save()
  }

  // MARK: - 书签

  func addBookmark(bookId: String, chapterIndex: Int, seconds: Double, title: String) {
    guard let i = index(of: bookId) else { return }
    let mark = Bookmark(
      chapterIndex: chapterIndex,
      seconds: seconds,
      title: title.isEmpty ? "书签" : title
    )
    books[i].bookmarks.append(mark)
    books[i].bookmarks.sort {
      $0.chapterIndex == $1.chapterIndex
        ? $0.seconds < $1.seconds
        : $0.chapterIndex < $1.chapterIndex
    }
    save()
  }

  func updateBookmark(bookId: String, bookmarkId: String, title: String) {
    guard let index = index(of: bookId),
      let bookmarkIndex = books[index].bookmarks.firstIndex(where: { $0.id == bookmarkId })
    else { return }
    books[index].bookmarks[bookmarkIndex].title = title
    save()
  }

  func deleteBookmark(bookId: String, bookmarkId: String) {
    guard let i = index(of: bookId) else { return }
    books[i].bookmarks.removeAll { $0.id == bookmarkId }
    save()
  }

  func updateChapterDurations(bookId: String, durations: [String: Double]) {
    guard let i = index(of: bookId) else { return }
    var changed = false
    for c in 0..<books[i].chapters.count {
      if let d = durations[books[i].chapters[c].id], d > 0, books[i].chapters[c].duration != d {
        books[i].chapters[c].duration = d
        changed = true
      }
    }
    if changed { save(delay: 1) }
  }
}
