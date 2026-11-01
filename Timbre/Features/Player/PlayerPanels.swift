import SwiftUI

// MARK: - 章节列表抽屉（对齐安卓 SelectChapterDialog）

/// 安卓的章节抽屉列的是 **分段（chapterMark）** 而不是章：
/// 一个有 markData 的章会展开成好几项，编号跨章连续累加，
/// 时间显示的是「前面所有章的总时长 + 本段在章内的起点」。
struct ChapterSheet: View {
  @ObservedObject var player: PlaybackEngine
  var onClose: () -> Void

  /// 分块跳转网格默认收起，点标题栏展开（安卓 rangesExpanded）
  @State private var rangesExpanded = false
  /// 点某个分块后，把该块第一项滚到顶部并短暂高亮 2 秒
  @State private var highlightedItemIndex: Int?
  /// 拍平后的播放单元。整本书可能上千条，缓存在 State 里，别每次重算
  @State private var units: [ChapterUnit] = []

  /// 一个可点项 = 一个分段
  struct ChapterUnit: Identifiable {
    var id: Int            // 全局序号（0 起）
    var chapterIndex: Int
    var markIndex: Int
    var name: String
    /// 全局起始秒（前面所有章时长 + 本段章内起点）
    var start: Double
  }

  private var chapters: [Chapter] { player.currentBook?.chapters ?? [] }

  /// 只在换书 / 章节数变化时重建
  private var unitsKey: String { "\(player.bookId ?? "")-\(chapters.count)" }

  private func buildUnits() -> [ChapterUnit] {
    var result: [ChapterUnit] = []
    var number = 0
    var startAcc: Double = 0
    for (chapterIndex, chapter) in chapters.enumerated() {
      for (markIndex, mark) in chapter.chapterMarks.enumerated() {
        result.append(
          ChapterUnit(
            id: number,
            chapterIndex: chapterIndex,
            markIndex: markIndex,
            name: mark.name ?? chapter.title,
            start: startAcc + mark.start
          )
        )
        number += 1
      }
      startAcc += chapter.duration
    }
    return result
  }

  private func isActive(_ unit: ChapterUnit) -> Bool {
    unit.chapterIndex == player.chapterIndex && unit.markIndex == player.markIndex
  }

  private var activeUnitIndex: Int {
    units.firstIndex { isActive($0) } ?? 0
  }

  private struct ChapterRange {
    var first: Int
    var last: Int
    var startIndex: Int
    var endIndex: Int

    /// 安卓 `containsCurrent = activeItemIndex in start until end`
    func contains(_ index: Int) -> Bool { index >= startIndex && index < endIndex }
  }

  /// 对齐安卓 ChapterRanges.kt：<30 不显示，>2000 每块 100，否则每块 50
  private var ranges: [ChapterRange] {
    let count = units.count
    guard count >= 30 else { return [] }
    let size = count > 2000 ? 100 : 50
    var result: [ChapterRange] = []
    var start = 0
    while start < count {
      let end = min(start + size, count)
      result.append(
        ChapterRange(first: start + 1, last: end, startIndex: start, endIndex: end)
      )
      start = end
    }
    return result
  }

  var body: some View {
    ScrollViewReader { proxy in
      VStack(spacing: 0) {
        // 安卓只有在 ranges 非空时才画这一行；它就是展开/收起网格的开关
        if !ranges.isEmpty {
          header
          if rangesExpanded {
            rangeGrid(proxy)
          }
        }

        ScrollView {
          LazyVStack(spacing: 0) {
            ForEach(units) { unit in
              row(unit)
            }
          }
          .padding(.bottom, 24)
        }
      }
      .onAppear { rebuild(proxy: proxy) }
      .onChange(of: unitsKey) { _ in
        rebuild(proxy: proxy)
      }
      .task(id: highlightedItemIndex) {
        // 对齐安卓 HIGHLIGHT_DURATION = 2s：跳转高亮两秒后自动褪掉
        guard let value = highlightedItemIndex else { return }
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        if highlightedItemIndex == value { highlightedItemIndex = nil }
      }
    }
    .presentationDetents([.large])
  }

