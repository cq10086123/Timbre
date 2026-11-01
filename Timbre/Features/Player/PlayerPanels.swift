import SwiftUI

// MARK: - 章节列表抽屉

struct ChapterSheet: View {
  @ObservedObject var player: PlaybackEngine
  var onClose: () -> Void

  /// 点击跳转后短暂高亮的块序号，2 秒后自动消失（对齐安卓 HIGHLIGHT_DURATION）
  @State private var highlightedRange: Int?

  private var chapters: [Chapter] { player.currentBook?.chapters ?? [] }

  private var starts: [Double] {
    var result: [Double] = []
    var acc: Double = 0
    for chapter in chapters {
      result.append(acc)
      acc += chapter.duration
    }
    return result
  }

  /// 分块跳转网格：集数太少时可以直接滚，不显示网格
  private struct ChapterRange {
    var first: Int
    var last: Int
    var startIndex: Int
  }

  /// 对齐安卓 ChapterRanges.kt：<30 不显示，>2000 每块 100，否则每块 50
  private var ranges: [ChapterRange] {
    let count = chapters.count
    guard count >= 30 else { return [] }
    let size = count > 2000 ? 100 : 50
    var result: [ChapterRange] = []
    var start = 0
    while start < count {
      let end = min(start + size, count)
      result.append(ChapterRange(first: start + 1, last: end, startIndex: start))
      start = end
    }
    return result
  }

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 4) {
        Text("共\(chapters.count)集")
          .font(AppFont.titleMedium())
          .foregroundColor(AppColor.onSurface)
      }
      .frame(maxWidth: .infinity)
      .padding(.vertical, 12)

      ScrollViewReader { proxy in
        VStack(spacing: 0) {
          if !ranges.isEmpty { rangeGrid(proxy) }

          ScrollView {
            LazyVStack(spacing: 0) {
              ForEach(chapters.indices, id: \.self) { index in
                let chapter = chapters[index]
                Button {
                  if let book = player.currentBook {
                    player.open(book, chapterIndex: index, autoplay: true)
                  }
                  onClose()
                } label: {
                  HStack(spacing: 0) {
                    Text("\(index + 1)")
                      .font(AppFont.bodyMedium())
                      .foregroundColor(AppColor.onSurfaceVariant)
                      .frame(minWidth: 32, alignment: .leading)
                    Text(chapter.title)
                      .font(AppFont.bodyLarge())
                      .foregroundColor(AppColor.onSurface)
                      .lineLimit(1)
                    Spacer(minLength: 12)
                    Text(Format.clockString(starts[index] + chapter.duration))
                      .font(AppFont.bodySmall())
                      .foregroundColor(AppColor.onSurfaceVariant)
                  }
                  .padding(.horizontal, 16)
                  .padding(.vertical, 12)
                  .background(
                    index == player.chapterIndex
                      ? AppColor.primaryContainer : Color.clear
                  )
                  .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
                  .padding(.horizontal, 3)
                  .padding(.vertical, 3)
                  .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .id(index)
              }
            }
            .padding(.bottom, 24)
          }
        }
        .onAppear {
          if player.chapterIndex > 0 {
            proxy.scrollTo(max(0, player.chapterIndex - 1), anchor: .top)
          }
        }
      }
    }
    .presentationDetents([.large])
  }

  /// 3 列网格，高度上限 300（对齐安卓 RANGE_GRID_MAX_HEIGHT）
  private func rangeGrid(_ proxy: ScrollViewProxy) -> some View {
    ScrollView {
      LazyVGrid(
        columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 3),
        spacing: 8
      ) {
        ForEach(ranges.indices, id: \.self) { index in
          let range = ranges[index]
          let isCurrent =
            player.chapterIndex >= range.startIndex && player.chapterIndex <= range.last - 1
          Button {
            proxy.scrollTo(range.startIndex, anchor: .top)
            highlightedRange = index
            DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
              if highlightedRange == index { highlightedRange = nil }
            }
          } label: {
            Text("\(range.first) - \(range.last)")
              .font(AppFont.bodyMedium())
              .foregroundColor(
                isCurrent ? AppColor.onPrimaryContainer : AppColor.onSurfaceVariant
              )
              .frame(maxWidth: .infinity)
              .frame(height: 40)
              .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                  .fill(
                    highlightedRange == index
                      ? AppColor.primary
                      : (isCurrent ? AppColor.primaryContainer : AppColor.surfaceContainerHigh)
                  )
              )
          }
          .buttonStyle(.plain)
        }
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 8)
    }
    .frame(maxHeight: 300)
    .overlay(alignment: .bottom) {
      Rectangle()
        .fill(AppColor.outlineVariant.opacity(0.4))
        .frame(height: 1)
    }
  }
}

