import SwiftUI

/// 从在线搜索结果里选中的一本书（弹章节对话框用）
struct OnlineBookSelection: Identifiable, Equatable {
  var item: SourceSearchItem
  var source: InstalledSource

  var id: String { "\(source.id)#\(item.id)" }

  static func == (lhs: OnlineBookSelection, rhs: OnlineBookSelection) -> Bool {
    lhs.id == rhs.id
  }
}

private let searchDebounce: UInt64 = 500_000_000  // 500ms，对齐安卓 SEARCH_DEBOUNCE_MILLIS

// MARK: - 「在线」段落

/// 安卓 OnlineSearchSection 的 1:1 移植：源 chip + 当前源的搜索结果行。
/// 没有可用接口源时整段不渲染。
struct OnlineSearchSection: View {
  var query: String
  var onSelect: (OnlineBookSelection) -> Void

  @EnvironmentObject private var extensions: ExtensionStore

  @State private var selectedId = ""
  @State private var results: [SourceSearchItem] = []
  @State private var loading = false
  @State private var offline = false
  @State private var failureText: String?
  @State private var reloadKey = 0

  private var sources: [InstalledSource] { extensions.enabledSources }

  private var activeSource: InstalledSource? {
    if let match = sources.first(where: { $0.id == selectedId }) { return match }
    return sources.first
  }

  var body: some View {
    VStack(spacing: 0) {
      Rectangle()
        .fill(AppColor.outlineVariant.opacity(0.4))
        .frame(height: 1)
        .padding(.horizontal, 16)
        .padding(.top, 8)
      chipRow
      content
    }
    .onAppear { syncSelection() }
    .onChange(of: sources.map(\.id)) { _ in syncSelection() }
    .task(id: "\(query)|\(activeSource?.id ?? "")|\(reloadKey)") { await runSearch() }
  }

  private func syncSelection() {
    if !sources.contains(where: { $0.id == selectedId }) {
      selectedId = sources.first?.id ?? ""
    }
  }

  // 源 chip 行：标题「在线」+ 横向滚动的 FilterChip。
  // 没有接口源时也要出这一行——否则界面只是一片空白，用户不知道是「没源」还是「没结果」。
  private var chipRow: some View {
    ScrollView(.horizontal, showsIndicators: false) {
      HStack(spacing: 8) {
        Text("在线")
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .padding(.trailing, 4)
        if sources.isEmpty {
          Text("未导入接口源")
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onSurfaceVariant)
            .padding(.horizontal, 14)
            .frame(height: 32)
            .overlay(
              Capsule().strokeBorder(AppColor.outlineVariant, lineWidth: 1)
            )
        } else {
          ForEach(sources) { source in
            chip(source)
          }
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 4)
    }
    .padding(.top, 12)
  }

  private func chip(_ source: InstalledSource) -> some View {
    let selected = source.id == activeSource?.id
    return Button {
      selectedId = source.id
    } label: {
      HStack(spacing: 6) {
        if selected {
          Image(systemName: "checkmark")
            .font(.system(size: 13, weight: .semibold))
        }
        Text(source.displayName)
          .font(AppFont.labelLarge())
          .lineLimit(1)
      }
      .foregroundColor(selected ? AppColor.onSecondaryContainer : AppColor.onSurfaceVariant)
      .padding(.horizontal, 14)
      .frame(height: 32)
      .background(
        Capsule().fill(selected ? AppColor.secondaryContainer : Color.clear)
      )
      .overlay(
        Capsule().strokeBorder(
          selected ? Color.clear : AppColor.outlineVariant,
          lineWidth: 1
        )
      )
    }
    .buttonStyle(.plain)
  }

  @ViewBuilder
  private var content: some View {
    if sources.isEmpty {
      Text("还没有可用的接口源。到「偏好设置 › 接口源」里导入 .jdr 扩展包后才能在线搜索。")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
    } else if loading {
      HStack(spacing: 10) {
        ProgressView().scaleEffect(0.9)
        Text("正在搜索「\(query)」…")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .lineLimit(1)
        Spacer(minLength: 0)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
    } else if let failureText {
      VStack(alignment: .leading, spacing: 6) {
        Text(offline ? "网络不可用，请检查连接" : "搜索失败")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
        Text(failureText)
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
          .fixedSize(horizontal: false, vertical: true)
        Button("重试") { reloadKey += 1 }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 12)
          .frame(height: 36)
          .overlay(
            Capsule().strokeBorder(AppColor.outlineVariant, lineWidth: 1)
          )
          .padding(.top, 2)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(.horizontal, 16)
      .padding(.vertical, 12)
    } else if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
      Text("输入关键词，在线上书源里搜书")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    } else if results.isEmpty {
      Text("这个源没有搜到结果")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
    } else {
      VStack(spacing: 0) {
        ForEach(results) { item in
          Button {
            guard let source = activeSource else { return }
            onSelect(OnlineBookSelection(item: item, source: source))
          } label: {
            resultRow(item)
          }
          .buttonStyle(.plain)
        }
        Spacer().frame(height: 24)
      }
    }
  }

