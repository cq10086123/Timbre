import SwiftUI

// MARK: - 倍速

struct SpeedPanel: View {
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.dismiss) private var dismiss
  @State private var value: Double = 1.0

  private let presets: [Double] = [0.75, 1.0, 1.25, 1.5, 1.75, 2.0, 2.5, 3.0]

  var body: some View {
    VStack(spacing: 18) {
      Text("播放速度")
        .font(.headline)
        .foregroundColor(AppColor.textPrimary)

      Text(Format.speed(value))
        .font(.system(size: 34, weight: .semibold, design: .rounded))
        .foregroundColor(AppColor.accent)
        .monospacedDigit()

      Slider(value: $value, in: 0.5...3.0, step: 0.05)
        .tint(AppColor.accent)
        .padding(.horizontal, 6)

      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8), count: 4), spacing: 8) {
        ForEach(presets, id: \.self) { preset in
          Button {
            value = preset
            player.setSpeed(preset)
          } label: {
            Text(Format.speed(preset))
              .font(.system(size: 12, weight: .medium))
              .foregroundColor(abs(preset - value) < 0.001 ? AppColor.background : AppColor.textPrimary)
              .frame(maxWidth: .infinity)
              .padding(.vertical, 8)
              .background(
                abs(preset - value) < 0.001 ? AppColor.accent : AppColor.surfaceHigh,
                in: Capsule()
              )
          }
          .buttonStyle(.plain)
        }
      }

      Spacer(minLength: 0)
    }
    .padding(20)
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(AppColor.background)
    .onAppear { value = player.speed }
    .onChange(of: value) { newValue in
      player.setSpeed(newValue)
    }
  }
}

// MARK: - 章节

struct ChapterPanel: View {
  @EnvironmentObject private var player: PlaybackEngine
  @EnvironmentObject private var app: AppState
  @Environment(\.dismiss) private var dismiss

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()
      VStack(spacing: 0) {
        HStack {
          Text("章节")
            .font(.headline)
            .foregroundColor(AppColor.textPrimary)
          Spacer()
          if let book = player.currentBook {
            Text(book.chapterCountText)
              .font(.caption)
              .foregroundColor(AppColor.textSecondary)
          }
          Button("完成") { dismiss() }
            .font(.subheadline.weight(.medium))
            .padding(.leading, 10)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)

        Divider().background(AppColor.divider)

        if let book = player.currentBook {
          ScrollViewReader { proxy in
            ScrollView {
              LazyVStack(spacing: 0) {
                ForEach(book.chapters.indices, id: \.self) { index in
                  let chapter = book.chapters[index]
                  Button {
                    player.open(book, chapterIndex: index, autoplay: true)
                    dismiss()
                  } label: {
                    HStack(spacing: 10) {
                      Text("\(index + 1)")
                        .font(.system(size: 12, design: .rounded))
                        .foregroundColor(AppColor.textSecondary)
                        .frame(width: 26, alignment: .trailing)
                      Text(chapter.title)
                        .font(.system(size: 14))
                        .foregroundColor(
                          index == player.chapterIndex ? AppColor.accent : AppColor.textPrimary
                        )
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                      Spacer(minLength: 6)
                      if chapter.duration > 0 {
                        Text(Format.time(chapter.duration))
                          .font(.system(size: 11))
                          .foregroundColor(AppColor.textSecondary)
                      }
                    }
                    .padding(.horizontal, 16)
                    .padding(.vertical, 11)
                  }
                  .buttonStyle(.plain)
                  .id(index)
                }
              }
            }
            .onAppear {
              proxy.scrollTo(player.chapterIndex, anchor: .center)
            }
          }
        } else {
          Spacer()
        }
      }
    }
  }
}

// MARK: - 睡眠定时器

struct SleepTimerPanel: View {
  @EnvironmentObject private var player: PlaybackEngine
  @EnvironmentObject private var settings: SettingsStore
  @Environment(\.dismiss) private var dismiss

  private let options = [5, 10, 15, 20, 30, 45, 60, 90]

