import SwiftUI
import UIKit

// MARK: - 书籍分类（对齐安卓 BookOverviewCategory）

enum BookOverviewCategory: Int, CaseIterable, Identifiable {
  case current = 0
  case notStarted = 1
  case completed = 2

  var id: Int { rawValue }

  var title: String {
    switch self {
    case .current: return "正在播放中"
    case .notStarted: return "尚未开始播放"
    case .completed: return "已结束播放"
    }
  }
}

extension Book {
  var overviewCategory: BookOverviewCategory {
    let total = totalDuration
    let played = position.chapterIndex * 0 + position.seconds
    if position.chapterIndex == 0 && played < 1 { return .notStarted }
    if total > 0 {
      let overall = progress
      if overall >= 1 - (5 / max(total, 1)) { return .completed }
    }
    return .current
  }

  /// 剩余时长（秒）
  var remainingSeconds: Double {
    let total = totalDuration
    guard total > 0 else { return 0 }
    return max(0, total * (1 - progress))
  }
}

// MARK: - 书架主界面

struct ShelfView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var player: PlaybackEngine

  @State private var searchActive = false
  @State private var query = ""
  @State private var sheetBook: Book?
  @State private var renameTarget: Book?
  @State private var renameText = ""
  @State private var deleteTarget: Book?
  @State private var history: [String] = SearchHistory.load()

  private var isGrid: Bool { settings.settings.useGridLayout }

  private var filteredBooks: [Book] {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return library.books }
    return library.books.filter {
      $0.title.localizedCaseInsensitiveContains(trimmed)
        || $0.author.localizedCaseInsensitiveContains(trimmed)
    }
  }

  private var grouped: [(category: BookOverviewCategory, books: [Book])] {
    BookOverviewCategory.allCases.compactMap { category in
      let items = filteredBooks.filter { $0.overviewCategory == category }
      return items.isEmpty ? nil : (category, items)
    }
  }

  /// 悬浮播放键：存在「正在播放中」的书才出现
  private var playTarget: Book? {
    if let id = player.bookId, let book = library.book(withId: id) { return book }
    return library.books.first { $0.overviewCategory == .current }
  }

  var body: some View {
    ZStack(alignment: .bottomTrailing) {
      AppColor.background.ignoresSafeArea()

      VStack(spacing: 0) {
        searchBar
        if searchActive {
          searchContent
        } else {
          shelfContent
        }
      }

      if let target = playTarget, !searchActive {
        M3PlayButton(
          playing: player.isPlaying && player.bookId == target.id,
          size: 56,
          iconSize: 24
        ) {
          if player.bookId == target.id {
            player.togglePlayPause()
          } else {
            app.openBook(target)
          }
        }
        .padding(.trailing, 16)
        .padding(.bottom, 16)
      }
    }
    .fullScreenCover(isPresented: $app.showPlayer) {
      PlayerView()
        .environmentObject(app)
        .environmentObject(library)
        .environmentObject(settings)
        .environmentObject(player)
    }
    .fullScreenCover(isPresented: $app.showSettings) {
      SettingsView()
        .environmentObject(app)
        .environmentObject(library)
        .environmentObject(settings)
        .environmentObject(player)
    }
    .sheet(isPresented: $app.showImporter) {
      DocumentPicker { urls in
        app.showImporter = false
        app.handleImport(urls: urls)
      }
      .ignoresSafeArea()
    }
    .sheet(item: $sheetBook) { book in
      BookActionsSheet(
        book: book,
        onRename: {
          sheetBook = nil
          renameText = book.title
          renameTarget = book
        },
        onDelete: {
          sheetBook = nil
          deleteTarget = book
        },
        onCategory: { category in
          sheetBook = nil
          apply(category: category, to: book)
        }
      )
      .presentationDragIndicator(.visible)
      .presentationCornerRadius(AppRadius.extraLarge)
      .presentationBackground(AppColor.surfaceContainerLow)
    }
    .alert("编辑书名", isPresented: Binding(
      get: { renameTarget != nil },
      set: { if !$0 { renameTarget = nil } }
    )) {
      TextField("名称", text: $renameText)
      Button("设置") {
        if let book = renameTarget {
          app.rename(book, title: renameText, author: book.author)
        }
        renameTarget = nil
      }
      Button("取消", role: .cancel) { renameTarget = nil }
    }
    .confirmationDialog(
      "删除有声书",
      isPresented: Binding(
        get: { deleteTarget != nil },
        set: { if !$0 { deleteTarget = nil } }
      ),
      titleVisibility: .visible
    ) {
      Button("删除", role: .destructive) {
        if let book = deleteTarget { app.delete(book) }
        deleteTarget = nil
      }
      Button("取消", role: .cancel) { deleteTarget = nil }
    } message: {
      Text("将从书架移除这本有声书，音频文件会保留在设备上。")
    }
    .overlay {
      if app.isImporting {
        HStack(spacing: 10) {
          ProgressView().tint(AppColor.primary)
          Text("导入中… 读取文件")
            .font(AppFont.labelMedium())
            .foregroundColor(AppColor.onSurface)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)
        .background(AppColor.surfaceContainerLow, in: Capsule())
      }
    }
    .overlay(alignment: .bottom) {
      if let toast = app.toast {
        Text(toast)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
          .padding(.horizontal, 16)
          .padding(.vertical, 14)
          .background(AppColor.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 4))
          .padding(.bottom, 88)
          .transition(.opacity)
      }
    }
    .alert("出错了", isPresented: Binding(
      get: { app.errorMessage != nil },
      set: { if !$0 { app.errorMessage = nil } }
    )) {
      Button("知道了", role: .cancel) { app.errorMessage = nil }
    } message: {
      Text(app.errorMessage ?? "")
    }
  }

  private func apply(category: BookOverviewCategory, to book: Book) {
    switch category {
    case .notStarted:
      library.resetPosition(bookId: book.id)
    case .current:
      library.setPosition(bookId: book.id, chapterIndex: 0, seconds: 0.001)
    case .completed:
      let last = max(0, book.chapters.count - 1)
      library.setPosition(bookId: book.id, chapterIndex: last, seconds: 0.001)
      library.markFinished(bookId: book.id)
    }
  }

  // MARK: 顶部搜索栏（56dp 全圆角，surfaceContainerHigh）

  private var searchBar: some View {
    HStack(spacing: 0) {
      if searchActive {
        IconButton48(systemName: "chevron.left", label: "关闭") {
          searchActive = false
          query = ""
        }
      } else {
        Image(systemName: "magnifyingglass")
          .font(.system(size: 24))
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
          .onTapGesture { searchActive = true }
      }

      TextField("", text: $query)
        .font(AppFont.bodyLarge())
        .foregroundColor(AppColor.onSurface)
        .textFieldStyle(.plain)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .submitLabel(.search)
        .onSubmit { commitSearch() }
        .focused($searchFocused)
        .frame(maxWidth: .infinity)

      if !searchActive {
        IconButton48(systemName: "books.vertical", label: "有声书文件夹") {
          app.showImporter = true
        }
        IconButton48(systemName: "gearshape", label: "偏好设置") {
          app.showSettings = true
        }
        .padding(.trailing, 4)
      }
    }
    .frame(height: 56)
    .background(AppColor.surfaceContainerHigh, in: Capsule())
    .padding(.horizontal, searchActive ? 0 : 16)
    .padding(.top, 8)
    .animation(.easeInOut(duration: 0.2), value: searchActive)
  }

  @FocusState private var searchFocused: Bool

  // MARK: 书架

  private var shelfContent: some View {
    Group {
      if grouped.isEmpty {
        emptyShelf
      } else {
        ScrollView {
          if isGrid {
            LazyVStack(spacing: 0) {
              ForEach(grouped, id: \.category) { group in
                SectionHeader(title: group.category.title)
                  .padding(.top, 8)
                  .padding(.bottom, 4)
                LazyVGrid(columns: gridColumns, spacing: 8) {
                  ForEach(group.books) { book in
                    GridBookCard(book: book, width: gridItemWidth)
                      .onTapGesture { app.openBook(book) }
                      .onLongPressGesture(minimumDuration: 0.5, perform: { sheetBook = book })
                  }
                }
                .padding(.horizontal, 8)
              }
            }
            .padding(.top, 16)
            .padding(.bottom, 96)
          } else {
            LazyVStack(spacing: 8, pinnedViews: [.sectionHeaders]) {
              ForEach(grouped, id: \.category) { group in
                Section {
                  ForEach(group.books) { book in
                    ListBookCard(book: book)
                      .padding(.horizontal, 8)
                      .onTapGesture { app.openBook(book) }
                      .onLongPressGesture(minimumDuration: 0.5, perform: { sheetBook = book })
                  }
                } header: {
                  SectionHeader(title: group.category.title)
                    .padding(.horizontal, 8)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(AppColor.background)
                }
              }
            }
            .padding(.top, 16)
            .padding(.bottom, 96)
          }
        }
        .scrollIndicators(.hidden)
      }
    }
  }

  private var gridColumns: [GridItem] {
    Array(repeating: GridItem(.flexible(), spacing: 8), count: gridColumnCount)
  }

  private var gridColumnCount: Int {
    let width = UIScreen.main.bounds.width - 16
    return max(2, Int((width / 180).rounded()))
  }

  private var gridItemWidth: CGFloat {
    let width = UIScreen.main.bounds.width - 16
    return (width - 8 * CGFloat(gridColumnCount - 1)) / CGFloat(gridColumnCount)
  }

  private var emptyShelf: some View {
    VStack(spacing: 0) {
      Spacer()
      Image(systemName: "books.vertical")
        .font(.system(size: 96))
        .foregroundColor(AppColor.primary.opacity(0.6))
      Text("首先添加有声书文件夹，然后就可以开始听书啦！")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .multilineTextAlignment(.center)
        .padding(.top, 24)
      Button {
        app.showImporter = true
      } label: {
        HStack(spacing: 8) {
          Image(systemName: "plus").font(.system(size: 18))
          Text("添加").font(AppFont.labelLarge())
        }
        .foregroundColor(AppColor.onPrimary)
        .padding(.horizontal, 20)
        .frame(height: 40)
        .background(AppColor.primary, in: Capsule())
      }
      .buttonStyle(.plain)
      .padding(.top, 24)
      Spacer()
    }
    .padding(32)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
  }

  // MARK: 搜索

  private var searchContent: some View {
    Group {
      if query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
        ScrollView {
          VStack(spacing: 0) {
            if !history.isEmpty {
              HStack {
                Spacer()
                Button("清除搜索历史") {
                  history = []
                  SearchHistory.save([])
                }
                .font(AppFont.labelLarge())
                .foregroundColor(AppColor.primary)
                .padding(.horizontal, 16)
                .frame(height: 48)
              }
            }
            ForEach(history, id: \.self) { item in
              Button {
                query = item
                searchFocused = true
              } label: {
                HStack(spacing: 0) {
                  Image(systemName: "clock.arrow.circlepath")
                    .font(.system(size: 24))
                    .foregroundColor(AppColor.onSurfaceVariant)
                    .frame(width: 24, height: 24)
                    .padding(.trailing, 16)
                  Text(item)
                    .font(AppFont.bodyLarge())
                    .foregroundColor(AppColor.onSurface)
                  Spacer()
                }
                .padding(.horizontal, 16)
                .frame(height: 56)
                .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
            }
          }
          .padding(.top, 16)
        }
      } else if filteredBooks.isEmpty {
        Text("没有找到匹配的有声书")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
          .padding(.top, 32)
      } else {
        ScrollView {
          LazyVStack(spacing: 8) {
            ForEach(filteredBooks) { book in
              ListBookCard(book: book)
                .padding(.horizontal, 8)
                .onTapGesture {
                  commitSearch()
                  app.openBook(book)
                }
            }
          }
          .padding(.top, 16)
          .padding(.bottom, 24)
        }
      }
    }
  }

  private func commitSearch() {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return }
    var list = history.filter { $0 != trimmed }
    list.insert(trimmed, at: 0)
    history = Array(list.prefix(12))
    SearchHistory.save(history)
  }
}

