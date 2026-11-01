import SwiftUI
import UniformTypeIdentifiers

struct ShelfView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @EnvironmentObject private var player: PlaybackEngine

  @State private var showJdrPicker = false
  @State private var showSourceSearch = false

  private var keywords: String {
    app.searchText.trimmingCharacters(in: .whitespacesAndNewlines)
  }

  var body: some View {
    NavigationStack {
      ZStack {
        AppColor.background.ignoresSafeArea()
        ScrollView {
          VStack(alignment: .leading, spacing: 22) {
            searchBar
            if keywords.isEmpty {
              nowPlayingSection
              allBooksSection
            } else {
              searchResults
            }
          }
          .padding(.horizontal, 16)
          .padding(.top, 8)
          .padding(.bottom, player.currentBook == nil ? 40 : 110)
        }
        .scrollDismissesKeyboard(.interactively)
      }
      .safeAreaInset(edge: .bottom) { miniPlayer }
      .navigationBarHidden(true)
      .navigationDestination(for: String.self) { bookId in
        BookDetailView(bookId: bookId)
          .environmentObject(app)
          .environmentObject(library)
          .environmentObject(settings)
          .environmentObject(player)
      }
      .sheet(isPresented: $showJdrPicker) {
        DocumentPicker(contentTypes: [.data], allowsMultiple: false) { urls in
          showJdrPicker = false
          app.installExtension(urls: urls)
        }
      }
      .sheet(isPresented: $showSourceSearch) {
        SourceSearchView()
          .environmentObject(app)
          .environmentObject(library)
          .environmentObject(settings)
          .environmentObject(extensions)
          .environmentObject(player)
      }
    }
  }

  // MARK: - 搜索栏

  private var searchBar: some View {
    HStack(spacing: 10) {
      HStack(spacing: 8) {
        Image(systemName: "magnifyingglass")
          .font(.system(size: 16, weight: .medium))
          .foregroundColor(AppColor.textSecondary)
        TextField("搜索书名、作者", text: $app.searchText)
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
          .foregroundColor(AppColor.textPrimary)
          .submitLabel(.search)
        if !app.searchText.isEmpty {
          Button {
            app.searchText = ""
          } label: {
            Image(systemName: "xmark.circle.fill")
              .foregroundColor(AppColor.textSecondary)
          }
        }
      }
      .padding(.horizontal, 14)
      .frame(height: 46)
      .background(AppColor.surface, in: Capsule())

      Button {
        app.showSettings = true
      } label: {
        Image(systemName: "gearshape")
          .font(.system(size: 18, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
          .frame(width: 46, height: 46)
          .background(AppColor.surface, in: Circle())
      }
    }
  }

  // MARK: - 正在播放中

  @ViewBuilder
  private var nowPlayingSection: some View {
    let items = library.playingBooks
    if !items.isEmpty {
      VStack(alignment: .leading, spacing: 12) {
        Text("正在播放中")
          .font(.system(size: 22, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
        ScrollView(.horizontal, showsIndicators: false) {
          HStack(spacing: 12) {
            ForEach(items) { book in
              Button {
                app.openBook(book)
              } label: {
                NowPlayingCard(book: book)
              }
              .buttonStyle(.plain)
            }
          }
          .padding(.vertical, 2)
        }
      }
    }
  }

  // MARK: - 全部书籍

  private var allBooksSection: some View {
    VStack(alignment: .leading, spacing: 14) {
      HStack(alignment: .center) {
        Text("全部书籍")
          .font(.system(size: 22, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
        Text("\(library.books.count)")
          .font(.system(size: 13, weight: .medium))
          .foregroundColor(AppColor.textSecondary)
        Spacer()
        sortMenu
        layoutToggle
        addMenu
      }

      if library.books.isEmpty {
        emptyState
      } else if settings.settings.useGridLayout {
        LazyVGrid(
          columns: [GridItem(.flexible(), spacing: 14), GridItem(.flexible(), spacing: 14)],
          spacing: 18
        ) {
          ForEach(library.sorted(settings.settings.sortOrder)) { book in
            NavigationLink(value: book.id) {
              GridBookCard(book: book)
            }
            .buttonStyle(.plain)
            .contextMenu {
              bookContextMenu(book)
            }
          }
        }
      } else {
        LazyVStack(spacing: 10) {
          ForEach(library.sorted(settings.settings.sortOrder)) { book in
            NavigationLink(value: book.id) {
              ListBookRow(book: book)
            }
            .buttonStyle(.plain)
            .contextMenu {
              bookContextMenu(book)
            }
          }
        }
      }
    }
  }

  private var sortMenu: some View {
    Menu {
      Picker("排序", selection: $settings.settings.sortOrder) {
        ForEach(BookSortOrder.allCases) { order in
          Text(order.title).tag(order)
        }
      }
    } label: {
      Image(systemName: "arrow.up.arrow.down")
        .font(.system(size: 15, weight: .medium))
        .foregroundColor(AppColor.textSecondary)
        .frame(width: 34, height: 34)
    }
  }

  private var layoutToggle: some View {
    Button {
      settings.settings.useGridLayout.toggle()
    } label: {
      Image(systemName: settings.settings.useGridLayout ? "list.bullet" : "square.grid.2x2")
        .font(.system(size: 15, weight: .medium))
        .foregroundColor(AppColor.textSecondary)
        .frame(width: 34, height: 34)
    }
  }

  private var addMenu: some View {
    Menu {
      Button {
        app.showImporter = true
      } label: {
        Label("导入本地音频 / 文件夹", systemImage: "folder.badge.plus")
      }
      Button {
        showJdrPicker = true
      } label: {
        Label("导入 .jdr 接口源", systemImage: "shippingbox")
      }
      Button {
        showSourceSearch = true
      } label: {
        Label("在线书源搜索", systemImage: "globe.asia.australia")
      }
      Button {
        app.showSettings = true
      } label: {
        Label("设置", systemImage: "gearshape")
      }
    } label: {
      Image(systemName: "plus")
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(AppColor.accent)
        .frame(width: 34, height: 34)
        .background(AppColor.accentSoft, in: Circle())
    }
  }

  @ViewBuilder
  private func bookContextMenu(_ book: Book) -> some View {
    Button {
      app.openBook(book)
    } label: {
      Label("播放", systemImage: "play.fill")
    }
    Button(role: .destructive) {
      app.delete(book)
    } label: {
      Label("删除", systemImage: "trash")
    }
  }

  private var emptyState: some View {
    VStack(spacing: 14) {
      Image(systemName: "books.vertical")
        .font(.system(size: 46, weight: .light))
        .foregroundColor(AppColor.textSecondary)
      Text("书架还是空的")
        .font(.headline)
        .foregroundColor(AppColor.textPrimary)
      Text("把整本有声书文件夹或音频文件导入进来，\n也可以用接口源在线搜索")
        .font(.footnote)
        .multilineTextAlignment(.center)
        .foregroundColor(AppColor.textSecondary)
      Button {
        app.showImporter = true
      } label: {
        Text("导入音频")
          .font(.subheadline.weight(.semibold))
          .foregroundColor(AppColor.background)
          .padding(.horizontal, 22)
          .padding(.vertical, 11)
          .background(AppColor.accent, in: Capsule())
      }
      .padding(.top, 4)
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 40)
  }

  // MARK: - 搜索结果

  private var searchResults: some View {
    let results = library.search(keywords).sorted {
      NaturalSort.compare($0.title, $1.title) == .orderedAscending
    }
    return VStack(alignment: .leading, spacing: 12) {
      Text("找到 \(results.count) 本")
        .font(.footnote)
        .foregroundColor(AppColor.textSecondary)
      LazyVStack(spacing: 10) {
        ForEach(results) { book in
          NavigationLink(value: book.id) {
            ListBookRow(book: book)
          }
          .buttonStyle(.plain)
        }
      }
      if results.isEmpty {
        Text("没有匹配的书")
          .font(.subheadline)
          .foregroundColor(AppColor.textSecondary)
          .frame(maxWidth: .infinity)
          .padding(.top, 40)
      }
    }
  }

  // MARK: - 迷你播放条

  @ViewBuilder
  private var miniPlayer: some View {
    if let book = player.currentBook, let chapter = player.currentChapter {
      HStack(spacing: 8) {
        Button {
          app.showPlayer = true
        } label: {
          HStack(spacing: 12) {
            BookCoverView(book: book, size: 42, cornerRadius: 8)
            VStack(alignment: .leading, spacing: 2) {
              Text(chapter.title)
                .font(.system(size: 14, weight: .medium))
                .foregroundColor(AppColor.textPrimary)
                .lineLimit(1)
              Text("\(book.title) · \(Format.percent(player.progressFraction))")
                .font(.system(size: 11))
                .foregroundColor(AppColor.textSecondary)
                .lineLimit(1)
            }
            Spacer(minLength: 4)
            ProgressRing(progress: player.progressFraction)
              .frame(width: 22, height: 22)
          }
        }
        .buttonStyle(.plain)

        Button {
          player.togglePlayPause()
        } label: {
          Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
            .font(.system(size: 17, weight: .semibold))
            .foregroundColor(AppColor.accent)
            .frame(width: 42, height: 42)
        }
        .buttonStyle(.plain)
      }
      .padding(.horizontal, 10)
      .padding(.vertical, 6)
      .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18, style: .continuous))
      .overlay(
        RoundedRectangle(cornerRadius: 18, style: .continuous)
          .stroke(AppColor.divider.opacity(0.6), lineWidth: 0.5)
      )
      .padding(.horizontal, 12)
      .padding(.bottom, 6)
    }
  }
}

// MARK: - 卡片

private struct ProgressRing: View {
  let progress: Double

  var body: some View {
    ZStack {
      Circle()
        .stroke(AppColor.divider, lineWidth: 2.5)
      Circle()
        .trim(from: 0, to: max(0.02, min(1, progress)))
        .stroke(AppColor.accent, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
        .rotationEffect(.degrees(-90))
    }
  }
}

private struct NowPlayingCard: View {
  let book: Book

  var body: some View {
    HStack(alignment: .top, spacing: 10) {
      BookCoverView(book: book, size: 62, cornerRadius: 10)
        .overlay(alignment: .topTrailing) {
          SourceBadge(book: book)
            .offset(x: 4, y: -4)
        }
      VStack(alignment: .leading, spacing: 3) {
        Text(book.title)
          .font(.system(size: 13, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
          .lineLimit(1)
        Text(currentChapterTitle)
          .font(.system(size: 11))
          .foregroundColor(AppColor.textSecondary)
          .lineLimit(1)
        Spacer(minLength: 2)
        HStack {
          Text(Format.time(book.totalDuration))
            .font(.system(size: 10))
            .foregroundColor(AppColor.textSecondary)
          Spacer()
          Text(Format.percent(book.progress))
            .font(.system(size: 10))
            .foregroundColor(AppColor.textSecondary)
        }
      }
    }
    .padding(10)
    .frame(width: 268, height: 84, alignment: .leading)
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
  }

  private var currentChapterTitle: String {
    let index = min(max(0, book.position.chapterIndex), max(0, book.chapters.count - 1))
    guard book.chapters.indices.contains(index) else { return book.chapterCountText }
    return book.chapters[index].title
  }
}

private struct GridBookCard: View {
  let book: Book

  var body: some View {
    VStack(alignment: .leading, spacing: 7) {
      GeometryReader { geometry in
        BookCoverView(book: book, size: geometry.size.width)
          .overlay(alignment: .topTrailing) {
            SourceBadge(book: book)
              .padding(5)
          }
      }
      .aspectRatio(1, contentMode: .fit)

      Text(book.title)
        .font(.system(size: 13, weight: .medium))
        .foregroundColor(AppColor.textPrimary)
        .lineLimit(2)
        .multilineTextAlignment(.leading)
        .frame(maxWidth: .infinity, alignment: .leading)

      HStack(spacing: 6) {
        Text(book.author.isEmpty ? book.chapterCountText : book.author)
          .font(.system(size: 11))
          .foregroundColor(AppColor.textSecondary)
          .lineLimit(1)
        Spacer(minLength: 0)
        if book.position.isStarted {
          Text(Format.percent(book.progress))
            .font(.system(size: 11))
            .foregroundColor(AppColor.accent)
        }
      }
    }
  }
}

private struct ListBookRow: View {
  let book: Book

  var body: some View {
    HStack(spacing: 12) {
      BookCoverView(book: book, size: 56, cornerRadius: 10)
      VStack(alignment: .leading, spacing: 4) {
        Text(book.title)
          .font(.system(size: 15, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
          .lineLimit(2)
        HStack(spacing: 6) {
          SourceBadge(book: book)
          Text(book.chapterCountText)
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
          if !book.author.isEmpty {
            Text("·")
              .font(.system(size: 11))
              .foregroundColor(AppColor.textSecondary)
            Text(book.author)
              .font(.system(size: 11))
              .foregroundColor(AppColor.textSecondary)
              .lineLimit(1)
          }
        }
      }
      Spacer(minLength: 4)
      VStack(alignment: .trailing, spacing: 4) {
        Text(Format.percent(book.progress))
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(book.position.isStarted ? AppColor.accent : AppColor.textSecondary)
        Text(Format.time(book.totalDuration))
          .font(.system(size: 10))
          .foregroundColor(AppColor.textSecondary)
      }
    }
    .padding(10)
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
  }
}
