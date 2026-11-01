import SwiftUI

// MARK: - 播放界面（对齐安卓 BookPlayView）

struct PlayerView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var player: PlaybackEngine

  @State private var showChapters = false
  @State private var showSleep = false
  @State private var showSpeed = false
  @State private var showSkip = false
  @State private var showBookmarks = false
  @State private var showMore = false
  @State private var dragValue: Double?

  private var book: Book? { player.currentBook }

  private var displayPosition: Double { dragValue ?? player.position }

  var body: some View {
    GeometryReader { geo in
      let landscape = geo.size.width > geo.size.height
      // 宽度必须显式钉死：封面用 scaledToFill 时会把图片原始尺寸当理想宽度上报，
      // VStack 会被撑到图片那么宽 —— 于是标题按超宽排版后被屏幕硬裁，
      // 进度条与右侧总时长直接被挤出屏幕外。
      VStack(spacing: 0) {
        topBar(landscape: landscape)
        if landscape {
          // 对齐安卓 BookPlayContent 的 useLandscapeLayout 分支：
          // 左半封面撑满高度，右半章节/进度/控制垂直居中
          HStack(spacing: 0) {
            coverArea
              .frame(maxWidth: .infinity, maxHeight: .infinity)
            controlsColumn
              .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .center)
          }
          .padding(.bottom, 8)
        } else {
          titleRow
          coverArea
          controlsColumn
          Color.clear.frame(height: 24)
        }
      }
      .frame(width: geo.size.width, height: geo.size.height, alignment: .top)
    }
    .background(AppColor.background.ignoresSafeArea())
    .m3Sheet(isPresented: $showChapters) {
      ChapterSheet(player: player) { showChapters = false }
    }
    .m3Sheet(isPresented: $showSleep) {
      SleepSheet(player: player) { showSleep = false }
    }
    .m3Sheet(isPresented: $showSpeed) {
      SpeedSheet(player: player)
    }
    .m3Sheet(isPresented: $showSkip) {
      SkipIntroOutroSheet(book: book, library: library)
    }
    .fullScreenCover(isPresented: $showBookmarks) {
      BookmarksView(player: player, library: library) { showBookmarks = false }
    }
    .alert("播放出错", isPresented: Binding(
      get: { player.errorMessage != nil },
      set: { if !$0 { player.errorMessage = nil } }
    )) {
      Button("知道了", role: .cancel) { player.errorMessage = nil }
    } message: {
      Text(player.errorMessage ?? "")
    }
    .overlay(alignment: .bottom) {
      if let toast = app.toast {
        Text(toast)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
          .padding(.horizontal, 16)
          .padding(.vertical, 14)
          .background(AppColor.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 4))
          .padding(.bottom, 24)
      }
    }
  }

  // MARK: 顶部栏

  /// 章节 + 进度 + 控制，竖屏直接排在封面下，横屏放进右半栏
  @ViewBuilder
  private var controlsColumn: some View {
    // 用 Color.clear 而非 Spacer：Spacer 是弹性视图，会跟封面抢剩余高度，
    // 结果封面被压扁、控件区反而留白。这里间距必须是死的。
    if book != nil && (book?.chapters.count ?? 0) > 1 {
      Color.clear.frame(height: 16)
      chapterRow
      Color.clear.frame(height: 20)
    } else {
      Color.clear.frame(height: 20)
    }
    sliderRow
    Color.clear.frame(height: 16)
    playbackRow
  }

  private func topBar(landscape: Bool) -> some View {
    HStack(spacing: 0) {
      IconButton48(systemName: "xmark", label: "关闭") {
        player.flush()
        app.showPlayer = false
      }
      // 横屏走紧凑顶栏（安卓 TopAppBar），书名移到标题位；竖屏用下方大标题
      if landscape {
        MarqueeText(
          text: book?.title ?? "",
          font: AppFont.titleLarge(),
          color: AppColor.onSurface
        )
        .frame(height: 28)
        // 交给跑马灯占满剩余宽度（它自己是 GeometryReader，跟 Spacer 抢会只分到一半）
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 8)
      } else {
        Spacer(minLength: 0)
      }
      IconButton48(
        systemName: player.sleepActive ? "moon.fill" : "moon",
        label: "睡眠定时器"
      ) {
        if player.sleepActive {
          player.cancelSleepTimer()
        } else {
          showSleep = true
        }
      }
      // 书签：单击打开列表，长按在当前位置加书签
      Button {
        showBookmarks = true
      } label: {
        Image(systemName: "books.vertical")
          .font(.system(size: 24))
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(width: 40, height: 40)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .simultaneousGesture(
        LongPressGesture(minimumDuration: 0.5).onEnded { _ in
          player.addBookmark()
          app.showToast("已添加书签")
        }
      )
      .accessibilityLabel("书签")

      IconButton48(systemName: "speedometer", label: "播放速度") {
        showSpeed = true
      }
      IconButton48(systemName: "ellipsis", label: "更多") {
        showMore = true
      }
      .confirmationDialog("更多", isPresented: $showMore, titleVisibility: .hidden) {
        Button("跳过片头片尾") { showSkip = true }
        Button("取消", role: .cancel) {}
      }
    }
    .padding(.horizontal, 4)
    .frame(height: landscape ? 56 : 64)
  }

  // MARK: 标题（LargeTopAppBar 展开态，对齐安卓 AppBarTitle 的跑马灯）

  private var titleRow: some View {
    MarqueeText(
      text: book?.title ?? "",
      font: AppFont.headlineMedium(),
      color: AppColor.onSurface
    )
    .frame(height: 38)
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal, 16)
    .padding(.bottom, 8)
  }

  // MARK: 封面（占满剩余高度，圆角 20）

  private var coverArea: some View {
    ZStack(alignment: .topTrailing) {
      // 同 OnlineCover：先给确定的框，再 overlay 图片，避免 scaledToFill 撑破容器
      Color.clear
        .overlay {
          if let book, let image = player.coverImage(for: book) {
            Image(uiImage: image)
              .resizable()
              .scaledToFill()
          } else {
            AppColor.surfaceVariant
              .overlay {
                Image(systemName: "opticaldisc")
                  .font(.system(size: 64))
                  .foregroundColor(AppColor.onSurfaceVariant.opacity(0.5))
              }
          }
        }
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
        .onTapGesture(count: 2) { player.togglePlayPause() }
        .accessibilityLabel("封面")

      if player.sleepActive {
        TimeBadge(text: sleepBadgeText)
          .padding(.top, 8)
          .padding(.trailing, 8)
      }
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .padding(.horizontal, 16)
    .padding(.top, 8)
  }

  private var sleepBadgeText: String {
    if player.sleepAtChapterEnd { return "章节结束时" }
    return Format.clockString(player.sleepRemaining)
  }

  // MARK: 章节行

  private var chapterRow: some View {
    HStack(spacing: 0) {
      IconButton48(systemName: "chevron.left", iconSize: 36, label: "上一节") {
        player.previousChapter()
      }
      Button {
        showChapters = true
      } label: {
        HStack(spacing: 0) {
          Color.clear.frame(width: 28, height: 28)
          Text(player.currentChapter?.title ?? "")
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
            .multilineTextAlignment(.center)
            .lineLimit(1)
            .truncationMode(.middle)
            .padding(.horizontal, 4)
          Image(systemName: "chevron.down")
            .font(.system(size: 28))
            .foregroundColor(AppColor.onSurface)
            .frame(width: 28, height: 28)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 16)
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      IconButton48(systemName: "chevron.right", iconSize: 36, label: "下一节") {
        player.nextChapter()
      }
    }
    .padding(.horizontal, 8)
  }

  // MARK: 进度行

  private var sliderRow: some View {
    HStack(spacing: 0) {
      // 两段时间固定宽度 + 等宽数字：否则 0:00:00 → 1:23:45 之间宽度变化会把滑块挤得左右跳
      Text(Format.clockString(displayPosition, duration: player.duration))
        .font(AppFont.bodyLarge())
        .monospacedDigit()
        .foregroundColor(AppColor.onSurface)
        .lineLimit(1)
        .fixedSize()
        .frame(minWidth: 58, alignment: .leading)
      ExpressiveSlider(
        value: Binding(
          get: { dragValue ?? (player.duration > 0 ? player.position / player.duration : 0) },
          set: { dragValue = $0 * player.duration }
        ),
        onEditingChanged: { editing in
          if !editing, let preview = dragValue {
            player.seek(to: preview)
            dragValue = nil
          }
        }
      )
      .padding(.horizontal, 8)
      Text(Format.clockString(player.duration, duration: player.duration))
        .font(AppFont.bodyLarge())
        .monospacedDigit()
        .foregroundColor(AppColor.onSurface)
        .lineLimit(1)
        .fixedSize()
        .frame(minWidth: 58, alignment: .trailing)
    }
    .padding(.horizontal, 16)
  }

  // MARK: 播放控制行

  private var playbackRow: some View {
    HStack(spacing: 0) {
      Button {
        player.skip(-Double(settings.settings.seekSeconds))
      } label: {
        Image(systemName: "arrow.uturn.backward")
          .font(.system(size: 40))
          .foregroundColor(AppColor.onSurface)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("回退")

      Spacer().frame(width: 16)

      ZStack {
        M3PlayButton(playing: player.isPlaying, size: 80, iconSize: 36) {
          player.togglePlayPause()
        }
        if player.isLoading {
          ProgressView()
            .progressViewStyle(.circular)
            .tint(AppColor.primary)
            .frame(width: 88, height: 88)
        }
      }
      .frame(width: 88, height: 88)

      Spacer().frame(width: 16)

      Button {
        player.skip(Double(settings.settings.seekSeconds))
      } label: {
        Image(systemName: "arrow.uturn.forward")
          .font(.system(size: 40))
          .foregroundColor(AppColor.onSurface)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("快进")
    }
    .frame(maxWidth: .infinity)
  }
}

// MARK: - Material3 Expressive 滑块（16dp 轨道 + 竖条 thumb + 末端圆点）

struct ExpressiveSlider: View {
  @Binding var value: Double
  var onEditingChanged: (Bool) -> Void

  var body: some View {
    GeometryReader { geo in
      let width = geo.size.width
      let clamped = min(1, max(0, value))
      ZStack(alignment: .leading) {
        Capsule()
          .fill(AppColor.secondaryContainer)
          .frame(height: 16)
        Capsule()
          .fill(AppColor.primary)
          .frame(width: max(0, clamped * width), height: 16)
        // 末端停靠圆点
        Circle()
          .fill(AppColor.onSurfaceVariant)
          .frame(width: 4, height: 4)
          .position(x: width - 2, y: 28)
        // 竖条 thumb
        RoundedRectangle(cornerRadius: 2, style: .continuous)
          .fill(AppColor.primary)
          .frame(width: 4, height: 44)
          .position(x: max(2, clamped * width), y: 28)
      }
      .frame(height: 56)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 0)
          .onChanged { gesture in
            onEditingChanged(true)
            value = min(1, max(0, gesture.location.x / max(width, 1)))
          }
          .onEnded { gesture in
            value = min(1, max(0, gesture.location.x / max(width, 1)))
            onEditingChanged(false)
          }
      )
    }
    .frame(height: 56)
  }
}