// MARK: - 睡眠定时器抽屉

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
        .frame(maxWidth: .infinity, alignment: .center)
        .padding(.horizontal, 16)
        .padding(.top, 8)
      Spacer().frame(height: 16)

      ForEach(presets, id: \.self) { minutes in
        M3ListItem(title: "\(minutes) 分钟", action: {
          player.startSleepTimer(minutes: minutes)
          onClose()
        })
      }

      HStack(spacing: 0) {
        Text("\(customMinutes) 分钟")
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer(minLength: 8)
        Button {
          customMinutes = max(1, customMinutes - 1)
        } label: {
          Image(systemName: "minus")
            .font(.system(size: 24))
            .foregroundColor(AppColor.onSurfaceVariant)
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("更短")
        Button {
          customMinutes += 1
        } label: {
          Image(systemName: "plus")
            .font(.system(size: 24))
            .foregroundColor(AppColor.onSurfaceVariant)
            .frame(width: 48, height: 48)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("更长")
      }
      .padding(.horizontal, 16)
      .frame(minHeight: 56)

      M3ListItem(title: "章节结束时", action: {
        player.startSleepAtChapterEnd()
        onClose()
      })

      Spacer().frame(height: 32)
    }
    .presentationDetents([.height(470)])
  }
}

// MARK: - 倍速

struct SpeedSheet: View {
  @ObservedObject var player: PlaybackEngine

  private var maxSpeed: Double { player.speed < 2 ? 2 : 3.5 }

  var body: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text("播放速度")
        .font(AppFont.headlineSmall())
        .foregroundColor(AppColor.onSurface)
      Spacer().frame(height: 16)
      Text("播放速度: \(Format.speed(player.speed))")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
      Spacer().frame(height: 12)
      Slider(
        value: Binding(
          get: { min(player.speed, maxSpeed) },
          set: { player.setSpeed(($0 / 0.05).rounded() * 0.05) }
        ),
        in: 0.5...maxSpeed,
        step: 0.05
      )
      .tint(AppColor.primary)
      Spacer().frame(height: 24)
    }
    .padding(24)
    .frame(maxWidth: .infinity, alignment: .leading)
    .presentationDetents([.height(240)])
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
      Color.clear.frame(height: 20)
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

// MARK: - 书签页

struct BookmarksView: View {
  @ObservedObject var player: PlaybackEngine
  @ObservedObject var library: LibraryStore
  var onClose: () -> Void

  @State private var addTitle = ""
  @State private var showAdd = false
  @State private var editing: Bookmark?
  @State private var editTitle = ""

  private var book: Book? { player.currentBook }
  private var bookmarks: [Bookmark] { book?.bookmarks ?? [] }

  var body: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        IconButton48(systemName: "xmark", label: "关闭") { onClose() }
        Text("书签")
          .font(AppFont.titleLarge())
          .foregroundColor(AppColor.onSurface)
        Spacer()
      }
      .frame(height: 64)

      if bookmarks.isEmpty {
        Text("还没有书签。听书时长按书签按钮即可添加。")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .multilineTextAlignment(.center)
          .padding(32)
          .frame(maxWidth: .infinity, maxHeight: .infinity)
      } else {
        ScrollView {
          LazyVStack(spacing: 0) {
            ForEach(bookmarks) { bookmark in
              HStack(spacing: 0) {
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
                      .lineLimit(2)
                    Text(Format.clockString(bookmark.seconds))
                      .font(AppFont.bodyMedium())
                      .foregroundColor(AppColor.onSurfaceVariant)
                  }
                  .frame(maxWidth: .infinity, alignment: .leading)
                  .contentShape(Rectangle())
                }
                .buttonStyle(.plain)

                Button {
                  editing = bookmark
                  editTitle = bookmark.title
                } label: {
                  Image(systemName: "ellipsis")
                    .font(.system(size: 20))
                    .foregroundColor(AppColor.onSurfaceVariant)
                    .frame(width: 48, height: 48)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
              }
              .padding(.horizontal, 16)
              .padding(.vertical, 8)
              .swipeActions(edge: .trailing, allowsFullSwipe: true) {
                Button(role: .destructive) {
                  delete(bookmark)
                } label: {
                  Image(systemName: "trash")
                }
              }
            }
          }
          .padding(.bottom, 96)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .overlay(alignment: .bottomTrailing) {
      Button {
        addTitle = ""
        showAdd = true
      } label: {
        Image(systemName: "plus")
          .font(.system(size: 24))
          .foregroundColor(AppColor.onPrimaryContainer)
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
    .alert("书签", isPresented: $showAdd) {
      TextField("书签名", text: $addTitle)
      Button("设置") {
        player.addBookmark(title: addTitle)
        addTitle = ""
      }
      Button("取消", role: .cancel) { addTitle = "" }
    }
    .alert("编辑书签", isPresented: Binding(
      get: { editing != nil },
      set: { if !$0 { editing = nil } }
    )) {
      TextField("书签名", text: $editTitle)
      Button("设置") {
        if let bookmark = editing, let book {
          library.updateBookmark(bookId: book.id, bookmarkId: bookmark.id, title: editTitle)
        }
        editing = nil
      }
      Button("取消", role: .cancel) { editing = nil }
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
