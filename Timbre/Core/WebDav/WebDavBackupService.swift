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
  /// 在线书恢复要用：按 sourceId 找接口源、现场拉章节
  private let extensions: ExtensionStore

  init(
    library: LibraryStore, servers: WebDavStore, targets: WebDavBackupStore,
    extensions: ExtensionStore
  ) {
    self.library = library
    self.servers = servers
    self.targets = targets
    self.extensions = extensions
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

    var payload = WebDavBackup(
      createdAt: Self.isoString(Date()),
      books: books.map { entry(for: $0) }
    )
    // 在线书进 extras.online，格式与安卓 OnlineShelfBackupContributor 一致，
    // 这样安卓也能恢复 iOS 的在线书
    let online = books.filter { $0.kind == .jdr }.compactMap { onlineEntry(for: $0) }
    if !online.isEmpty { payload.extras = ["online": online] }
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
    // 跨端匹配：安卓备份里 WebDAV 书的 id 是目录 URL（去尾斜杠、百分号编码）。
    // 本机 WebDAV 书的目录 URL 从章节 remoteURL 的上级目录推导，与 book.id 的格式无关。
    // 同一台 NAS 常有内网/外网两套地址（host:port 不同、路径相同），
    // 所以完整 URL 之外再按「解码后的路径」建一份索引。
    var byRemoteFolder: [String: Book] = [:]
    var byRemotePath: [String: Book] = [:]
    for book in local where book.kind == .webdav {
      guard let folder = Self.remoteFolderURL(of: book) else { continue }
      if let normalized = Self.normalizeRemoteUrl(folder) {
        if byRemoteFolder[normalized] == nil { byRemoteFolder[normalized] = book }
        let decoded = normalized.removingPercentEncoding ?? normalized
        if byRemoteFolder[decoded] == nil { byRemoteFolder[decoded] = book }
      }
      if let path = Self.remotePath(of: folder), byRemotePath[path] == nil {
        byRemotePath[path] = book
      }
    }
    var byFallback: [String: Book] = [:]
    for book in local where byFallback[Self.fallbackKey(book)] == nil {
      byFallback[Self.fallbackKey(book)] = book
    }

    var restored = 0
    var incomplete = 0
    var missingWebDav = 0
    for entry in payload.books {
      let entryFolder = Self.remotePath(of: entry.id) ?? entry.id
      var matched =
        byId[entry.id]
        ?? byRemoteFolder[entry.id]
        ?? byRemotePath[entryFolder]
        ?? byFallback[Self.fallbackKey(entry)]
      if matched == nil, let decoded = entry.id.removingPercentEncoding {
        matched = byRemoteFolder[decoded]
      }
      guard var book = matched else {
        // WebDAV 书没法在这儿凭空造出来：章节来自服务器，得先把书源加回来
        if entry.id.hasPrefix("http://") || entry.id.hasPrefix("https://") {
          missingWebDav += 1
        }
        continue
      }
      if apply(entry, to: &book) {
        library.update(book)
        restored += 1
      } else {
        // 本机有这本书但章节还没就绪（导入没完成）：不静默吞掉，
        // 对齐安卓——明说这本书的进度没有合上
        incomplete += 1
        DLog("webdav", "「\(entry.name)」章节还没就绪，进度未恢复（等导入完成后再恢复一次）")
      }
    }

    // 在线书：安卓 extras.online。不在 books 里，单独按 source::bookId 上架。
    // 只记账不拉章节（对齐安卓：章节第一次打开时才从接口源拉）。
    let onlineEntries = payload.extras?["online"] ?? []
    let onlineRestored = restoreOnline(onlineEntries)

    let cleanLabel = label?.trimmingCharacters(in: .whitespacesAndNewlines)
    DLog(
      "webdav",
      "恢复完成 \(restored + onlineRestored)/\(payload.books.count + onlineEntries.count) 本，缺 WebDAV \(missingWebDav) 本，章节未就绪 \(incomplete) 本"
    )
    return .success(
      restored: restored + onlineRestored,
      total: payload.books.count + onlineEntries.count,
      serverName: resolved.location.serverName,
      label: (cleanLabel?.isEmpty ?? true) ? nil : cleanLabel,
      missingWebDav: missingWebDav,
      incomplete: incomplete
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
    let chapter = book.chapters.indices.contains(index) ? book.chapters[index] : nil
    var id = book.id
    var currentChapter = chapter?.id ?? ""
    // 跨端一致的键：WebDAV 书在安卓那边 id 就是目录 URL（去尾斜杠），
    // currentChapter 就是音频文件 URL。按这个格式写，备份才能两端互相恢复。
    if book.kind == .webdav, let chapter,
      let fileURL = chapter.remoteURL.flatMap({ URL(string: $0) }) {
      let folder = fileURL.deletingLastPathComponent().absoluteString
      if let normalized = Self.normalizeRemoteUrl(folder) { id = normalized }
      currentChapter = fileURL.absoluteString
    }
    let lastPlayed = book.lastPlayedAt ?? book.position.updatedAt ?? book.addedAt
    return WebDavBackupBook(
      id: id,
      name: book.title,
      author: book.author.isEmpty ? nil : book.author,
      isActive: true,
      currentChapter: currentChapter,
      positionInChapter: Int64((max(0, book.position.seconds) * 1000).rounded()),
      lastPlayedAt: Self.isoString(lastPlayed),
      playbackSpeed: Float(book.speed),
      skipSilence: book.skipSilence,
      // iOS 存秒，备份格式（安卓）要毫秒
      skipIntro: Int64(book.skipIntro) * 1000,
      skipOutro: Int64(book.skipOutro) * 1000,
      gain: Float(book.loudnessGain),
      chapterCount: book.chapters.count,
      totalDurationMs: Int64((book.totalDuration * 1000).rounded())
    )
  }

  /// 存着的那一集可能已经没了（文件被换掉），那时只有第一章的位置是安全的。
  /// 返回 false = 这本书章节还没就绪，进度没法合上。
  @discardableResult
  private func apply(_ entry: WebDavBackupBook, to book: inout Book) -> Bool {
    guard !book.chapters.isEmpty else { return false }
    // 优先按本端章节 id 对（iOS↔iOS）；跨端时安卓的 currentChapter 是音频文件 URL，
    // 对本端 remoteURL（完整 URL 或解码路径，主机地址内网外网不同也能对上）
    let index =
      book.chapters.firstIndex { $0.id == entry.currentChapter }
      ?? book.chapters.firstIndex { Self.chapterMatches($0, entryChapter: entry.currentChapter) }
      ?? 0
    let seconds = max(0, Double(entry.positionInChapter) / 1000)
    let playedAt = Self.date(from: entry.lastPlayedAt)
    book.position = PlaybackPosition(
      chapterIndex: index,
      seconds: seconds,
      updatedAt: playedAt
    )
    book.speed = Double(entry.playbackSpeed) > 0 ? Double(entry.playbackSpeed) : 1.0
    book.skipSilence = entry.skipSilence
    // 安卓侧单位是毫秒，iOS 的 skipIntro/skipOutro 是秒，必须换算
    book.skipIntro = max(0, Int(entry.skipIntro) / 1000)
    book.skipOutro = max(0, Int(entry.skipOutro) / 1000)
    book.loudnessGain = Double(entry.gain)
    // 写完位置会被盖成「现在」，把备份里的时间写回去才能保住最近播放的排序
    book.lastPlayedAt = playedAt ?? book.lastPlayedAt
    return true
  }

  // MARK: - 在线书（extras.online，对齐安卓 OnlineShelfBackupContributor）

  /// iOS 在线书 → 安卓 OnlineBackupBook 格式（时间字段一律毫秒）
  private func onlineEntry(for book: Book) -> OnlineBackupEntry? {
    guard let sourceId = book.sourceId, !sourceId.isEmpty,
      let remoteBookId = book.remoteBookId, !remoteBookId.isEmpty
    else { return nil }
    let index = book.chapters.isEmpty
      ? 0
      : min(max(0, book.position.chapterIndex), book.chapters.count - 1)
    let chapter = book.chapters.indices.contains(index) ? book.chapters[index] : nil
    // 封面：本地文件 base64 进备份（≤512KB，与安卓同一上限）
    var coverImage: String?
    if let coverFile = book.coverFile,
      let data = try? Data(contentsOf: AppPaths.url(for: coverFile)),
      !data.isEmpty, data.count <= 512 * 1024
    {
      coverImage = data.base64EncodedString()
    }
    return OnlineBackupEntry(
      source: sourceId,
      bookId: remoteBookId,
      title: book.title,
      author: book.author,
      cover: "",
      coverUrl: nil,
      coverImage: coverImage,
      addedAt: Int64((book.addedAt.timeIntervalSince1970 * 1000).rounded()),
      currentChapterId: chapter?.id ?? "",
      positionInChapter: Int64((max(0, book.position.seconds) * 1000).rounded()),
      skipIntro: Int64(book.skipIntro) * 1000,
      skipOutro: Int64(book.skipOutro) * 1000,
      skipSilence: book.skipSilence,
      playbackSpeed: Float(book.speed),
      gain: Float(book.loudnessGain)
    )
  }

  /// 恢复 extras.online：只把书记回书架（source::bookId + 目标章节 id + 进度 + 偏好），
  /// **不现场拉章节** —— 对齐安卓 OnlineShelfBackupContributor：章节列表不在备份里，
  /// 也不在恢复时拉，而是第一次打开这本书时才从接口源拉
  /// （打开路径走 Book.needsChapterFetch → AppState 拉齐后解析 pendingChapterId）。
  /// 返回恢复成功的本数。
  private func restoreOnline(_ entries: [OnlineBackupEntry]) -> Int {
    guard !entries.isEmpty else { return 0 }
    var restored = 0
    for entry in entries {
      guard let sourceId = entry.source, !sourceId.isEmpty,
        let remoteBookId = entry.bookId, !remoteBookId.isEmpty
      else {
        DLog("webdav", "在线书「\(entry.title ?? "-")」跳过：source / bookId 缺失")
        continue
      }
      // 已在书架上（同源 + 同书 id）合并进度；不在才新建
      if var existing = library.books.first(where: {
        $0.kind == .jdr && $0.sourceId == sourceId && $0.remoteBookId == remoteBookId
      }) {
        applyOnline(entry, to: &existing)
        library.update(existing)
      } else {
        var book = Book(
          title: entry.title ?? "",
          author: entry.author ?? "",
          kind: .jdr,
          chapters: [],
          addedAt: Self.millisToDate(entry.addedAt) ?? Date(),
          position: PlaybackPosition(
            chapterIndex: 0,
            seconds: max(0, Double(entry.positionInChapter ?? 0) / 1000),
            updatedAt: Self.millisToDate(entry.addedAt)
          ),
          skipIntro: max(0, Int(entry.skipIntro ?? 0) / 1000),
          skipOutro: max(0, Int(entry.skipOutro ?? 0) / 1000),
          speed: Double(entry.playbackSpeed ?? 0) > 0 ? Double(entry.playbackSpeed ?? 1) : 1.0,
          loudnessGain: Double(entry.gain ?? 0),
          skipSilence: entry.skipSilence ?? false,
          sourceId: sourceId,
          // 源已卸载也照常上架（对齐安卓），打开时再报「找不到接口源」
          sourceName: extensions.source(withId: sourceId)?.displayName,
          remoteBookId: remoteBookId,
          pendingChapterId: entry.currentChapterId?.isEmpty == false ? entry.currentChapterId : nil
        )
        restoreCover(entry, into: &book)
        library.add(book)
      }
      restored += 1
    }
    return restored
  }

  /// 在线书进度合并。章节列表在手且能对上备份的目标章节就直接定位；
  /// 对不上（列表为空或过时）先记账 pendingChapterId，打开时拉齐章节再换算。
  private func applyOnline(_ entry: OnlineBackupEntry, to book: inout Book) {
    let seconds = max(0, Double(entry.positionInChapter ?? 0) / 1000)
    let playedAt = Self.millisToDate(entry.addedAt)
    if let pending = entry.currentChapterId, !pending.isEmpty,
      let index = book.chapters.firstIndex(where: { $0.id == pending })
    {
      book.position = PlaybackPosition(chapterIndex: index, seconds: seconds, updatedAt: playedAt)
      book.pendingChapterId = nil
    } else {
      book.pendingChapterId =
        entry.currentChapterId?.isEmpty == false ? entry.currentChapterId : nil
      book.position = PlaybackPosition(
        chapterIndex: book.position.chapterIndex,
        seconds: seconds,
        updatedAt: playedAt
      )
    }
    let speed = Double(entry.playbackSpeed ?? 0)
    if speed > 0 { book.speed = speed }
    // 安卓侧单位是毫秒，iOS 的 skipIntro/skipOutro 是秒，必须换算
    book.skipIntro = max(0, Int(entry.skipIntro ?? 0) / 1000)
    book.skipOutro = max(0, Int(entry.skipOutro ?? 0) / 1000)
    book.skipSilence = entry.skipSilence ?? book.skipSilence
    book.loudnessGain = Double(entry.gain ?? 0)
  }

  /// 封面：base64 写进 Covers/ 并挂到书上
  private func restoreCover(_ entry: OnlineBackupEntry, into book: inout Book) {
    guard let base64 = entry.coverImage,
      let data = Data(base64Encoded: base64), !data.isEmpty
    else { return }
    let name = "\(book.id).jpg"
    do {
      try data.write(to: AppPaths.coversDir.appendingPathComponent(name))
    } catch {
      DLog("webdav", "在线书封面写入失败：\(error.localizedDescription)")
      return
    }
    book.coverFile = "Covers/\(name)"
    CoverStore.clear()
  }

  private static func millisToDate(_ millis: Int64?) -> Date? {
    guard let millis, millis > 0 else { return nil }
    return Date(timeIntervalSince1970: Double(millis) / 1000)
  }

  // MARK: - 跨端 URL 对齐（对齐安卓 RemoteUrl.normalizeRemoteUrl / WebDAV 书 id 语义）

  /// 安卓 `normalizeRemoteUrl`：http(s) 目录 URL 去掉尾斜杠（"http://" 本身保留）
  static func normalizeRemoteUrl(_ value: String) -> String? {
    guard let sep = value.range(of: "://") else { return value }
    let scheme = value[..<sep.lowerBound]
    guard scheme.lowercased() == "http" || scheme.lowercased() == "https" else { return value }
    if value.contains("?") || value.contains("#") { return value }
    var trimmed = value
    while trimmed.hasSuffix("/") { trimmed.removeLast() }
    let minLength = value.distance(from: value.startIndex, to: sep.upperBound)
    return trimmed.count <= minLength ? value : trimmed
  }

  /// 书的 WebDAV 目录 URL：从章节 remoteURL 的上级目录推导。
  /// 与 book.id 的格式无关，老版本导入的书照样能参与跨端匹配。
  static func remoteFolderURL(of book: Book) -> String? {
    guard
      let remote = book.chapters.first(where: { $0.remoteURL != nil })?.remoteURL,
      let fileURL = URL(string: remote)
    else { return nil }
    return fileURL.deletingLastPathComponent().absoluteString
  }

  /// URL 的解码路径（去掉 scheme 和主机）。同一 NAS 内网/外网地址不同、路径相同，
  /// 路径才是跨地址稳定的部分。
  static func remotePath(of urlText: String) -> String? {
    guard let url = URL(string: urlText), !url.path.isEmpty else { return nil }
    return url.path.removingPercentEncoding ?? url.path
  }

  /// 跨端章节匹配：安卓 currentChapter 是音频文件 URL（百分号编码），
  /// 本端 remoteURL 同源；两边主机可能内网/外网不同，最后按解码路径对。
  static func chapterMatches(_ chapter: Chapter, entryChapter: String) -> Bool {
    guard let remote = chapter.remoteURL else { return false }
    if remote == entryChapter { return true }
    if (remote.removingPercentEncoding ?? remote)
      == (entryChapter.removingPercentEncoding ?? entryChapter) {
      return true
    }
    guard let a = remotePath(of: remote), let b = remotePath(of: entryChapter) else { return false }
    return a == b
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
