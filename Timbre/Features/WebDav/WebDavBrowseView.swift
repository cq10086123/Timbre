import SwiftUI

// MARK: - 远程目录浏览与导入

struct WebDavBrowseView: View {
  @EnvironmentObject private var webdav: WebDavStore
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var app: AppState
  @Environment(\.dismiss) private var dismiss

  var server: WebDavServer

  /// 显式构造器：带 private 属性包装器的 struct 其 memberwise init 是私有的，跨文件无法调用
  init(server: WebDavServer) {
    self.server = server
  }

  struct Crumb: Identifiable, Hashable {
    var url: URL
    var title: String
    var id: String { url.absoluteString }
  }

  @State private var stack: [Crumb] = []
  @State private var items: [WebDavResource] = []
  @State private var loading = false
  @State private var errorText: String?
  @State private var started = false
  /// 「子文件夹各作为一本书」的扫描进度；total > 0 = 正在扫
  @State private var scanDone = 0
  @State private var scanTotal = 0

  private var current: Crumb? { stack.last }

  private var audioFiles: [WebDavResource] {
    WebDavAudio.sorted(items.filter { !$0.isDirectory && WebDavAudio.isAudio($0.name) })
  }

  private var subdirectories: [WebDavResource] {
    items.filter { $0.isDirectory }
  }

  var body: some View {
    VStack(spacing: 0) {
      topBar
      content
      bottomBar
    }
    .background(AppColor.background.ignoresSafeArea())
    .onAppear(perform: start)
  }

  // MARK: 顶栏

  private var topBar: some View {
    HStack(spacing: 0) {
      IconButton48(
        systemName: stack.count > 1 ? "chevron.left" : "xmark",
        label: "返回"
      ) {
        if stack.count > 1 {
          stack.removeLast()
          reload()
        } else {
          dismiss()
        }
      }
      VStack(alignment: .leading, spacing: 0) {
        Text(current?.title ?? server.name)
          .font(AppFont.titleMedium())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(1)
        Text(server.name)
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
          .lineLimit(1)
      }
      Spacer(minLength: 8)
      IconButton48(systemName: "arrow.clockwise", label: "刷新") { reload() }
    }
    .padding(.horizontal, 4)
    .padding(.vertical, 4)
    .frame(minHeight: 64)
  }

  // MARK: 内容

  @ViewBuilder
  private var content: some View {
    if loading {
      HStack {
        Spacer()
        ProgressView()
        Spacer()
      }
      .frame(maxHeight: .infinity)
    } else if let errorText {
      VStack(spacing: 12) {
        Image(systemName: "exclamationmark.triangle")
          .font(.system(size: 40))
          .foregroundColor(AppColor.onSurfaceVariant)
        Text(errorText)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .multilineTextAlignment(.center)
          .fixedSize(horizontal: false, vertical: true)
          .padding(.horizontal, 32)
        Button("重试") { reload() }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 20)
          .frame(height: 44)
          .overlay(Capsule().stroke(AppColor.outlineVariant, lineWidth: 1))
          .buttonStyle(.plain)
      }
      .frame(maxHeight: .infinity)
    } else if items.isEmpty {
      VStack(spacing: 8) {
        Text("这个目录是空的")
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
      .frame(maxHeight: .infinity)
    } else {
      ScrollView {
        VStack(spacing: 0) {
          ForEach(items) { item in
            row(item)
          }
          Spacer().frame(height: 24)
        }
      }
    }
  }

  private func row(_ item: WebDavResource) -> some View {
    HStack(spacing: 0) {
      Image(systemName: item.isDirectory ? "folder" : "waveform")
        .font(.system(size: 22))
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(width: 24, height: 24)
        .padding(.trailing, 16)

      VStack(alignment: .leading, spacing: 2) {
        Text(item.name)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(1)
        if !item.isDirectory {
          Text(byteText(item.contentLength))
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
      }

      Spacer(minLength: 8)

      if item.isDirectory {
        Image(systemName: "chevron.right")
          .font(.system(size: 14))
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.trailing, 16)
      }
    }
    .padding(.leading, 16)
    .padding(.vertical, 10)
    .frame(minHeight: 56)
    .contentShape(Rectangle())
    .onTapGesture {
      guard item.isDirectory, let url = URL(string: item.url) else { return }
      stack.append(Crumb(url: url, title: item.name))
      reload()
    }
  }

