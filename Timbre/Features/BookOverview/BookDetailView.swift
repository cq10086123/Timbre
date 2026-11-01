import SwiftUI

struct BookDetailView: View {
  let bookId: String

  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.dismiss) private var dismiss

  @State private var showRename = false
  @State private var draftTitle = ""
  @State private var draftAuthor = ""
  @State private var showDeleteConfirm = false

  private var book: Book? { library.book(withId: bookId) }

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()
      if let book {
        ScrollView {
          VStack(alignment: .leading, spacing: 20) {
            header(book)
            actionRow(book)
            skipSettings(book)
            if !book.bookmarks.isEmpty { bookmarkSection(book) }
            chapterSection(book)
          }
          .padding(.horizontal, 16)
          .padding(.bottom, 120)
        }
      } else {
        Text("这本书已被删除")
          .foregroundColor(AppColor.textSecondary)
      }
    }
    .navigationTitle(book?.title ?? "书籍详情")
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .navigationBarTrailing) {
        Menu {
          Button {
            draftTitle = book?.title ?? ""
            draftAuthor = book?.author ?? ""
            showRename = true
          } label: {
            Label("重命名", systemImage: "pencil")
          }
          Button {
            if let book { library.resetPosition(bookId: book.id) }
            app.showToast("已清除播放进度")
          } label: {
            Label("清除播放进度", systemImage: "arrow.counterclockwise")
          }
          Button(role: .destructive) {
            showDeleteConfirm = true
          } label: {
            Label("删除这本书", systemImage: "trash")
          }
        } label: {
          Image(systemName: "ellipsis.circle")
        }
      }
    }
    .alert("重命名", isPresented: $showRename) {
      TextField("书名", text: $draftTitle)
      TextField("作者", text: $draftAuthor)
      Button("保存") {
        if let book { app.rename(book, title: draftTitle, author: draftAuthor) }
      }
      Button("取消", role: .cancel) {}
    }
    .alert("删除确认", isPresented: $showDeleteConfirm) {
      Button("删除", role: .destructive) {
        if let book {
          app.delete(book)
          dismiss()
        }
      }
      Button("取消", role: .cancel) {}
    } message: {
      Text("会同时删除已导入的音频文件，无法恢复。")
    }
  }

  // MARK: - 头部

  private func header(_ book: Book) -> some View {
    HStack(alignment: .top, spacing: 16) {
      BookCoverView(book: book, size: 118, cornerRadius: 16)
      VStack(alignment: .leading, spacing: 8) {
        Text(book.title)
          .font(.system(size: 20, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
          .lineLimit(3)
        if !book.author.isEmpty {
          Text(book.author)
            .font(.subheadline)
            .foregroundColor(AppColor.textSecondary)
        }
        HStack(spacing: 8) {
          SourceBadge(book: book)
          Text(book.chapterCountText)
            .font(.caption)
            .foregroundColor(AppColor.textSecondary)
        }
        Text(Format.humanDuration(book.totalDuration))
          .font(.caption)
          .foregroundColor(AppColor.textSecondary)
        Spacer(minLength: 0)
        ProgressView(value: book.progress)
          .progressViewStyle(.linear)
          .tint(AppColor.accent)
          .frame(height: 4)
      }
      .frame(maxWidth: .infinity, alignment: .leading)
    }
  }

  private func actionRow(_ book: Book) -> some View {
    HStack(spacing: 12) {
      Button {
        // 「开始播放 / 继续播放」是明确的播放意图，不是单纯打开播放页
        app.openBook(book, autoplay: true)
      } label: {
        Label(
          book.position.isStarted ? "继续播放" : "开始播放",
          systemImage: "play.fill"
        )
        .font(.subheadline.weight(.semibold))
        .foregroundColor(AppColor.background)
        .frame(maxWidth: .infinity)
        .padding(.vertical, 13)
        .background(AppColor.accent, in: Capsule())
      }
      Button {
        player.open(book, chapterIndex: 0, autoplay: true)
        app.showPlayer = true
      } label: {
        Label("从头播放", systemImage: "arrow.uturn.backward")
          .font(.subheadline.weight(.medium))
          .foregroundColor(AppColor.textPrimary)
          .padding(.horizontal, 16)
          .padding(.vertical, 13)
          .background(AppColor.surfaceHigh, in: Capsule())
      }
    }
  }

  // MARK: - 片头片尾

  private func skipSettings(_ book: Book) -> some View {
    VStack(spacing: 0) {
      HStack {
        Text("跳过片头")
          .font(.subheadline)
          .foregroundColor(AppColor.textPrimary)
        Spacer()
        Picker("跳过片头", selection: Binding(
          get: { book.skipIntro },
          set: { value in
            var updated = book
            updated.skipIntro = value
            library.update(updated)
          }
        )) {
          ForEach([0, 3, 5, 10, 15, 20, 30, 60], id: \.self) { value in
            Text(value == 0 ? "关闭" : "\(value) 秒").tag(value)
          }
        }
        .pickerStyle(.menu)
        .tint(AppColor.accent)
      }
      .padding(.horizontal, 14)
      .padding(.vertical, 10)

      Divider().background(AppColor.divider)

      HStack {
        Text("跳过片尾")
          .font(.subheadline)
          .foregroundColor(AppColor.textPrimary)
        Spacer()
        Picker("跳过片尾", selection: Binding(
          get: { book.skipOutro },
          set: { value in
            var updated = book
            updated.skipOutro = value
            library.update(updated)
          }
        )) {
          ForEach([0, 5, 10, 15, 20, 30, 60], id: \.self) { value in
            Text(value == 0 ? "关闭" : "\(value) 秒").tag(value)
          }
        }
        .pickerStyle(.menu)
        .tint(AppColor.accent)
      }
      .padding(.horizontal, 14)
      .padding(.vertical, 10)
    }
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
  }

  // MARK: - 书签

  private func bookmarkSection(_ book: Book) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Text("书签")
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(AppColor.textPrimary)
      VStack(spacing: 0) {
        ForEach(book.bookmarks) { mark in
          Button {
            player.open(book, chapterIndex: mark.chapterIndex, autoplay: true)
            player.seek(to: mark.seconds)
            app.showPlayer = true
          } label: {
            HStack {
              Image(systemName: "bookmark.fill")
                .font(.system(size: 12))
                .foregroundColor(AppColor.accent)
              VStack(alignment: .leading, spacing: 2) {
                Text(mark.title)
                  .font(.system(size: 14))
                  .foregroundColor(AppColor.textPrimary)
                  .lineLimit(1)
                Text(Format.time(mark.seconds))
                  .font(.system(size: 11))
                  .foregroundColor(AppColor.textSecondary)
              }
              Spacer()
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 10)
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
      .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
  }

  // MARK: - 章节

  private func chapterSection(_ book: Book) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack {
        Text("章节")
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(AppColor.textPrimary)
        Spacer()
        Text(playPositionText(book))
          .font(.caption)
          .foregroundColor(AppColor.textSecondary)
      }

      LazyVStack(spacing: 0) {
        ForEach(book.chapters.indices, id: \.self) { index in
          let chapter = book.chapters[index]
          Button {
            app.playChapter(bookId: book.id, index: index)
          } label: {
            HStack(spacing: 12) {
              Text("\(index + 1)")
                .font(.system(size: 12, weight: .medium, design: .rounded))
                .foregroundColor(isCurrent(book, index) ? AppColor.accent : AppColor.textSecondary)
                .frame(width: 28, alignment: .trailing)
              Text(chapter.title)
                .font(.system(size: 14))
                .foregroundColor(isCurrent(book, index) ? AppColor.accent : AppColor.textPrimary)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
              Spacer(minLength: 8)
              if chapter.duration > 0 {
                Text(Format.time(chapter.duration))
                  .font(.system(size: 11))
                  .foregroundColor(AppColor.textSecondary)
              }
              if isCurrent(book, index) {
                Image(systemName: "waveform")
                  .font(.system(size: 11))
                  .foregroundColor(AppColor.accent)
              }
            }
            .padding(.horizontal, 14)
            .padding(.vertical, 11)
          }
          .buttonStyle(.plain)

          if index < book.chapters.count - 1 {
            Divider().background(AppColor.divider).padding(.leading, 54)
          }
        }
      }
      .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }
  }

  private func isCurrent(_ book: Book, _ index: Int) -> Bool {
    player.currentBook?.id == book.id && player.chapterIndex == index
  }

  private func playPositionText(_ book: Book) -> String {
    guard book.position.isStarted else { return "未开始" }
    let index = min(max(0, book.position.chapterIndex), max(0, book.chapters.count - 1))
    let chapter = book.chapters.indices.contains(index) ? book.chapters[index].title : ""
    return "\(chapter) · \(Format.time(book.position.seconds))"
  }
}
