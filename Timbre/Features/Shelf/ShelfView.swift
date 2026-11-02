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

  /// 分类内的排序规则 —— 与安卓 `BookOverviewCategory.comparator` 一致：
  /// 进行中 / 已完成按最后播放时间倒序，未开始按书名自然序。
  func sort(_ books: [Book]) -> [Book] {
    switch self {
    case .current, .completed:
      return books.sorted { left, right in
        let l = left.lastPlayedAt ?? left.position.updatedAt ?? left.addedAt
        let r = right.lastPlayedAt ?? right.position.updatedAt ?? right.addedAt
        if l == r { return NaturalSort.compare(left.title, right.title) == .orderedAscending }
        return l > r
      }
    case .notStarted:
      return books.sorted {
        NaturalSort.compare($0.title, $1.title) == .orderedAscending
      }
    }
  }
}

extension Book {
  var overviewCategory: BookOverviewCategory {
    let total = totalDuration
    let played = position.seconds
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
  @EnvironmentObject private var extensions: ExtensionStore

  @State private var searchActive = false
  @State private var query = ""
  @State private var sheetBook: Book?
  /// 长按弹抽屉的时间戳：松手那一刻 tap 手势还会触发一次，靠它把这次 tap 挡掉
  @State private var lastLongPressAt = Date.distantPast
  @State private var renameTarget: Book?
  @State private var renameText = ""
  @State private var deleteTarget: Book?
  /// 换封面全屏流程
  @State private var coverTarget: Book?
  /// 在线书「缓存书籍」抽屉
  @State private var cacheTarget: Book?
  @State private var history: [String] = SearchHistory.load()
  @State private var onlineSelection: OnlineBookSelection?

  private var isGrid: Bool { settings.settings.useGridLayout }

  /// 卡片右上角的接口名。安卓是「整串接口名」而不是单个字母，
  /// 而且取的是这条源自己的名字（A / B / 企鹅FM），不是扩展包的名字。
  private func sourceLabel(for book: Book) -> String? {
    guard book.kind == .jdr else { return nil }
    return extensions.displayName(forSourceId: book.sourceId, fallback: book.sourceName)
  }

  /// WebDAV 导入后的章节时长解析进度，解析中的书在卡片上显示这行小字
  private func analysisNote(for book: Book) -> String? {
    guard let p = app.durationAnalyzer.progress[book.id] else { return nil }
    return "正在解析 \(p.done)/\(p.total) 章"
  }

  private var trimmedQuery: String {
    query.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  private var filteredBooks: [Book] {
    let trimmed = trimmedQuery
    guard !trimmed.isEmpty else { return library.books }
    return library.books.filter {
      $0.title.localizedCaseInsensitiveContains(trimmed)
        || $0.author.localizedCaseInsensitiveContains(trimmed)
    }
  }

  private var grouped: [(category: BookOverviewCategory, books: [Book])] {
    BookOverviewCategory.allCases.compactMap { category in
      let items = category.sort(filteredBooks.filter { $0.overviewCategory == category })
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

      // 在线书源的章节弹窗：安卓是 AlertDialog，这里按同一层级居中浮在整页之上
      if let selection = onlineSelection {
        OnlineChaptersDialog(selection: selection, onClose: { onlineSelection = nil })
          .transition(.opacity)
      }
    }
    .animation(.easeInOut(duration: 0.15), value: onlineSelection)
    .fullScreenCover(isPresented: $app.showPlayer) {
      PlayerView()
        .environmentObject(app)
        .environmentObject(library)
        .environmentObject(settings)
        .environmentObject(app.extensions)
        .environmentObject(player)
    }
    .fullScreenCover(isPresented: $app.showSettings) {
      // SettingsView 声明了 @EnvironmentObject extensions（接口源入口），
      // 少注入这一个运行时就会 fatalError，表现为「点设置直接闪退」。
      SettingsView()
        .environmentObject(app)
        .environmentObject(library)
        .environmentObject(settings)
        .environmentObject(app.extensions)
        .environmentObject(app.webdav)
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
          // 抽屉收起和 alert 弹出抢同一个窗口：不隔一拍 alert 会被吞掉（删除/改名都一样）
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            renameText = book.title
            renameTarget = book
          }
        },
        onEditCover: {
          sheetBook = nil
          // 抽屉还在收起路上就弹全屏会被系统拒绝，隔一拍再上
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            coverTarget = book
          }
        },
        onRefreshChapters: {
          sheetBook = nil
          Task { @MainActor in
            let message = await app.refreshOnlineChapters(book)
            app.showToast(message)
          }
        },
        onCacheBook: {
          sheetBook = nil
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            cacheTarget = book
          }
        },
        onDelete: {
          sheetBook = nil
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.35) {
            deleteTarget = book
          }
        },
        onCategory: { category in
          sheetBook = nil
          apply(category: category, to: book)
        }
      )
      .presentationDragIndicator(.visible)
      .presentationDetents([.height(BookActionsSheet.sheetHeight(for: book))])
    }
    .fullScreenCover(item: $coverTarget) { book in
      CoverFlowView(
        book: book,
        onCancel: { coverTarget = nil },
        onApply: { data in
          if app.applyCover(bookId: book.id, imageData: data) {
            app.showToast("封面已更新")
          } else {
            app.errorMessage = "封面保存失败，换张图片试试"
          }
          coverTarget = nil
        }
      )
    }
    .sheet(item: $cacheTarget) { book in
      OnlineCacheSheet(book: book, onClose: { cacheTarget = nil })
        .environmentObject(app.cache)
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
          .onTapGesture {
            searchActive = true
            searchFocused = true
          }
      }

      TextField("", text: $query)
        .font(AppFont.bodyLarge())
        .foregroundColor(AppColor.onSurface)
        .textFieldStyle(.plain)
        .autocorrectionDisabled()
        .textInputAutocapitalization(.never)
        .submitLabel(.search)
        .onSubmit { commitSearch() }
        .onChange(of: query) { text in
          // 直接点输入框打字时也要进搜索态，否则界面停在书架，看起来像「没反应」
          if !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            searchActive = true
          }
        }
        .focused($searchFocused)
        .frame(maxWidth: .infinity)

      if !searchActive {
        // 安卓的 folder_picker_in_settings 开关默认把「有声书文件夹」入口收进设置页，
        // 搜索栏只留设置齿轮 —— 这里对齐，导入走「设置 → 有声书文件夹」
        IconButton48(systemName: "gearshape", label: "偏好设置") {
          app.showSettings = true
        }
        .padding(.trailing, 4)
      }
    }
    .onChange(of: searchFocused) { focused in
      // 点一下输入框就切到搜索界面，不用先点放大镜
      if focused { searchActive = true }
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
                    GridBookCard(
                      book: book,
                      width: gridItemWidth,
                      sourceLabel: sourceLabel(for: book),
                      progressNote: analysisNote(for: book)
                    )
                      .contentShape(Rectangle())
                      .onTapGesture { handleTap(book) }
                      .onLongPressGesture(minimumDuration: 0.5) { handleLongPress(book) }
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
                    ListBookCard(
                      book: book,
                      sourceLabel: sourceLabel(for: book),
                      progressNote: analysisNote(for: book)
                    )
                      .padding(.horizontal, 8)
                      .contentShape(Rectangle())
                      .onTapGesture { handleTap(book) }
                      .onLongPressGesture(minimumDuration: 0.5) { handleLongPress(book) }
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

  /// 轻点开书、长按弹操作抽屉。
  ///
  /// 踩过的两种坑：
  /// 1. `LongPressGesture.exclusively(before: TapGesture())` —— 理论上互斥，
  ///    实测 tap 永远不被触发（点卡片没反应，进不了播放器），弃用。
  /// 2. 裸叠 `.onTapGesture` + `.onLongPressGesture` —— 长按 0.5s 抽屉已经弹出，
  ///    松手那一刻 tap 仍会触发一次，播放器把抽屉盖掉，看起来就是「长按没反应」。
  ///    现在用 `lastLongPressAt` 时间戳把长按后的那一下 tap 挡掉。
  private func handleTap(_ book: Book) {
    guard Date().timeIntervalSince(lastLongPressAt) > 0.6 else {
      DLog("shelf", "忽略长按松手带出的 tap \(book.title)")
      return
    }
    DLog("shelf", "点开书 \(book.title)")
    app.openBook(book)
  }

  private func handleLongPress(_ book: Book) {
    lastLongPressAt = Date()
    DLog("shelf", "长按 \(book.title) 弹出操作抽屉")
    sheetBook = book
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
      } else {
        ScrollView {
          VStack(spacing: 0) {
            // 本地结果用 Lazy，书多时不卡
            LazyVStack(spacing: 8) {
              if filteredBooks.isEmpty {
                Text("没有找到匹配的有声书")
                  .font(AppFont.bodyMedium())
                  .foregroundColor(AppColor.onSurfaceVariant)
                  .frame(maxWidth: .infinity, alignment: .center)
                  .padding(.top, 32)
                  .padding(.bottom, 8)
              } else {
                ForEach(filteredBooks) { book in
                  ListBookCard(
                    book: book,
                    sourceLabel: sourceLabel(for: book),
                    progressNote: analysisNote(for: book)
                  )
                    .padding(.horizontal, 8)
                    .onTapGesture {
                      commitSearch()
                      app.openBook(book)
                    }
                }
              }
            }

            // 「在线」段落必须放在 Lazy 容器外：放进 LazyVStack 时，本地结果一多它就被
            // 滚出渲染范围，.task 不触发，表现为「搜索没反应」。
            OnlineSearchSection(query: trimmedQuery) { selection in
              onlineSelection = selection
            }

            Spacer().frame(height: 24)
          }
          .padding(.top, 16)
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

/// 接口名角标（对齐安卓 `OnlineSourceBadge`）：
/// Capsule 底、secondaryContainer、labelMedium 加粗、左右 10 上下 5，外留 8。
/// 安卓显示的是整串接口名（A / B / 企鹅FM），所以这里不做首字母缩写。
struct SourceNameBadge: View {
  var text: String

  var body: some View {
    Text(text)
      .font(AppFont.labelMedium())
      .fontWeight(.bold)
      .foregroundColor(AppColor.onSecondaryContainer)
      .lineLimit(1)
      .padding(.horizontal, 10)
      .padding(.vertical, 5)
      .background(AppColor.secondaryContainer, in: Capsule())
      .padding(8)
  }
}

struct ListBookCard: View {
  var book: Book
  var sourceLabel: String?
  /// WebDAV 导入后章节时长解析中的提示（非 nil 时替代剩余时长显示）
  var progressNote: String? = nil

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
            Text(progressNote ?? Format.humanDuration(book.remainingSeconds))
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
      if let badge = sourceLabel {
        SourceNameBadge(text: badge)
      }
    }
    .contentShape(RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous))
  }
}