  private func rebuild(proxy: ScrollViewProxy) {
    units = buildUnits()
    // 安卓 rememberLazyListState(initialFirstVisibleItemIndex = selectedIndex - 1)：
    // 把上一项顶到第一条，用户一眼能看到自己在哪
    let target = max(0, activeUnitIndex - 1)
    if target > 0 {
      DispatchQueue.main.async { proxy.scrollTo(target, anchor: .top) }
    }
  }


  // MARK: 标题栏

  private var header: some View {
    Button {
      withAnimation(.easeInOut(duration: 0.2)) { rangesExpanded.toggle() }
    } label: {
      HStack(spacing: 0) {
        Text("共\(units.count)集")
          .font(AppFont.titleMedium())
          .foregroundColor(AppColor.onSurface)
        AndroidIcon(glyph: .expandMore, size: 24, color: AppColor.onSurface)
          .padding(.leading, 4)
          .rotationEffect(.degrees(rangesExpanded ? 180 : 0))
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 12)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  // MARK: 分块跳转网格

  /// 3 列、间距 8、左右留 12、高度上限 300（安卓 RANGE_GRID_MAX_HEIGHT）
  private func rangeGrid(_ proxy: ScrollViewProxy) -> some View {
    ScrollView {
      LazyVGrid(
        columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3),
        spacing: 8
      ) {
        ForEach(ranges.indices, id: \.self) { index in
          let range = ranges[index]
          let containsCurrent = range.contains(activeUnitIndex)
          Button {
            // 安卓：点分块 → 收起网格 → 高亮该块第一项 → 滚到该块
            withAnimation(.easeInOut(duration: 0.2)) { rangesExpanded = false }
            highlightedItemIndex = range.startIndex
            proxy.scrollTo(range.startIndex, anchor: .top)
          } label: {
            Text("\(range.first)-\(range.last)")
              .font(AppFont.bodyMedium())
              .foregroundColor(
                containsCurrent ? AppColor.onPrimaryContainer : AppColor.onSurfaceVariant
              )
              .padding(.horizontal, 8)
              .padding(.vertical, 10)
              .frame(maxWidth: .infinity)
              .background(
                RoundedRectangle(cornerRadius: 20, style: .continuous)
                  .fill(containsCurrent ? AppColor.primaryContainer : AppColor.surfaceVariant)
              )
          }
          .buttonStyle(.plain)
        }
      }
      .padding(.horizontal, 12)
    }
    .frame(maxHeight: 300)
  }

  // MARK: 单个分段

  /// 安卓 ListItem：leading 编号 / 正文标题 / trailing 起始时间，
  /// 背景 active → primaryContainer，跳转高亮 → secondaryContainer，
  /// 外层 padding(3dp) + 圆角 12dp。
  private func row(_ unit: ChapterUnit) -> some View {
    let active = isActive(unit)
    let background: Color =
      active
      ? AppColor.primaryContainer
      : (unit.id == highlightedItemIndex ? AppColor.secondaryContainer : Color.clear)

    return Button {
      if let book = player.currentBook {
        // 安卓 onChapterClick 只 setPosition，不改 playWhenReady：
        // 暂停着切到某一集，切完还是暂停
        player.open(
          book,
          chapterIndex: unit.chapterIndex,
          markIndex: unit.markIndex,
          autoplay: player.wantsToPlay
        )
      }
      onClose()
    } label: {
      HStack(spacing: 0) {
        Text("\(unit.id + 1)")
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(minWidth: 24, alignment: .leading)
        Text(unit.name)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(1)
          .padding(.leading, 16)
        Spacer(minLength: 16)
        Text(Format.clockString(unit.start))
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
      .padding(.horizontal, 16)
      .frame(minHeight: 56)
      .background(background)
      .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
      .padding(3)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .id(unit.id)
  }
}

// MARK: - 睡眠定时器抽屉（对齐安卓 SleepTimerDialog）

struct SleepSheet: View {
  @ObservedObject var player: PlaybackEngine
  var onClose: () -> Void

  @AppStorage("timbre.sleepCustomMinutes") private var customMinutes = 20

  private let presets = [5, 15, 30, 60]

  var body: some View {
    VStack(spacing: 0) {
      Text("做个好梦！🌙")
        .font(AppFont.headlineSmall())
        .foregroundColor(AppColor.onSurface)
        .multilineTextAlignment(.center)
        .frame(maxWidth: .infinity)
        .padding(.horizontal, 16)
        .padding(.top, 8)
      Color.clear.frame(height: 16)

      ForEach(presets, id: \.self) { minutes in
        M3ListItem(title: "\(minutes) 分钟", action: {
          player.startSleepTimer(minutes: minutes)
          onClose()
        })
      }

      // 自定义分钟：点标题区即用当前分钟数开始计时；加减号长按 0.5s 后每 0.1s 连续增减
      HStack(spacing: 0) {
        Button {
          player.startSleepTimer(minutes: customMinutes)
          onClose()
        } label: {
          HStack(spacing: 0) {
            Text("\(customMinutes) 分钟")
              .font(AppFont.bodyLarge())
              .foregroundColor(AppColor.onSurface)
            Spacer(minLength: 0)
          }
          .frame(maxWidth: .infinity)
          .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        ContinuousPressIcon(glyph: .remove, label: "更短") {
          customMinutes = max(1, customMinutes - 1)
        }
        ContinuousPressIcon(glyph: .add, label: "更长") {
          customMinutes += 1
        }
      }
      .padding(.horizontal, 16)
      .frame(minHeight: 56)

      M3ListItem(title: "章节结束时", action: {
        player.startSleepAtChapterEnd()
        onClose()
      })

      Color.clear.frame(height: 32)
    }
    .presentationDetents([.height(470)])
  }
}

/// 安卓 ContinuousPressIcon：单击一步，长按 500ms 后每 100ms 重复一步
private struct ContinuousPressIcon: View {
  var glyph: AndroidGlyph
  var label: String
  var onTrigger: () -> Void

  @State private var repeatTask: Task<Void, Never>?
  /// 长按已经进入连续触发：松手时不能再补一步
  @State private var repeating = false

  var body: some View {
    AndroidIcon(glyph: glyph, size: 24, color: AppColor.onSurfaceVariant)
      .frame(width: 48, height: 48)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 0)
          .onChanged { _ in
            guard repeatTask == nil else { return }
            repeating = false
            repeatTask = Task { @MainActor in
              try? await Task.sleep(nanoseconds: 500_000_000)
              while !Task.isCancelled {
                repeating = true
                onTrigger()
                try? await Task.sleep(nanoseconds: 100_000_000)
              }
            }
          }
          .onEnded { _ in
            repeatTask?.cancel()
            repeatTask = nil
            if !repeating { onTrigger() }
            repeating = false
          }
      )
      .onDisappear {
        repeatTask?.cancel()
        repeatTask = nil
      }
      .accessibilityLabel(label)
  }
}

// MARK: - 倍速（对齐安卓 SpeedDialog）

struct SpeedSheet: View {
  @ObservedObject var player: PlaybackEngine

  /// 安卓 `maxSpeed = if (speed < 2F) 2F else 3.5F`
  private var maxSpeed: Double { player.speed < 2 ? 2 : 3.5 }

  /// 0.5 … maxSpeed 的归一化位置
  private var normalized: Double {
    let clamped = min(max(player.speed, 0.5), maxSpeed)
    let span = max(maxSpeed - 0.5, 0.0001)
    return (clamped - 0.5) / span
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      // 安卓 DecimalFormat("0.00 x")：AlertDialog 正文色是 onSurfaceVariant
      Text("播放速度: \(Format.speed(player.speed))")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { normalized },
          set: { value in
            // 步进 0.05，落点吸附到刻度上（安卓 steps = rangeSize / 0.05 - 1）
            let raw = 0.5 + value * (maxSpeed - 0.5)
            player.setSpeed((raw / 0.05).rounded() * 0.05)
          }
        ),
        onEditingChanged: { _ in }
      )
    }
  }
}