  // MARK: 底部导入（对齐安卓双模式：「此文件夹作为一本书」「子文件夹各作为一本书」）

  private var bottomBar: some View {
    VStack(spacing: 0) {
      Rectangle()
        .fill(AppColor.outlineVariant.opacity(0.4))
        .frame(height: 1)
      if scanTotal > 0 {
        HStack {
          Spacer()
          Text("正在扫描书籍 \(scanDone)/\(scanTotal) …")
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
      } else if app.durationAnalyzer.totalProgress.total > 0 {
        // 导入后的逐章时长解析（导入并发度），书在后台排队解，离开本页也不中断
        let p = app.durationAnalyzer.totalProgress
        HStack {
          Spacer()
          Text("正在解析章节 \(p.done)/\(p.total) …")
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
      } else if audioFiles.isEmpty && subdirectories.isEmpty {
        HStack {
          Text("这个目录里没有音频文件")
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          Spacer()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 14)
      } else {
        HStack(spacing: 10) {
          importButton(title: "此文件夹作为一本书", enabled: !audioFiles.isEmpty) {
            importSingleBook()
          }
          importButton(title: "子文件夹各作为一本书", enabled: !subdirectories.isEmpty || !audioFiles.isEmpty) {
            importAsLibraryRoot()
          }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
      }
    }
  }

  private func importButton(title: String, enabled: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Text(title)
        .font(AppFont.labelLarge())
        .foregroundColor(enabled ? AppColor.primary : AppColor.onSurfaceVariant)
        .lineLimit(1)
        .minimumScaleFactor(0.7)
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, minHeight: 44)
        .background(
          Capsule().fill(enabled ? AppColor.primary.opacity(0.12) : AppColor.outlineVariant.opacity(0.2))
        )
    }
    .buttonStyle(.plain)
    .disabled(!enabled)
  }

  // MARK: 行为

  private func start() {
    guard !started else { return }
    started = true
    guard let root = URL(string: server.normalizedBase + "/") else {
      errorText = WebDavError.badURL.localizedDescription
      return
    }
    stack = [Crumb(url: root, title: server.name)]
    reload()
  }

  private func reload() {
    guard let target = current else { return }
    loading = true
    errorText = nil
    let password = webdav.password(for: server.id)
    let url = target.url
    Task {
      do {
        let list = try await WebDavClient.list(url: url, server: server, password: password)
        let sorted = list.sorted { left, right in
          if left.isDirectory != right.isDirectory { return left.isDirectory }
          return NaturalSort.compare(left.name, right.name) == .orderedAscending
        }
        await MainActor.run {
          items = sorted
          loading = false
        }
      } catch {
        let text = error.localizedDescription
        await MainActor.run {
          items = []
          errorText = text
          loading = false
        }
      }
    }
  }

  /// 单本导入：当前目录里的音频 = 一本书的章节
  private func importSingleBook() {
    guard let crumb = current else { return }
    let files = audioFiles
    guard !files.isEmpty else { return }

    let bookId = "webdav:\(server.id):\(crumb.url.path)"
    var chapters: [Chapter] = []
    for file in files {
      chapters.append(
        Chapter(
          id: "\(bookId)#\(file.url)",
          title: file.name,
          remoteURL: file.url,
          duration: 0
        )
      )
    }

    let book = Book(
      id: bookId,
      title: crumb.title,
      author: "",
      kind: .webdav,
      chapters: chapters,
      sourceName: server.name,
      webdavServerId: server.id
    )
    addOrUpdate(book)

    // 封面：目录图片文件优先，其次第一段音频的内嵌封面（不阻塞返回）
    let password = webdav.password(for: server.id)
    let library = self.library
    let listing = items
    let target = library.books.first(where: { $0.id == bookId }) ?? book
    if target.coverFile == nil {
      Task {
        if let path = await WebDavCoverFetcher.fetch(
          book: target,
          server: server,
          password: password,
          listing: listing
        ) {
          await MainActor.run { library.setCover(bookId: bookId, coverFile: path) }
        }
      }
    }
    // 导入后逐章解析时长（导入并发度设置），后台排队跑，离开本页也不中断
    app.durationAnalyzer.analyze(book: book, server: server, password: password)
    dismiss()
  }

  /// 批量导入：当前目录的每个子文件夹各作为一本书（子文件夹里的音频 = 章节），
  /// 顶层散音频各作为一本单章书。对齐安卓 `WebDavBookSource.Mode.LibraryRoot` 的展开语义。
  private func importAsLibraryRoot() {
    guard let _ = current, scanTotal == 0 else { return }
    let dirs = subdirectories
    let files = audioFiles
    guard !dirs.isEmpty || !files.isEmpty else { return }

    var jobs: [(name: String, url: String, isDir: Bool)] = dirs.map { ($0.name, $0.url, true) }
    jobs += files.map { ($0.name, $0.url, false) }
    scanTotal = jobs.count
    scanDone = 0

    let password = webdav.password(for: server.id)
    let library = self.library

    Task {
      var collected: [(book: Book, listing: [WebDavResource]?)] = []
      for (index, job) in jobs.enumerated() {
        defer {
          let done = index + 1
          Task { @MainActor in scanDone = done }
        }

        var chapters: [Chapter] = []
        var listing: [WebDavResource]? = nil
        let title = job.isDir ? job.name : ((job.name as NSString).deletingPathExtension)

        if job.isDir, let url = URL(string: job.url) {
          // 逐个 PROPFIND 子目录：NAS 一次大并发容易把服务打挂，串行最稳
          if let list = try? await WebDavClient.list(url: url, server: server, password: password) {
            listing = list
            let audios = WebDavAudio.sorted(
              list.filter { !$0.isDirectory && WebDavAudio.isAudio($0.name) }
            )
            let bookId = "webdav:\(server.id):\(url.path)"
            chapters = audios.map { file in
              Chapter(
                id: "\(bookId)#\(file.url)",
                title: file.name,
                remoteURL: file.url,
                duration: 0
              )
            }
          } else {
            DLog("webdav", "批量导入：子目录列举失败，跳过 \(job.name)")
          }
        } else if let url = URL(string: job.url) {
          let bookId = "webdav:\(server.id):\(url.path)"
          chapters = [
            Chapter(
              id: "\(bookId)#\(job.url)",
              title: title,
              remoteURL: job.url,
              duration: 0
            )
          ]
        }

        guard !chapters.isEmpty, let bookURL = URL(string: job.url) else { continue }
        let book = Book(
          id: "webdav:\(server.id):\(bookURL.path)",
          title: title,
          author: "",
          kind: .webdav,
          chapters: chapters,
          sourceName: server.name,
          webdavServerId: server.id
        )
        collected.append((book, listing))
      }

      let finished = collected
      await MainActor.run {
        for entry in finished {
          self.addOrUpdate(entry.book)
        }
        // 每本书排队解析章节时长（一次只解一本，书内按并发度）
        for entry in finished {
          self.app.durationAnalyzer.analyze(book: entry.book, server: server, password: password)
        }
        self.scanTotal = 0
        self.dismiss()
      }

      // 封面：每本没有封面的书都试一次（目录图片优先，其次内嵌 artwork）
      for entry in finished {
        let target = await MainActor.run {
          library.books.first(where: { $0.id == entry.book.id }) ?? entry.book
        }
        if let path = await WebDavCoverFetcher.fetch(
          book: target,
          server: server,
          password: password,
          listing: entry.listing
        ) {
          await MainActor.run { library.setCover(bookId: entry.book.id, coverFile: path) }
        }
      }
    }
  }

  /// 重新导入同一目录/书时保留进度、书签与个性化设置
  @MainActor
  private func addOrUpdate(_ book: Book) {
    if let old = library.books.first(where: { $0.id == book.id }) {
      var updated = book
      updated.position = old.position
      updated.bookmarks = old.bookmarks
      updated.skipIntro = old.skipIntro
      updated.skipOutro = old.skipOutro
      updated.speed = old.speed
      updated.addedAt = old.addedAt
      updated.coverFile = old.coverFile
      library.update(updated)
    } else {
      library.add(book)
    }
  }

  private func byteText(_ bytes: Int64) -> String {
    guard bytes > 0 else { return "大小未知" }
    return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
  }
}
