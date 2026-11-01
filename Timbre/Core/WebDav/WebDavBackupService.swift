import Foundation

/// 把书架写进 WebDAV 上的备份文件，或把它读回来合并到本机。
///
/// 对齐安卓 `WebDavBackupService`：
/// - 备份只写「书 + 听到哪 + 播放偏好」，音频文件永远不进备份；
/// - 恢复只合并**本机已有**的书，按 id 匹配，id 变了就用「书名|章节数|总时长」兜底；
/// - 备份里有、本机没有的 WebDAV 书单独计数，让界面说清楚「先把书源加回来」。
final class WebDavBackupService {
  private let library: LibraryStore
  private let servers: WebDavStore
  private let targets: WebDavBackupStore

  init(library: LibraryStore, servers: WebDavStore, targets: WebDavBackupStore) {
    self.library = library
    self.servers = servers
    self.targets = targets
  }

  // MARK: - 目标

  func location() -> WebDavBackupLocation? {
    let target = targets.target
    guard target.isConfigured,
      let serverId = target.serverId,
      let folderUrl = target.folderUrl,
      let server = servers.server(withId: serverId)
    else { return nil }
    return WebDavBackupLocation(
      serverId: server.id,
      serverName: server.name,
      serverBaseUrl: server.normalizedBase,
      folderUrl: folderUrl
    )
  }

  func targetStatus() -> WebDavBackupTargetStatus {
    let target = targets.target
    guard let serverId = target.serverId, !serverId.isEmpty,
      servers.server(withId: serverId) != nil
    else { return .noServer }
    guard let folderUrl = target.folderUrl, !folderUrl.isEmpty else { return .noFolder }
    return .ready
  }

  func setLocation(serverId: String, folderUrl: String) {
    var next = targets.target
    next.serverId = serverId
    next.folderUrl = folderUrl
    targets.save(next)
    DLog("webdav", "备份目录已设置 \(folderUrl)")
  }

  func rememberLabel(_ label: String?) {
    targets.rememberLabel(label)
  }

  var lastLabel: String? { targets.lastLabel }

  // MARK: - 备份

  func backup(label: String?) async -> WebDavBackupResult {
    guard let resolved = resolveTarget() else {
      return targetStatus() == .noFolder ? .noFolder : .noServer
    }
    let books = library.books
    guard !books.isEmpty else { return .emptyShelf }
    guard let url = backupURL(resolved.location, label: label) else {
      return .failure("备份目录地址不合法")
    }

    let payload = WebDavBackup(
      createdAt: Self.isoString(Date()),
      books: books.map { entry(for: $0) }
    )
    let data: Data
    do {
      data = try JSONCoding.encoder().encode(payload)
    } catch {
      return .failure("组装备份失败：\(error.localizedDescription)")
    }

    do {
      try await WebDavClient.put(
        url: url,
        server: resolved.server,
        password: resolved.password,
        data: data,
        contentType: WebDavBackup.contentType
      )
      let cleanLabel = label?.trimmingCharacters(in: .whitespacesAndNewlines)
      DLog("webdav", "备份完成 \(books.count) 本 → \(url.lastPathComponent)")
      return .success(
        bookCount: books.count,
        serverName: resolved.location.serverName,
        label: (cleanLabel?.isEmpty ?? true) ? nil : cleanLabel
      )
    } catch {
      DLog("webdav", "备份失败：\(error.localizedDescription)")
      return .failure(error.localizedDescription)
    }
  }

  // MARK: - 恢复

  func restore(label: String?) async -> WebDavRestoreResult {
    guard let resolved = resolveTarget() else {
      return targetStatus() == .noFolder ? .noFolder : .noServer
    }
    guard let url = backupURL(resolved.location, label: label) else {
      return .failure("备份目录地址不合法")
    }

    let data: Data?
    do {
      data = try await WebDavClient.get(
        url: url,
        server: resolved.server,
        password: resolved.password
      )
    } catch {
      return .failure(error.localizedDescription)
    }
    guard let data, !data.isEmpty else { return .noBackupFile }

    let payload: WebDavBackup
    do {
      payload = try JSONCoding.decoder().decode(WebDavBackup.self, from: data)
    } catch {
      DLog("webdav", "备份文件解析失败：\(error.localizedDescription)")
      return .failure("备份文件读不出来：\(error.localizedDescription)")
    }

    let local = library.books
    var byId: [String: Book] = [:]
    for book in local where byId[book.id] == nil { byId[book.id] = book }
    var byFallback: [String: Book] = [:]
    for book in local where byFallback[Self.fallbackKey(book)] == nil {
      byFallback[Self.fallbackKey(book)] = book
    }

    var restored = 0
    var missingWebDav = 0
    for entry in payload.books {
      guard var book = byId[entry.id] ?? byFallback[Self.fallbackKey(entry)] else {
        // WebDAV 书没法在这儿凭空造出来：章节来自服务器，得先把书源加回来
        if entry.id.hasPrefix("http://") || entry.id.hasPrefix("https://") {
          missingWebDav += 1
        }
        continue
      }
      apply(entry, to: &book)
      library.update(book)
      restored += 1
    }

    let cleanLabel = label?.trimmingCharacters(in: .whitespacesAndNewlines)
    DLog("webdav", "恢复完成 \(restored)/\(payload.books.count) 本，缺 WebDAV \(missingWebDav) 本")
    return .success(
      restored: restored,
      total: payload.books.count,
      serverName: resolved.location.serverName,
      label: (cleanLabel?.isEmpty ?? true) ? nil : cleanLabel,
      missingWebDav: missingWebDav
    )
  }