// MARK: - 搜索历史存储

enum SearchHistory {
  private static let key = "timbre.searchHistory"

  static func load() -> [String] {
    UserDefaults.standard.stringArray(forKey: key) ?? []
  }

  static func save(_ items: [String]) {
    UserDefaults.standard.set(items, forKey: key)
  }
}

// MARK: - 列表卡片

struct ListBookCard: View {
  var book: Book

  private var showProgress: Bool { book.progress > 0.05 }

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        BookCoverView(book: book, size: 76)
          .frame(width: 76, height: 76)
          .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
          .padding(EdgeInsets(top: 8, leading: 8, bottom: 8, trailing: 0))

        VStack(alignment: .leading, spacing: 0) {
          Text(book.author.isEmpty ? "未知作者" : book.author.uppercased())
            .font(AppFont.labelSmall())
            .foregroundColor(AppColor.onSurfaceVariant)
            .lineLimit(1)
          Text(book.title)
            .font(AppFont.titleSmall())
            .foregroundColor(AppColor.onSurface)
            .lineLimit(2)
            .padding(.top, 1)
          HStack(spacing: 0) {
            Text(Format.humanDuration(book.remainingSeconds))
              .font(AppFont.labelMedium())
              .foregroundColor(AppColor.onSurfaceVariant)
            Spacer(minLength: 8)
            if book.progress > 0 {
              Text(Format.percent(book.progress))
                .font(AppFont.labelMedium())
                .foregroundColor(AppColor.onSurfaceVariant)
            }
          }
          .padding(.top, 4)
          .padding(.trailing, 12)
        }
        .padding(.leading, 12)
        .frame(maxWidth: .infinity, alignment: .leading)
      }
      .frame(height: 92)

      if showProgress {
        M3ProgressBar(fraction: book.progress)
          .padding(.leading, 12)
          .padding(.trailing, 12)
          .padding(.bottom, 10)
      }
    }
    .background(
      RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous)
        .fill(AppColor.surfaceContainerLow)
    )
    .overlay(alignment: .topTrailing) {
      if let badge = sourceBadge {
        Text(badge)
          .font(.system(size: 12, weight: .bold))
          .foregroundColor(AppColor.onSecondaryContainer)
          .padding(.horizontal, 10)
          .padding(.vertical, 5)
          .background(AppColor.secondaryContainer, in: Capsule())
          .padding(8)
      }
    }
    .contentShape(RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous))
  }

  private var sourceBadge: String? {
    guard book.kind == .jdr else { return nil }
    let name = book.sourceName ?? "源"
    return String(name.prefix(1)).uppercased()
  }
}

