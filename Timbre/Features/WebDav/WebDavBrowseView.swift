import SwiftUI

// MARK: - 远程目录浏览与导入

struct WebDavBrowseView: View {
  @EnvironmentObject private var webdav: WebDavStore
  @EnvironmentObject private var library: LibraryStore
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

  private var current: Crumb? { stack.last }

  private var audioFiles: [WebDavResource] {
    WebDavAudio.sorted(items.filter { !$0.isDirectory && WebDavAudio.isAudio($0.name) })
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

  // MARK: 底部导入

  private var bottomBar: some View {
    VStack(spacing: 0) {
      Rectangle()
        .fill(AppColor.outlineVariant.opacity(0.4))
        .frame(height: 1)
      HStack(spacing: 12) {
        if audioFiles.isEmpty {
          Text("这个目录里没有音频文件")
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          Spacer()
        } else {
          Text("\(audioFiles.count) 个音频")
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          Spacer()
          Button {
            importHere()
          } label: {
            Text("导入到书架")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.onPrimary)
              .padding(.horizontal, 24)
              .frame(height: 44)
              .background(AppColor.primary, in: Capsule())
          }
          .buttonStyle(.plain)
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 10)
    }
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

  private func importHere() {
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

    var book = Book(
      id: bookId,
      title: crumb.title,
      author: "",
      kind: .webdav,
      chapters: chapters,
      sourceName: server.name,
      webdavServerId: server.id
    )

    // 重新导入同一目录时保留进度、书签与个性化设置
    if let old = library.books.first(where: { $0.id == bookId }) {
      book.position = old.position
      book.bookmarks = old.bookmarks
      book.skipIntro = old.skipIntro
      book.skipOutro = old.skipOutro
      book.speed = old.speed
      book.addedAt = old.addedAt
      book.coverFile = old.coverFile
      library.update(book)
    } else {
      library.add(book)
    }
    dismiss()
  }

  private func byteText(_ bytes: Int64) -> String {
    guard bytes > 0 else { return "大小未知" }
    return ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
  }
}