  // MARK: - 列表

  func backups() async -> WebDavBackupListResult {
    guard let resolved = resolveTarget() else {
      return targetStatus() == .noFolder ? .noFolder : .noServer
    }
    guard let folderURL = URL(string: resolved.location.folderUrl) else {
      return .failure("备份目录地址不合法")
    }
    do {
      let items = try await WebDavClient.list(
        url: folderURL,
        server: resolved.server,
        password: resolved.password
      )
      let backups = items.compactMap { resource -> WebDavBackupItem? in
        // URL 才是权威：DAV:displayname 是服务器想叫什么叫什么，不一定是文件名
        let fileName =
          URL(string: resource.url)?.lastPathComponent
          ?? resource.name
        guard WebDavBackup.isBackupFile(fileName) else { return nil }
        return WebDavBackupItem(
          label: WebDavBackup.label(of: fileName),
          fileName: fileName,
          lastModified: resource.lastModified,
          size: resource.contentLength
        )
      }
      .sorted { $0.lastModified > $1.lastModified }
      return .success(items: backups, serverName: resolved.location.serverName)
    } catch {
      DLog("webdav", "读取备份列表失败：\(error.localizedDescription)")
      return .failure(error.localizedDescription)
    }
  }

  // MARK: - 内部

  private struct ResolvedTarget {
    var server: WebDavServer
    var password: String
    var location: WebDavBackupLocation
  }

  private func resolveTarget() -> ResolvedTarget? {
    guard let location = location(), let server = servers.server(withId: location.serverId) else {
      return nil
    }
    return ResolvedTarget(
      server: server,
      password: servers.password(for: server.id),
      location: location
    )
  }

  private func backupURL(_ location: WebDavBackupLocation, label: String?) -> URL? {
    var folder = location.folderUrl
    guard !folder.isEmpty else { return nil }
    if !folder.hasSuffix("/") { folder += "/" }
    let name = WebDavBackup.fileName(label: label)
    let encoded =
      name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? name
    return URL(string: folder + encoded)
  }

  private func entry(for book: Book) -> WebDavBackupBook {
    let index = book.chapters.isEmpty
      ? 0
      : min(max(0, book.position.chapterIndex), book.chapters.count - 1)
    let chapterId = book.chapters.indices.contains(index) ? book.chapters[index].id : ""
    let lastPlayed = book.lastPlayedAt ?? book.position.updatedAt ?? book.addedAt
    return WebDavBackupBook(
      id: book.id,
      name: book.title,
      author: book.author.isEmpty ? nil : book.author,
      isActive: true,
      currentChapter: chapterId,
      positionInChapter: Int64((max(0, book.position.seconds) * 1000).rounded()),
      lastPlayedAt: Self.isoString(lastPlayed),
      playbackSpeed: Float(book.speed),
      skipSilence: book.skipSilence,
      skipIntro: Int64(book.skipIntro),
      skipOutro: Int64(book.skipOutro),
      gain: Float(book.loudnessGain),
      chapterCount: book.chapters.count,
      totalDurationMs: Int64((book.totalDuration * 1000).rounded())
    )
  }

  /// 存着的那一集可能已经没了（文件被换掉），那时只有第一章的位置是安全的
  private func apply(_ entry: WebDavBackupBook, to book: inout Book) {
    guard !book.chapters.isEmpty else { return }
    let index = book.chapters.firstIndex { $0.id == entry.currentChapter } ?? 0
    let seconds = max(0, Double(entry.positionInChapter) / 1000)
    let playedAt = Self.date(from: entry.lastPlayedAt)
    book.position = PlaybackPosition(
      chapterIndex: index,
      seconds: seconds,
      updatedAt: playedAt
    )
    book.speed = Double(entry.playbackSpeed) > 0 ? Double(entry.playbackSpeed) : 1.0
    book.skipSilence = entry.skipSilence
    book.skipIntro = max(0, Int(entry.skipIntro))
    book.skipOutro = max(0, Int(entry.skipOutro))
    book.loudnessGain = Double(entry.gain)
    // 写完位置会被盖成「现在」，把备份里的时间写回去才能保住最近播放的排序
    book.lastPlayedAt = playedAt ?? book.lastPlayedAt
  }

  private static func fallbackKey(_ book: Book) -> String {
    "\(book.title)|\(book.chapters.count)|\(Int64((book.totalDuration * 1000).rounded()))"
  }

  private static func fallbackKey(_ entry: WebDavBackupBook) -> String {
    "\(entry.name)|\(entry.chapterCount)|\(entry.totalDurationMs)"
  }

  private static func isoString(_ date: Date) -> String {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    return formatter.string(from: date)
  }

  private static func date(from text: String) -> Date? {
    let formatter = ISO8601DateFormatter()
    formatter.formatOptions = [.withInternetDateTime]
    if let date = formatter.date(from: text) { return date }
    // 安卓的 Instant.toString() 有时带小数秒
    formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
    return formatter.date(from: text)
  }
}
