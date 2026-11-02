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

  /// 拖动中的归一化预览值。拖动期间**不碰 player**：
  /// 以前每帧都调 `setSpeed()`，它要改 `@Published speed`、改 `library.books`
  /// 并落盘、还要刷一次锁屏信息 —— 一秒 60 次的活，滑块直接划不动。
  @State private var preview: Double?

  /// 安卓 `maxSpeed = if (speed < 2F) 2F else 3.5F`
  private var maxSpeed: Double { player.speed < 2 ? 2 : 3.5 }

  /// 0.5 … maxSpeed 的归一化位置
  private var liveFraction: Double {
    let clamped = min(max(player.speed, 0.5), maxSpeed)
    let span = max(maxSpeed - 0.5, 0.0001)
    return (clamped - 0.5) / span
  }

  /// 预览值 → 真实倍速（步进 0.05，对齐安卓 steps = rangeSize / 0.05 - 1）
  private func speed(fromFraction fraction: Double) -> Double {
    let raw = 0.5 + min(1, max(0, fraction)) * (maxSpeed - 0.5)
    return (raw / 0.05).rounded() * 0.05
  }

  private var shownSpeed: Double {
    preview.map { speed(fromFraction: $0) } ?? player.speed
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      // 安卓 DecimalFormat("0.00 x")：AlertDialog 正文色是 onSurfaceVariant
      Text("播放速度: \(Format.speed(shownSpeed))")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { preview ?? liveFraction },
          set: { preview = $0 }
        ),
        onEditingChanged: { editing in
          guard !editing, let fraction = preview else { return }
          player.setSpeed(speed(fromFraction: fraction))
          preview = nil
        }
      )
    }
  }
}

// MARK: - 跳过片头片尾

/// 跳过片头片尾 —— 安卓是 AlertDialog 里的两行「标签 + M3 滑块」
struct SkipIntroOutroSheet: View {
  var book: Book?
  @ObservedObject var library: LibraryStore

  /// 拖动中的预览（0…1）。拖动期间不写库 —— 否则一帧一次整库编码 + 落盘。
  @State private var introPreview: Double?
  @State private var outroPreview: Double?

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text(rowTitle("跳过片头", value: introShown))
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { introPreview ?? Double(intro) / Self.maxSeconds },
          set: { introPreview = $0 }
        ),
        onEditingChanged: { editing in
          guard !editing, let fraction = introPreview else { return }
          setIntro(Self.seconds(fromFraction: fraction))
          introPreview = nil
        }
      )
      Color.clear.frame(height: 16)
      Text(rowTitle("跳过片尾", value: outroShown))
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { outroPreview ?? Double(outro) / Self.maxSeconds },
          set: { outroPreview = $0 }
        ),
        onEditingChanged: { editing in
          guard !editing, let fraction = outroPreview else { return }
          setOutro(Self.seconds(fromFraction: fraction))
          outroPreview = nil
        }
      )
    }
  }

  /// 安卓 SKIP_TIME_MAX_SECONDS = 120
  private static let maxSeconds: Double = 120

  private static func seconds(fromFraction fraction: Double) -> Int {
    Int((min(1, max(0, fraction)) * maxSeconds).rounded())
  }

  private var intro: Int { book?.skipIntro ?? 0 }
  private var outro: Int { book?.skipOutro ?? 0 }
  private var introShown: Int {
    introPreview.map { Self.seconds(fromFraction: $0) } ?? intro
  }
  private var outroShown: Int {
    outroPreview.map { Self.seconds(fromFraction: $0) } ?? outro
  }

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

  /// 拖动中的预览（0…1）。setLoudnessGain 会重建音频处理链，一帧一次根本扛不住。
  @State private var preview: Double?

  private var gain: Double { book?.loudnessGain ?? 0 }
  private var shownGain: Double {
    preview.map { min(1, max(0, $0)) * Self.maxGain } ?? gain
  }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text("音量提升: \(String(format: "%.1f", shownGain)) dB")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      ExpressiveSlider(
        value: Binding(
          get: { preview ?? gain / Self.maxGain },
          set: { preview = $0 }
        ),
        onEditingChanged: { editing in
          guard !editing, let fraction = preview else { return }
          player.setLoudnessGain(min(1, max(0, fraction)) * Self.maxGain)
          preview = nil
        }
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
  /// 每一行当前所在的 y（滚动时实时刷新），菜单靠它定位。
  ///
  /// 故意用引用类型 + @State 存：preference 每滚一帧都变，
  /// 写进 @State 值类型会让整页跟着重建；写进类的属性不会触发重绘，
  /// 只在真正要开菜单（menuIndex 变化）时读一次最新值。
  @State private var rowTops = BookmarkRowTops()

  private var book: Book? { player.currentBook }
  private var bookmarks: [Bookmark] { book?.bookmarks ?? [] }

  /// 安卓 ListItem 两行高度固定，菜单按行号定位即可
  private static let rowHeight: CGFloat = 72
  private static let headerHeight: CGFloat = 64
  /// 行位置测量用的坐标空间名
  private static let space = "timbre.bookmarks"

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
    // 行位置靠这个坐标系报上来：菜单以前按「行号 × 行高」硬算，
    // 列表一滚动菜单就飘到别的行上，点「删除」会删错书签
    .coordinateSpace(name: Self.space)
    .onPreferenceChange(BookmarkRowTopKey.self) { rowTops.tops = $0 }
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
        // 没量到（preference 还没上报）时退回旧的算法，至少不会更差
        .padding(
          .top,
          (rowTops.tops[index] ?? (Self.headerHeight + CGFloat(index) * Self.rowHeight)) + 40
        )
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
    // 把这一行当前所在的 y 报给外层，菜单才能贴着被点的那一行（滚动也跟着走）
    .background(
      GeometryReader { geo in
        Color.clear.preference(
          key: BookmarkRowTopKey.self,
          value: [index: geo.frame(in: .named(BookmarksView.space)).minY]
        )
      }
    )
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

/// 书签每一行在页面里的实时 y 位置：菜单按它定位，滚动时不会飘到别的行
private struct BookmarkRowTopKey: PreferenceKey {
  static var defaultValue: [Int: CGFloat] = [:]

  static func reduce(value: inout [Int: CGFloat], nextValue: () -> [Int: CGFloat]) {
    value.merge(nextValue()) { _, new in new }
  }
}

/// 行位置的持有者：类实例放进 @State 里，改它的属性不会触发 SwiftUI 重绘
private final class BookmarkRowTops {
  var tops: [Int: CGFloat] = [:]
}