// MARK: - 跳过片头片尾

/// 跳过片头片尾 —— 安卓是 AlertDialog 里的两行「标签 + M3 滑块」
struct SkipIntroOutroSheet: View {
  var book: Book?
  @ObservedObject var library: LibraryStore

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(rowTitle("跳过片头", value: intro))
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { Double(intro) / Self.maxSeconds },
          set: { setIntro(Int(($0 * Self.maxSeconds).rounded())) }
        ),
        onEditingChanged: { _ in }
      )
      Color.clear.frame(height: 16)
      Text(rowTitle("跳过片尾", value: outro))
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { Double(outro) / Self.maxSeconds },
          set: { setOutro(Int(($0 * Self.maxSeconds).rounded())) }
        ),
        onEditingChanged: { _ in }
      )
    }
  }

  /// 安卓 SKIP_TIME_MAX_SECONDS = 120
  private static let maxSeconds: Double = 120

  private var intro: Int { book?.skipIntro ?? 0 }
  private var outro: Int { book?.skipOutro ?? 0 }

  private func rowTitle(_ label: String, value: Int) -> String {
    value == 0 ? "\(label): 关闭" : "\(label): \(value) 秒"
  }

  private func setIntro(_ value: Int) { update { $0.skipIntro = min(max(value, 0), 120) } }
  private func setOutro(_ value: Int) { update { $0.skipOutro = min(max(value, 0), 120) } }

  private func update(_ mutate: (inout Book) -> Void) {
    guard let book, let index = library.index(of: book.id) else { return }
    mutate(&library.books[index])
    library.save(delay: 0.4)
  }
}