  /// 结果行：52×70 封面（无圆角，与安卓一致）+ 标题 + 「作者 · 集数」
  private func resultRow(_ item: SourceSearchItem) -> some View {
    HStack(spacing: 0) {
      OnlineCover(url: item.cover)
        .frame(width: 52, height: 70)

      VStack(alignment: .leading, spacing: 2) {
        Text(item.title)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(2)
          .multilineTextAlignment(.leading)
        let subtitle = [
          item.author, item.trackCount > 0 ? "\(item.trackCount)" : "",
        ]
        .filter { !$0.isEmpty }
        .joined(separator: " · ")
        if !subtitle.isEmpty {
          Text(subtitle)
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
            .lineLimit(1)
        }
      }
      .padding(.leading, 12)

      Spacer(minLength: 0)
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 8)
    .contentShape(Rectangle())
  }

  private func runSearch() async {
    let keyword = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !sources.isEmpty else {
      results = []
      loading = false
      failureText = nil
      return
    }
    guard !keyword.isEmpty else {
      results = []
      loading = false
      failureText = nil
      return
    }
    guard let source = activeSource else {
      results = []
      loading = false
      failureText = "没有可用的接口源"
      return
    }
    guard let script = extensions.scriptText(for: source) else {
      results = []
      loading = false
      failureText = "读不到源脚本「\(source.meta.script)」，请在接口源里重新导入扩展包"
      return
    }

    loading = true
    failureText = nil
    offline = false
    DLog("ui", "在线搜索 源=\(source.displayName) 脚本 \(script.count) 字符 关键词「\(keyword)」")
    try? await Task.sleep(nanoseconds: searchDebounce)
    // 防抖：查询词或源变了，旧任务会被取消，取消的搜索不算失败
    if Task.isCancelled { return }

    do {
      let items = try await ExtensionSourceService.shared.search(
        script: script,
        allowInsecure: source.allowInsecure,
        keyword: keyword
      )
      if Task.isCancelled { return }
      DLog("ui", "在线搜索返回 \(items.count) 条")
      results = items
      loading = false
    } catch {
      if Task.isCancelled { return }
      DLog("ui", "在线搜索失败：\(error.localizedDescription)")
      offline = !NetworkStatus.isOnline
      failureText = error.localizedDescription
      results = []
      loading = false
    }
  }
}

// MARK: - 封面（无圆角）

private struct OnlineCover: View {
  var url: String

  var body: some View {
    AsyncImage(url: URL(string: url)) { phase in
      if let image = phase.image {
        image.resizable().scaledToFill()
      } else {
        Rectangle()
          .fill(AppColor.surfaceContainerHigh)
          .overlay(
            Image(systemName: "book.closed")
              .font(.system(size: 16))
              .foregroundColor(AppColor.onSurfaceVariant)
          )
      }
    }
    .clipped()
  }
}

// MARK: - 章节对话框（加书架 / 播这一集）

struct OnlineChaptersDialog: View {
  var selection: OnlineBookSelection
  var onClose: () -> Void

  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore

  @State private var chapters: [Chapter] = []
  @State private var loading = true
  @State private var failed = false
  @State private var detail: String?
  @State private var offline = false
  @State private var reloadKey = 0
  @State private var page = 0

  private let pageSize = 50

  private var shelfBook: Book? {
    library.books.first {
      $0.sourceId == selection.source.id && $0.remoteBookId == selection.item.id
    }
  }

  private var pageCount: Int {
    max(1, (chapters.count + pageSize - 1) / pageSize)
  }

  private var safePage: Int { min(max(page, 0), pageCount - 1) }

  var body: some View {
    ZStack {
      AppColor.scrim
        .ignoresSafeArea()
        .onTapGesture { onClose() }

      VStack(alignment: .leading, spacing: 0) {
        Text(selection.item.title)
          .font(AppFont.headlineSmall())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(3)
          .fixedSize(horizontal: false, vertical: true)
          .padding(.bottom, 16)

        bodyContent

        HStack(spacing: 8) {
          Spacer()
          Button {
            toggleShelf()
          } label: {
            Text(shelfBook == nil ? "加入书架" : "从书架移除")
              .font(AppFont.labelLarge())
              .foregroundColor(canToggleShelf ? AppColor.primary : AppColor.onSurfaceVariant)
              .padding(.horizontal, 12)
              .frame(height: 40)
          }
          .buttonStyle(.plain)
          .disabled(!canToggleShelf)

          Button {
            onClose()
          } label: {
            Text("取消")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(height: 40)
          }
          .buttonStyle(.plain)
        }
        .padding(.top, 12)
      }
      .padding(24)
      .frame(width: 328)
      .background(
        RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous)
          .fill(AppColor.surfaceContainerHigh)
      )
      .padding(.horizontal, 24)
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .task(id: reloadKey) { await load() }
  }

  private var canToggleShelf: Bool {
    if shelfBook != nil { return true }
    return !loading && !failed && !chapters.isEmpty
  }