// MARK: - 网格卡片

struct GridBookCard: View {
  var book: Book
  var width: CGFloat
  var sourceLabel: String?
  /// WebDAV 导入后章节时长解析中的提示（非 nil 时替代剩余时长显示）
  var progressNote: String? = nil

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
        Text(progressNote ?? Format.humanDuration(book.remainingSeconds))
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
      if let badge = sourceLabel {
        SourceNameBadge(text: badge)
      }
    }
    .contentShape(RoundedRectangle(cornerRadius: AppRadius.extraLarge, style: .continuous))
  }
}

// MARK: - 长按底部抽屉

struct BookActionsSheet: View {
  var book: Book
  var onRename: () -> Void
  var onEditCover: () -> Void
  var onRefreshChapters: () -> Void
  var onCacheBook: () -> Void
  var onDelete: () -> Void
  var onCategory: (BookOverviewCategory) -> Void

  /// 在线书源的书（`jdr:`）改封面 / 改名都没意义：
  /// 封面是源给的 URL，改名后端下次刷新会覆盖回去（对齐安卓两处 `return emptyList()`）
  private var isOnline: Bool { book.kind == .jdr }

  var body: some View {
    VStack(spacing: 0) {
      if !isOnline {
        // 安卓 EditBookTitleViewModel：仓库里所有书都能改名，包括 WebDAV 导入的
        M3ListItem(systemName: "textformat", title: "名称", action: onRename)
        M3ListItem(systemName: "photo", title: "更换封面", action: onEditCover)
      } else {
        M3ListItem(
          systemName: "arrow.triangle.2.circlepath",
          title: "更新章节",
          action: onRefreshChapters
        )
        M3ListItem(
          systemName: "arrow.down.circle",
          title: "缓存书籍",
          action: onCacheBook
        )
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

  /// 抽屉高度（行数算出来），让 sheet 贴着内容而不是撑满半屏。
  /// 结构固定：2 个主操作 + 删除 + 2 个「标记为…」（当前分类那个不显示）。
  static func sheetHeight(for book: Book) -> CGFloat {
    12.0 + 5.0 * 56.0 + 24.0
  }
}