// MARK: - 音量提升对话框

/// 安卓 VolumeGainDialog：标题行「音量提升: 3.0 dB」+ 滑块 0~9
struct VolumeGainPanel: View {
  @ObservedObject var player: PlaybackEngine
  var book: Book?

  private var gain: Double { book?.loudnessGain ?? 0 }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text("音量提升: \(String(format: "%.1f", gain)) dB")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { gain / Self.maxGain },
          set: { player.setLoudnessGain($0 * Self.maxGain) }
        ),
        onEditingChanged: { _ in }
      )
    }
  }

  /// 对齐安卓 VolumeGain.MAX_GAIN = Decibel(9F)
  private static let maxGain: Double = 9
}

// MARK: - 取流失败正文（对齐安卓 SourceErrorDialog）

/// 正文可选可复制：排查源的问题时，用户要能把源的原话原封不动贴出来。
/// 高度上限 + 滚动，免得某个源把整页 HTML 当异常抛出来撑爆弹窗。
struct SourceErrorPanel: View {
  var message: String

  var body: some View {
    ScrollView {
      Text(message)
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .textSelection(.enabled)
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
    .frame(maxHeight: 280)
  }
}

// MARK: - 书签页（对齐安卓 BookmarkScreen）

struct BookmarksView: View {
  @ObservedObject var player: PlaybackEngine
  @ObservedObject var library: LibraryStore
  var onClose: () -> Void

  @State private var addTitle = ""
  @State private var showAdd = false
  @State private var editing: Bookmark?
  @State private var editTitle = ""
  /// 打开行尾 ⋮ 菜单的那一条（安卓是 DropdownMenu「编辑 / 删除」）
  @State private var menuIndex: Int?

  private var book: Book? { player.currentBook }
  private var bookmarks: [Bookmark] { book?.bookmarks ?? [] }

  /// 安卓 ListItem 两行高度固定，菜单按行号定位即可
  private static let rowHeight: CGFloat = 72
  private static let headerHeight: CGFloat = 64

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        GlyphButton(glyph: .close, label: "关闭") { onClose() }
        Text("书签")
          .font(AppFont.titleLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer()
      }
      .frame(height: Self.headerHeight)