  var body: some View {
    VStack(alignment: .leading, spacing: 16) {
      HStack {
        Text("睡眠定时器")
          .font(.headline)
          .foregroundColor(AppColor.textPrimary)
        Spacer()
        if player.sleepActive {
          Text(player.sleepAtChapterEnd ? "本集结束" : Format.time(player.sleepRemaining))
            .font(.system(size: 13, weight: .medium))
            .foregroundColor(AppColor.accent)
        }
      }

      LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 10), count: 4), spacing: 10) {
        ForEach(options, id: \.self) { minutes in
          Button {
            player.startSleepTimer(minutes: minutes)
            dismiss()
          } label: {
            Text("\(minutes) 分")
              .font(.system(size: 13, weight: .medium))
              .foregroundColor(AppColor.textPrimary)
              .frame(maxWidth: .infinity)
              .padding(.vertical, 11)
              .background(AppColor.surfaceHigh, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
          }
          .buttonStyle(.plain)
        }
      }

      Button {
        player.startSleepAtChapterEnd()
        dismiss()
      } label: {
        HStack {
          Image(systemName: "text.line.last.and.arrowtriangle.forward")
          Text("播完这一集停止")
          Spacer()
        }
        .font(.system(size: 14))
        .foregroundColor(AppColor.textPrimary)
        .padding(.horizontal, 14)
        .padding(.vertical, 12)
        .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
      }
      .buttonStyle(.plain)

      if player.sleepActive {
        HStack(spacing: 10) {
          Button {
            player.extendSleepTimer(minutes: 15)
            dismiss()
          } label: {
            Text("延长 15 分钟")
              .font(.system(size: 13, weight: .medium))
              .foregroundColor(AppColor.accent)
              .frame(maxWidth: .infinity)
              .padding(.vertical, 11)
              .background(AppColor.accentSoft, in: Capsule())
          }
          .buttonStyle(.plain)

          Button {
            player.cancelSleepTimer()
            dismiss()
          } label: {
            Text("关闭定时")
              .font(.system(size: 13, weight: .medium))
              .foregroundColor(AppColor.danger)
              .frame(maxWidth: .infinity)
              .padding(.vertical, 11)
              .background(AppColor.surfaceHigh, in: Capsule())
          }
          .buttonStyle(.plain)
        }
      }

      Toggle(isOn: $settings.settings.sleepFadeOut) {
        Text("到点前渐弱淡出")
          .font(.system(size: 13))
          .foregroundColor(AppColor.textSecondary)
      }
      .tint(AppColor.accent)

      Spacer(minLength: 0)
    }
    .padding(20)
    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    .background(AppColor.background)
  }
}

// MARK: - 书签

struct BookmarksPanel: View {
  @EnvironmentObject private var player: PlaybackEngine
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var app: AppState
  @Environment(\.dismiss) private var dismiss

  private var book: Book? { player.currentBook }

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()
      VStack(spacing: 0) {
        HStack {
          Text("书签")
            .font(.headline)
            .foregroundColor(AppColor.textPrimary)
          Spacer()
          Button {
            player.addBookmark()
            app.showToast("已添加书签")
          } label: {
            Label("添加", systemImage: "plus")
              .font(.subheadline.weight(.medium))
          }
          Button("完成") { dismiss() }
            .font(.subheadline.weight(.medium))
            .padding(.leading, 10)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 14)

        Divider().background(AppColor.divider)

        if let book, !book.bookmarks.isEmpty {
          ScrollView {
            LazyVStack(spacing: 0) {
              ForEach(book.bookmarks) { mark in
                Button {
                  player.open(book, chapterIndex: mark.chapterIndex, autoplay: true)
                  player.seek(to: mark.seconds)
                  dismiss()
                } label: {
                  HStack(spacing: 10) {
                    Image(systemName: "bookmark.fill")
                      .font(.system(size: 12))
                      .foregroundColor(AppColor.accent)
                    VStack(alignment: .leading, spacing: 3) {
                      Text(mark.title)
                        .font(.system(size: 14))
                        .foregroundColor(AppColor.textPrimary)
                        .lineLimit(1)
                      Text(Format.time(mark.seconds))
                        .font(.system(size: 11))
                        .foregroundColor(AppColor.textSecondary)
                    }
                    Spacer(minLength: 0)
                  }
                  .padding(.horizontal, 18)
                  .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
                .contextMenu {
                  Button(role: .destructive) {
                    library.deleteBookmark(bookId: book.id, bookmarkId: mark.id)
                  } label: {
                    Label("删除书签", systemImage: "trash")
                  }
                }
              }
            }
          }
        } else {
          VStack(spacing: 10) {
            Spacer()
            Image(systemName: "bookmark")
              .font(.system(size: 34, weight: .light))
              .foregroundColor(AppColor.textSecondary)
            Text("还没有书签")
              .font(.subheadline)
              .foregroundColor(AppColor.textSecondary)
            Spacer()
          }
          .frame(maxWidth: .infinity)
        }
      }
    }
  }
}
