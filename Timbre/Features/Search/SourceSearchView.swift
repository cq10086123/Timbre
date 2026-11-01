import SwiftUI

struct SourceSearchView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @Environment(\.dismiss) private var dismiss

  @State private var sourceId: String?
  @State private var keyword = ""
  @State private var results: [SourceSearchItem] = []
  @State private var isSearching = false
  @State private var message: String?
  @State private var selectedItem: SourceSearchItem?

  private var sources: [InstalledSource] { extensions.enabledSources }

  private var activeSource: InstalledSource? {
    if let sourceId, let match = sources.first(where: { $0.id == sourceId }) { return match }
    return sources.first
  }

  var body: some View {
    NavigationStack {
      ZStack {
        AppColor.background.ignoresSafeArea()
        VStack(spacing: 12) {
          searchBar
          content
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
      }
      .navigationTitle("在线书源搜索")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("关闭") { dismiss() }
        }
      }
      .sheet(item: $selectedItem) { item in
        if let source = activeSource, let script = extensions.scriptText(for: source) {
          SourceBookSheet(item: item, source: source, script: script)
            .environmentObject(app)
            .environmentObject(library)
            .environmentObject(settings)
        }
      }
    }
  }

  private var searchBar: some View {
    VStack(spacing: 10) {
      if sources.isEmpty {
        Text("没有启用的接口源，请先在「接口源」里安装并启用")
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
          .frame(maxWidth: .infinity, alignment: .leading)
      } else {
        HStack(spacing: 10) {
          Menu {
            ForEach(sources) { source in
              Button {
                sourceId = source.id
              } label: {
                if source.id == activeSource?.id {
                  Label(source.displayName, systemImage: "checkmark")
                } else {
                  Text(source.displayName)
                }
              }
            }
          } label: {
            HStack(spacing: 5) {
              Text(activeSource?.displayName ?? "选择源")
                .font(.system(size: 13, weight: .medium))
                .lineLimit(1)
              Image(systemName: "chevron.down")
                .font(.system(size: 9, weight: .semibold))
            }
            .foregroundColor(AppColor.accent)
            .padding(.horizontal, 12)
            .padding(.vertical, 8)
            .background(AppColor.accentSoft, in: Capsule())
          }

          HStack(spacing: 6) {
            TextField("书名 / 关键词", text: $keyword)
              .textInputAutocapitalization(.never)
              .autocorrectionDisabled()
              .submitLabel(.search)
              .onSubmit { runSearch() }
              .foregroundColor(AppColor.textPrimary)
            Button {
              runSearch()
            } label: {
              Image(systemName: "magnifyingglass")
                .foregroundColor(AppColor.accent)
            }
          }
          .padding(.horizontal, 12)
          .frame(height: 38)
          .background(AppColor.surface, in: Capsule())
        }
      }
    }
  }

  @ViewBuilder
  private var content: some View {
    if isSearching {
      VStack(spacing: 12) {
        ProgressView()
        Text("正在搜索…")
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else if let message {
      VStack(spacing: 10) {
        Text(message)
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
          .multilineTextAlignment(.center)
        Button("重试") { runSearch() }
          .font(.subheadline.weight(.medium))
          .foregroundColor(AppColor.accent)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .padding(.horizontal, 20)
    } else if results.isEmpty {
      VStack(spacing: 10) {
        Image(systemName: "sparkle.magnifyingglass")
          .font(.system(size: 34, weight: .light))
          .foregroundColor(AppColor.textSecondary)
        Text("输入关键词开始搜索")
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else {
      ScrollView {
        LazyVStack(spacing: 10) {
          ForEach(results) { item in
            Button {
              selectedItem = item
            } label: {
              SearchResultRow(item: item)
            }
            .buttonStyle(.plain)
          }
        }
        .padding(.bottom, 20)
      }
    }
  }

  private func runSearch() {
    let text = keyword.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return }
    guard let source = activeSource else { return }
    guard let script = extensions.scriptText(for: source) else {
      message = "找不到该源的脚本文件，请重新安装接口源"
      return
    }
    isSearching = true
    message = nil
    Task {
      do {
        let items = try await ExtensionSourceService.shared.search(
          script: script,
          allowInsecure: source.allowInsecure,
          keyword: text
        )
        await MainActor.run {
          results = items
          isSearching = false
        }
      } catch {
        await MainActor.run {
          results = []
          message = error.localizedDescription
          isSearching = false
        }
      }
    }
  }
}

private struct SearchResultRow: View {
  let item: SourceSearchItem

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      AsyncImage(url: URL(string: item.cover)) { phase in
        if let image = phase.image {
          image.resizable().scaledToFill()
        } else {
          RoundedRectangle(cornerRadius: 10, style: .continuous)
            .fill(AppColor.surfaceHigh)
            .overlay(
              Image(systemName: "book.closed")
                .foregroundColor(AppColor.textSecondary)
            )
        }
      }
      .frame(width: 62, height: 62)
      .clipShape(RoundedRectangle(cornerRadius: 10, style: .continuous))

      VStack(alignment: .leading, spacing: 4) {
        Text(item.title)
          .font(.system(size: 14, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
          .lineLimit(2)
        if !item.author.isEmpty {
          Text(item.author)
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
        }
        if item.trackCount > 0 {
          Text("共 \(item.trackCount) 集")
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
        }
        if !item.intro.isEmpty {
          Text(item.intro)
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
            .lineLimit(2)
        }
      }
      Spacer(minLength: 0)
    }
    .padding(10)
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
  }
}

private struct SourceBookSheet: View {
  let item: SourceSearchItem
  let source: InstalledSource
  let script: String

  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @Environment(\.dismiss) private var dismiss

  @State private var chapters: [Chapter] = []
  @State private var isLoading = true
  @State private var error: String?

  var body: some View {
    NavigationStack {
      ZStack {
        AppColor.background.ignoresSafeArea()
        VStack(spacing: 0) {
          header
          Divider().background(AppColor.divider)
          content
        }
      }
      .navigationTitle("确认加入书架")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("取消") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("加入") { addToShelf(playFirst: false) }
            .disabled(chapters.isEmpty)
        }
      }
      .task { await loadChapters() }
    }
  }

  private var header: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(item.title)
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(AppColor.textPrimary)
        .lineLimit(2)
      HStack(spacing: 8) {
        Text(source.displayName)
          .font(.system(size: 11, weight: .medium))
          .foregroundColor(AppColor.accent)
          .padding(.horizontal, 7)
          .padding(.vertical, 3)
          .background(AppColor.accentSoft, in: Capsule())
        if !item.author.isEmpty {
          Text(item.author)
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
        }
        Text(chapters.isEmpty ? "共 \(item.trackCount) 集" : "共 \(chapters.count) 集")
          .font(.system(size: 11))
          .foregroundColor(AppColor.textSecondary)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal, 16)
    .padding(.vertical, 12)
  }

  @ViewBuilder
  private var content: some View {
    if isLoading {
      VStack(spacing: 10) {
        ProgressView()
        Text("正在获取章节列表…")
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
        Text("长书章节较多，可能需要一点时间")
          .font(.caption2)
          .foregroundColor(AppColor.textSecondary)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else if let error {
      VStack(spacing: 10) {
        Text(error)
          .font(.footnote)
          .foregroundColor(AppColor.textSecondary)
          .multilineTextAlignment(.center)
        Button("重试") {
          Task { await loadChapters() }
        }
        .font(.subheadline.weight(.medium))
        .foregroundColor(AppColor.accent)
        Button("仍然加入书架（章节稍后刷新）") {
          addPlaceholder()
        }
        .font(.caption)
        .foregroundColor(AppColor.textSecondary)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .padding(.horizontal, 24)
    } else {
      ScrollView {
        LazyVStack(spacing: 0) {
          ForEach(Array(chapters.prefix(300).enumerated()), id: \.element.id) { index, chapter in
            HStack(spacing: 10) {
              Text("\(index + 1)")
                .font(.system(size: 11, design: .rounded))
                .foregroundColor(AppColor.textSecondary)
                .frame(width: 26, alignment: .trailing)
              Text(chapter.title)
                .font(.system(size: 13))
                .foregroundColor(AppColor.textPrimary)
                .lineLimit(1)
              Spacer(minLength: 4)
              if chapter.duration > 0 {
                Text(Format.time(chapter.duration))
                  .font(.system(size: 10))
                  .foregroundColor(AppColor.textSecondary)
              }
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 9)
          }
          if chapters.count > 300 {
            Text("只显示前 300 集，加入书架后可完整播放")
              .font(.caption2)
              .foregroundColor(AppColor.textSecondary)
              .padding(.vertical, 14)
          }
        }
      }
    }
  }

  private func loadChapters() async {
    isLoading = true
    error = nil
    do {
      let items = try await ExtensionSourceService.shared.chapters(
        script: script,
        allowInsecure: source.allowInsecure,
        bookId: item.id,
        extraJSON: item.extraJSON
      )
      await MainActor.run {
        chapters = items
        isLoading = false
      }
    } catch {
      await MainActor.run {
        self.error = error.localizedDescription
        isLoading = false
      }
    }
  }

  private func addPlaceholder() {
    let book = makeBook(chapters: [])
    library.add(book)
    app.cacheCover(bookId: book.id, remote: item.cover)
    app.showToast("已加入书架（暂无章节）")
    dismiss()
  }

  private func addToShelf(playFirst: Bool) {
    let book = makeBook(chapters: chapters)
    library.add(book)
    app.cacheCover(bookId: book.id, remote: item.cover)
    if playFirst, let saved = library.book(withId: book.id) {
      app.openBook(saved, chapterIndex: 0)
    } else {
      app.showToast("已加入书架，点按章节开始播放")
    }
    dismiss()
  }

  private func makeBook(chapters: [Chapter]) -> Book {
    Book(
      title: item.title,
      author: item.author,
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
      sourceId: source.id,
      sourceName: source.extensionName,
      remoteBookId: item.id,
      sourceExtraJSON: item.extraJSON
    )
  }
}