      if bookmarks.isEmpty {
        Text("还没有书签。听书时长按书签按钮即可添加。")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .multilineTextAlignment(.center)
          .padding(32)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      } else {
        // 必须用 List：swipeActions 只在 List 的行上生效，放在 ScrollView 里等于没有。
        // 去掉分隔线与行内边距，让它看起来仍是 M3 的 ListItem。
        List {
          ForEach(bookmarks.indices, id: \.self) { index in
            row(index)
              .listRowInsets(EdgeInsets())
              .listRowSeparator(.hidden)
              .listRowBackground(AppColor.background)
          }
          Color.clear
            .frame(height: 88)
            .listRowInsets(EdgeInsets())
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
      }
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(AppColor.background.ignoresSafeArea())
    .overlay(alignment: .topTrailing) {
      if let index = menuIndex, bookmarks.indices.contains(index) {
        M3DropdownMenu(onDismiss: { menuIndex = nil }) {
          M3DropdownItem(title: "编辑") {
            menuIndex = nil
            editing = bookmarks[index]
            editTitle = bookmarks[index].title
          }
          M3DropdownItem(title: "删除") {
            menuIndex = nil
            delete(bookmarks[index])
          }
        }
        .padding(.top, Self.headerHeight + CGFloat(index) * Self.rowHeight + 40)
        .padding(.trailing, 16)
      }
    }
    .overlay(alignment: .bottomTrailing) {
      // 安卓 FloatingActionButton：56dp、圆角 16、primaryContainer 底
      Button {
        addTitle = ""
        showAdd = true
      } label: {
        AndroidIcon(glyph: .add, size: 24, color: AppColor.onPrimaryContainer)
          .frame(width: 56, height: 56)
          .background(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
              .fill(AppColor.primaryContainer)
          )
      }
      .buttonStyle(.plain)
      .padding(16)
      .accessibilityLabel("添加")
    }
    .overlay {
      if showAdd {
        M3TextFieldDialog(
          title: "书签",
          label: "书签名",
          text: $addTitle,
          onConfirm: {
            player.addBookmark(title: addTitle)
            addTitle = ""
            showAdd = false
          },
          onDismiss: {
            addTitle = ""
            showAdd = false
          }
        )
      }
    }
    .overlay {
      if editing != nil {
        M3TextFieldDialog(
          title: "编辑书签",
          label: "书签名",
          text: $editTitle,
          onConfirm: {
            if let bookmark = editing, let book {
              library.updateBookmark(
                bookId: book.id,
                bookmarkId: bookmark.id,
                title: editTitle
              )
            }
            editing = nil
          },
          onDismiss: { editing = nil }
        )
      }
    }
  }

  /// 安卓 ListItem：headline = 标题，supporting = 时间戳，trailing = MoreVert；
  /// 左→右滑删除（SwipeToDismissBox 只开了 StartToEnd）。
  private func row(_ index: Int) -> some View {
    let bookmark = bookmarks[index]
    return HStack(spacing: 0) {
      Button {
        player.seek(toBookmark: bookmark)
        onClose()
      } label: {
        VStack(alignment: .leading, spacing: 2) {
          Text(bookmark.title.isEmpty
            ? titleForChapter(bookmark.chapterIndex)
            : bookmark.title)
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
            .lineLimit(1)
          Text(Format.clockString(bookmark.seconds))
            .font(AppFont.bodyMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)

      Button {
        menuIndex = index
      } label: {
        AndroidIcon(glyph: .moreVert, size: 24, color: AppColor.onSurfaceVariant)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("编辑")
    }
    .padding(.horizontal, 16)
    .frame(height: Self.rowHeight)
    .swipeActions(edge: .leading, allowsFullSwipe: true) {
      Button(role: .destructive) {
        delete(bookmark)
      } label: {
        Label("删除", systemImage: "trash")
      }
    }
  }

  private func titleForChapter(_ index: Int) -> String {
    guard let book, book.chapters.indices.contains(index) else { return "书签" }
    return book.chapters[index].title
  }

  private func delete(_ bookmark: Bookmark) {
    guard let book else { return }
    library.deleteBookmark(bookId: book.id, bookmarkId: bookmark.id)
  }
}