  @ViewBuilder
  private var bodyContent: some View {
    if loading {
      VStack(spacing: 0) {
        ProgressView().scaleEffect(0.95)
        Text(
          selection.item.trackCount > 0
            ? "正在获取 \(selection.item.trackCount) 集章节…"
            : "正在获取章节…"
        )
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurface)
        .padding(.top, 12)
        Text("章节较多的书可能需要一会儿")
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.top, 4)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 16)
    } else if failed {
      VStack(spacing: 0) {
        Text(offline ? "网络不可用，请检查连接" : "获取章节失败")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
        if let detail, !detail.isEmpty {
          Text(detail)
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
            .lineLimit(4)
            .multilineTextAlignment(.center)
            .padding(.top, 6)
        }
        Button("重试") { reloadKey += 1 }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 12)
          .frame(height: 40)
          .padding(.top, 4)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 16)
    } else if chapters.isEmpty {
      Text("这个源没有返回章节")
        .font(AppFont.bodySmall())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 24)
    } else {
      VStack(alignment: .leading, spacing: 0) {
        Text("共 \(chapters.count) 集")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
          .padding(.bottom, 8)

        if shelfBook != nil {
          Text("已加入书架")
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
            .padding(.bottom, 8)
        }

        if pageCount > 1 {
          ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
              ForEach(0..<pageCount, id: \.self) { index in
                pageChip(index)
              }
            }
          }
          .padding(.bottom, 8)
        }

        ScrollView {
          LazyVStack(spacing: 4) {
            let start = safePage * pageSize
            let slice = Array(chapters.dropFirst(start).prefix(pageSize))
            ForEach(Array(slice.enumerated()), id: \.element.id) { offset, chapter in
              Button {
                playChapter(at: start + offset)
              } label: {
                HStack(spacing: 8) {
                  Text(chapter.title)
                    .font(AppFont.bodyMedium())
                    .foregroundColor(AppColor.onSurface)
                    .lineLimit(1)
                  Spacer(minLength: 8)
                  if chapter.duration > 0 {
                    Text(Self.durationLabel(chapter.duration))
                      .font(AppFont.bodySmall())
                      .foregroundColor(AppColor.onSurfaceVariant)
                  }
                }
                .frame(height: 32)
                .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
            }
          }
        }
        .frame(width: 280, height: 360)
      }
    }
  }

  private func pageChip(_ index: Int) -> some View {
    let selected = index == safePage
    let start = index * pageSize + 1
    let end = min((index + 1) * pageSize, chapters.count)
    return Button {
      page = index
    } label: {
      Text("\(start)-\(end)")
        .font(AppFont.labelMedium())
        .foregroundColor(selected ? AppColor.onSecondaryContainer : AppColor.onSurfaceVariant)
        .padding(.horizontal, 12)
        .frame(height: 28)
        .background(Capsule().fill(selected ? AppColor.secondaryContainer : Color.clear))
        .overlay(
          Capsule().strokeBorder(
            selected ? Color.clear : AppColor.outlineVariant,
            lineWidth: 1
          )
        )
    }
    .buttonStyle(.plain)
  }

  // MARK: 动作

  private func load() async {
    loading = true
    failed = false
    detail = nil
    offline = false

    guard let script = extensions.scriptText(for: selection.source) else {
      loading = false
      failed = true
      detail = "找不到该源的脚本文件"
      return
    }
    do {
      let items = try await ExtensionSourceService.shared.chapters(
        script: script,
        allowInsecure: selection.source.allowInsecure,
        bookId: selection.item.id,
        extraJSON: selection.item.extraJSON
      )
      chapters = items
      loading = false
      if page >= max(1, (items.count + pageSize - 1) / pageSize) { page = 0 }
    } catch {
      offline = !NetworkStatus.isOnline
      detail = error.localizedDescription
      failed = true
      chapters = []
      loading = false
    }
  }

  private func toggleShelf() {
    if let existing = shelfBook {
      library.delete(existing)
      app.showToast("已从书架移除")
      return
    }
    let book = makeBook()
    library.add(book)
    app.cacheCover(bookId: book.id, remote: selection.item.cover)
    app.showToast("已加入书架")
  }

  private func playChapter(at index: Int) {
    guard let book = shelfBook else {
      app.showToast("先加入书架再播放")
      return
    }
    onClose()
    app.openBook(book, chapterIndex: index)
  }

  private func makeBook() -> Book {
    Book(
      title: selection.item.title,
      author: selection.item.author,
      kind: .jdr,
      folder: nil,
      coverFile: nil,
      chapters: chapters,
      addedAt: Date(),
      position: PlaybackPosition(),
      bookmarks: [],
      skipIntro: settings.settings.defaultSkipIntro,
      skipOutro: settings.settings.defaultSkipOutro,
      speed: settings.settings.defaultSpeed,
      sourceId: selection.source.id,
      sourceName: selection.source.extensionName,
      remoteBookId: selection.item.id,
      sourceExtraJSON: selection.item.extraJSON
    )
  }

  /// 安卓 formatDuration：不足 1 小时只显示分钟，超过显示 h+m
  static func durationLabel(_ seconds: Double) -> String {
    let minutes = Int(seconds) / 60
    if minutes >= 60 {
      return "\(minutes / 60)h\(minutes % 60)m"
    }
    return "\(minutes)m"
  }
}
