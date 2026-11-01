import SwiftUI

struct PlayerView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.dismiss) private var dismiss

  @State private var dragValue: Double = 0
  @State private var isDragging = false
  @State private var showSpeed = false
  @State private var showChapters = false
  @State private var showSleep = false
  @State private var showBookmarks = false

  var body: some View {
    GeometryReader { geometry in
      ZStack {
        background

        if let book = player.currentBook, let chapter = player.currentChapter {
          VStack(spacing: 12) {
            topBar(book)
            Spacer(minLength: 4)
            titleBlock(book, chapter: chapter)
            cover(book, available: geometry.size)
            Spacer(minLength: 4)
            chapterNav(book, chapter: chapter)
            slider
            transport(book)
            bottomRow(book)
          }
          .padding(.horizontal, 20)
          .padding(.top, 8)
          .padding(.bottom, 18)
        } else {
          VStack(spacing: 14) {
            Image(systemName: "music.note")
              .font(.system(size: 40, weight: .light))
              .foregroundColor(AppColor.textSecondary)
            Text("没有正在播放的内容")
              .foregroundColor(AppColor.textSecondary)
            Button("关闭") { dismiss() }
              .foregroundColor(AppColor.accent)
          }
        }
      }
    }
    .sheet(isPresented: $showSpeed) {
      SpeedPanel()
        .environmentObject(player)
        .environmentObject(settings)
        .presentationDetents([.height(300)])
    }
    .sheet(isPresented: $showChapters) {
      ChapterPanel()
        .environmentObject(player)
        .environmentObject(app)
        .presentationDetents([.medium, .large])
    }
    .sheet(isPresented: $showSleep) {
      SleepTimerPanel()
        .environmentObject(player)
        .environmentObject(settings)
        .presentationDetents([.height(420)])
    }
    .sheet(isPresented: $showBookmarks) {
      BookmarksPanel()
        .environmentObject(player)
        .environmentObject(library)
        .environmentObject(app)
        .presentationDetents([.medium, .large])
    }
    .alert(
      "播放出错",
      isPresented: Binding(
        get: { player.errorMessage != nil },
        set: { if !$0 { player.errorMessage = nil } }
      )
    ) {
      Button("知道了", role: .cancel) { player.errorMessage = nil }
    } message: {
      Text(player.errorMessage ?? "")
    }
  }

  // MARK: - 背景

  private var background: some View {
    ZStack {
      AppColor.background
      if let book = player.currentBook, let image = CoverStore.image(for: book) {
        Image(uiImage: image)
          .resizable()
          .scaledToFill()
          .blur(radius: 60)
          .opacity(0.28)
          .clipped()
      }
      LinearGradient(
        colors: [AppColor.background.opacity(0.35), AppColor.background.opacity(0.96)],
        startPoint: .top,
        endPoint: .bottom
      )
    }
    .ignoresSafeArea()
  }

  // MARK: - 顶栏

  private func topBar(_ book: Book) -> some View {
    HStack(spacing: 18) {
      Button {
        player.flush()
        dismiss()
      } label: {
        Image(systemName: "xmark")
          .font(.system(size: 17, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
      }

      Spacer()

      Button {
        showSleep = true
      } label: {
        Image(systemName: player.sleepActive ? "moon.fill" : "moon")
          .font(.system(size: 17))
          .foregroundColor(player.sleepActive ? AppColor.accent : AppColor.textPrimary)
      }

      Button {
        showChapters = true
      } label: {
        Image(systemName: "list.bullet.rectangle")
          .font(.system(size: 17))
          .foregroundColor(AppColor.textPrimary)
      }

      Button {
        showSpeed = true
      } label: {
        Image(systemName: "speedometer")
          .font(.system(size: 17))
          .foregroundColor(player.speed == 1.0 ? AppColor.textPrimary : AppColor.accent)
      }

      Menu {
        Button {
          showBookmarks = true
        } label: {
          Label("书签", systemImage: "bookmark")
        }
        Button {
          player.addBookmark()
          app.showToast("已添加书签")
        } label: {
          Label("在此处添加书签", systemImage: "bookmark.fill")
        }
        Divider()
        Button {
          player.setSpeed(1.0)
        } label: {
          Label("恢复 1x 速度", systemImage: "1.circle")
        }
        if library.index(of: book.id) != nil {
          Button {
            library.resetPosition(bookId: book.id)
            player.seek(to: 0)
            app.showToast("已清除播放进度")
          } label: {
            Label("清除播放进度", systemImage: "arrow.counterclockwise")
          }
        }
      } label: {
        Image(systemName: "ellipsis")
          .font(.system(size: 17, weight: .medium))
          .foregroundColor(AppColor.textPrimary)
      }
    }
  }

  // MARK: - 标题

  private func titleBlock(_ book: Book, chapter: Chapter) -> some View {
    VStack(spacing: 5) {
      Text(book.title)
        .font(.system(size: 19, weight: .semibold))
        .foregroundColor(AppColor.textPrimary)
        .lineLimit(2)
        .multilineTextAlignment(.center)
      Text(book.author.isEmpty ? book.chapterCountText : book.author)
        .font(.system(size: 13))
        .foregroundColor(AppColor.textSecondary)
        .lineLimit(1)
    }
  }

  // MARK: - 封面

  private func cover(_ book: Book, available: CGSize) -> some View {
    let size = min(available.width - 40, available.height * 0.44)
    return BookCoverView(book: book, size: size, cornerRadius: 18)
      .shadow(color: Color.black.opacity(0.45), radius: 24, y: 12)
  }

  // MARK: - 章节切换

  private func chapterNav(_ book: Book, chapter: Chapter) -> some View {
    HStack(spacing: 8) {
      Button {
        player.previousChapter()
      } label: {
        Image(systemName: "chevron.left")
          .font(.system(size: 15, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
          .frame(width: 40, height: 34)
      }
      .disabled(player.chapterIndex == 0)

      Button {
        showChapters = true
      } label: {
        HStack(spacing: 4) {
          Text(chapter.title)
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(AppColor.textPrimary)
            .lineLimit(1)
          Image(systemName: "chevron.down")
            .font(.system(size: 10, weight: .semibold))
            .foregroundColor(AppColor.textSecondary)
        }
        .frame(maxWidth: .infinity)
      }

      Button {
        player.nextChapter()
      } label: {
        Image(systemName: "chevron.right")
          .font(.system(size: 15, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
          .frame(width: 40, height: 34)
      }
    }
  }

  // MARK: - 进度条

  private var slider: some View {
    VStack(spacing: 2) {
      Slider(
        value: Binding(
          get: { isDragging ? dragValue : player.position },
          set: { value in
            dragValue = value
            isDragging = true
          }
        ),
        in: 0...max(player.duration, 1),
        onEditingChanged: { editing in
          if editing {
            dragValue = player.position
            isDragging = true
          } else {
            player.seek(to: dragValue)
            isDragging = false
          }
        }
      )
      .tint(AppColor.accent)

      HStack {
        Text(Format.time(isDragging ? dragValue : player.position))
          .font(.system(size: 11, design: .monospaced))
          .foregroundColor(AppColor.textSecondary)
        Spacer()
        Text(Format.remaining(player.duration - (isDragging ? dragValue : player.position)))
          .font(.system(size: 11, design: .monospaced))
          .foregroundColor(AppColor.textSecondary)
      }
    }
  }

  // MARK: - 播放控制

  private func transport(_ book: Book) -> some View {
    HStack(spacing: 34) {
      Button {
        player.skip(-Double(settings.settings.seekSeconds))
      } label: {
        skipIcon(forward: false)
      }

      Button {
        player.togglePlayPause()
      } label: {
        ZStack {
          Circle()
            .fill(AppColor.accent)
            .frame(width: 74, height: 74)
          if player.isLoading {
            ProgressView()
              .progressViewStyle(.circular)
              .tint(AppColor.background)
          } else {
            Image(systemName: player.isPlaying ? "pause.fill" : "play.fill")
              .font(.system(size: 28, weight: .semibold))
              .foregroundColor(AppColor.background)
              .offset(x: player.isPlaying ? 0 : 2)
          }
        }
      }

      Button {
        player.skip(Double(settings.settings.seekSeconds))
      } label: {
        skipIcon(forward: true)
      }
    }
    .padding(.vertical, 4)
  }

  private func bottomRow(_ book: Book) -> some View {
    HStack {
      Button {
        player.addBookmark()
        app.showToast("已添加书签")
      } label: {
        HStack(spacing: 5) {
          Image(systemName: "bookmark")
          Text("书签")
        }
        .font(.system(size: 12))
        .foregroundColor(AppColor.textSecondary)
      }

      Spacer()

      if player.sleepActive {
        Text(sleepLabel)
          .font(.system(size: 11))
          .foregroundColor(AppColor.accent)
      }

      Spacer()

      Button {
        showSpeed = true
      } label: {
        Text(Format.speed(player.speed))
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(AppColor.accent)
      }
    }
  }

  private func skipIcon(forward: Bool) -> some View {
    ZStack {
      Image(systemName: forward ? "goforward" : "gobackward")
        .font(.system(size: 29, weight: .regular))
        .foregroundColor(AppColor.textPrimary)
      Text("\(settings.settings.seekSeconds)")
        .font(.system(size: 9, weight: .bold))
        .foregroundColor(AppColor.textPrimary)
        .offset(y: 0.5)
    }
    .frame(width: 42, height: 42)
  }

  private var sleepLabel: String {
    if player.sleepAtChapterEnd { return "本集结束停止" }
    return "\(Format.time(player.sleepRemaining)) 后停止"
  }
}