// MARK: - 网格卡片

struct GridBookCard: View {
  var book: Book
  var width: CGFloat

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      BookCoverView(book: book, size: width)
        .frame(width: width, height: width * 3 / 4)
        .clipShape(RoundedRectangle(cornerRadius: AppRadius.large, style: .continuous))

      Text(book.title)
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
        .lineLimit(3)
        .multilineTextAlignment(.leading)
        .padding(.top, 4)

      HStack(spacing: 0) {
        Text(Format.humanDuration(book.remainingSeconds))
          .font(AppFont.labelMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
        Spacer(minLength: 4)
        if book.progress > 0 {
          Text(Format.percent(book.progress))
            .font(AppFont.labelMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
      }
      .padding(.top, 2)

      if book.progress > 0.05 {
        M3ProgressBar(fraction: book.progress, track: AppColor.secondaryContainer, rounded: false)
          .padding(.top, 8)
      }
    }
    .padding(.horizontal, 12)
    .padding(.top, 12)
    .background(
      RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous)
        .fill(AppColor.surfaceContainerLow)
    )
    .overlay(alignment: .topTrailing) {
      if let badge = sourceBadge {
        Text(badge)
          .font(.system(size: 12, weight: .bold))
          .foregroundColor(AppColor.onSecondaryContainer)
          .padding(.horizontal, 10)
          .padding(.vertical, 5)
          .background(AppColor.secondaryContainer, in: Capsule())
          .padding(8)
      }
    }
    .contentShape(RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous))
  }

  private var sourceBadge: String? {
    guard book.kind == .jdr else { return nil }
    let name = book.sourceName ?? "源"
    return String(name.prefix(1)).uppercased()
  }
}

// MARK: - 长按底部抽屉

struct BookActionsSheet: View {
  var book: Book
  var onRename: () -> Void
  var onDelete: () -> Void
  var onCategory: (BookOverviewCategory) -> Void

  var body: some View {
    VStack(spacing: 0) {
      if book.kind == .local {
        M3ListItem(systemName: "textformat", title: "名称", action: onRename)
      }
      M3ListItem(systemName: "trash", title: "删除有声书", action: onDelete)
      if book.overviewCategory != .notStarted {
        M3ListItem(systemName: "hourglass", title: "标记为尚未开始播放", action: {
          onCategory(.notStarted)
        })
      }
      if book.overviewCategory != .current {
        M3ListItem(systemName: "play.circle", title: "标记为正在播放", action: {
          onCategory(.current)
        })
      }
      if book.overviewCategory != .completed {
        M3ListItem(systemName: "checkmark", title: "标记为已结束播放", action: {
          onCategory(.completed)
        })
      }
      Spacer().frame(height: 24)
    }
    .padding(.top, 12)
    .frame(maxWidth: .infinity)
  }
}
