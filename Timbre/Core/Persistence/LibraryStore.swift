import Combine
import Foundation

/// 书架数据仓库：books 持久化在 Documents/library.json
final class LibraryStore: ObservableObject {
  @Published var books: [Book] = []

  init() {
    load()
  }

  // MARK: - 读写

  /// library.json 整体解码失败时置 true：书架此时是空的，
  /// 任何一次 save() 都会用空数组覆盖完好的旧文件 —— 必须拦下（坏文件已留档）
  private var loadFailed = false

  private func load() {
    guard let data = try? Data(contentsOf: AppPaths.libraryFile) else { return }
    do {
      books = try JSONCoding.decoder().decode([Book].self, from: data)
    } catch {
      loadFailed = true
      // 原文件留档，升级 / 半兼容数据可以拿它找回，而不是被空库覆盖
      let corruptURL = AppPaths.documents.appendingPathComponent(
        "library.json.corrupt-\(Int(Date().timeIntervalSince1970))"
      )
      try? data.write(to: corruptURL)
      DLog("library", "library.json 解码失败，原文件已留档 \(corruptURL.lastPathComponent)：\(error.localizedDescription)")
    }
  }

  /// 待落盘的那一次保存。合并式：新的一次会把上一次取消掉。
  private var pendingSave: DispatchWorkItem?

  /// delay > 0 时合并高频写入（播放进度每秒都在变、拖滑块一帧一次）。
  ///
  /// 之前这里是「每调一次就排一个 asyncAfter」——**不去重**。
  /// 拖倍速滑块 2 秒 ≈ 120 帧，就排了 120 个闭包，1 秒后挤在主线程上
  /// 同时把整个书库编码成 JSON（几百本书 + 几千章节），于是越拖越卡、松手后还在卡。
  /// 现在只保留最后一次：拖多久都只编码一次。
  func save(delay: TimeInterval = 0) {
    pendingSave?.cancel()
    if delay <= 0 {
      pendingSave = nil
      persist()
      return
    }
    let work = DispatchWorkItem { [weak self] in
      self?.pendingSave = nil
      self?.persist()
    }
    pendingSave = work
    DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: work)
  }

  private func persist() {
    if loadFailed && books.isEmpty { return }
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

  /// 换封面：只改指向，图片文件由调用方放进 Covers/
  func setCover(bookId: String, coverFile: String?) {
    guard let i = index(of: bookId) else { return }
    books[i].coverFile = coverFile
    save()
  }

  /// 在线书源刷新章节：按 id 合并，老章节已探到的时长 / 内嵌分段沿用，
  /// 新章节追加在后面（对齐安卓 `OnlinePlaybackCatalog.refreshChapters`）
  @discardableResult
  func mergeChapters(bookId: String, fresh: [Chapter]) -> (added: Int, total: Int) {
    guard let i = index(of: bookId) else { return (0, 0) }
    let old = books[i].chapters
    let oldById = Dictionary(old.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    var added = 0
    let merged = fresh.map { chapter -> Chapter in
      guard let previous = oldById[chapter.id] else {
        added += 1
        return chapter
      }
      var next = chapter
      // 已经量到 / 源里已经给过时长，别被空值冲掉
      if previous.duration > 0 { next.duration = previous.duration }
      if !previous.markData.isEmpty { next.markData = previous.markData }
      return next
    }
    books[i].chapters = merged

    // 正在听的那一集可能已经从源里消失：把断点挪到合法位置，别指着一个不存在的地方
    let ids = Set(merged.map(\.id))
    let currentId = old.indices.contains(books[i].position.chapterIndex)
      ? old[books[i].position.chapterIndex].id
      : nil
    if let currentId, ids.contains(currentId) {
      books[i].position.chapterIndex = merged.firstIndex { $0.id == currentId } ?? 0
    } else if old.isEmpty {
      // 章节从空到非空（恢复备份的在线书第一次拉齐）：position 里的秒数是
      // 恢复时写进来的，别清——目标章节由 resolvePendingChapter 对位
    } else {
      // 那一集从源里没了：章号退回合法范围，章内秒数保留
      books[i].position.chapterIndex = min(
        max(0, books[i].position.chapterIndex),
        max(0, merged.count - 1)
      )
    }
    if books[i].introSkippedChapter >= merged.count { books[i].introSkippedChapter = -1 }
    save()
    DLog("library", "刷新章节 书=\(bookId) 新增=\(added) 总计=\(merged.count)")
    return (added, merged.count)
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

  /// 记录「这本书刚播过」，书架「进行中 / 已完成」按它倒序排（对齐安卓 `lastPlayedAt`）。
  /// 30 秒内重复调用不重复写盘 —— 位置本身每 10 秒就会存一次。
  func touchLastPlayed(bookId: String) {
    guard let i = index(of: bookId) else { return }
    let now = Date()
    if let last = books[i].lastPlayedAt, now.timeIntervalSince(last) < 30 { return }
    books[i].lastPlayedAt = now
    save(delay: 2)
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

  /// 写入从音频文件里读出来的内嵌章节（m4b / mp4 的章节轨 → markData）
  func updateChapterMarks(bookId: String, marks: [String: [MarkData]]) {
    guard let i = index(of: bookId) else { return }
    var changed = false
    for c in 0..<books[i].chapters.count {
      guard let data = marks[books[i].chapters[c].id], !data.isEmpty else { continue }
      guard books[i].chapters[c].markData != data else { continue }
      books[i].chapters[c].markData = data
      changed = true
    }
    // 扫过就标一下，下次启动不重复开文件
    if !books[i].marksScanned {
      books[i].marksScanned = true
      changed = true
    }
    if changed {
      save(delay: 1)
      DLog("library", "写入内嵌章节 书=\(bookId) 命中章数=\(marks.count)")
    }
  }
}
