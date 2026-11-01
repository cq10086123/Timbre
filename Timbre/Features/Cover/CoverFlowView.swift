import SwiftUI
import UniformTypeIdentifiers
import UIKit

/// 换封面入口 —— 对齐安卓 `EditCoverDialog` 的两条路：
/// 从文件里选一张图，或者联网搜图，选完都进裁剪，最后落成正方形封面。
struct CoverFlowView: View {
  var book: Book
  var onCancel: () -> Void
  /// 裁好的图片数据交给调用方落盘
  var onApply: (Data) -> Void

  private enum Stage: Equatable {
    case menu
    case search
    case crop
  }

  @State private var stage: Stage = .menu
  /// 裁剪页点「取消」回到哪一步（从文件来的回菜单，从搜图来的回搜索）
  @State private var cropBack: Stage = .menu
  @State private var showFilePicker = false
  @State private var cropImage: UIImage?
  @State private var errorText: String?

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()

      switch stage {
      case .menu:
        menu
      case .search:
        CoverSearchView(
          initialQuery: book.title,
          onCancel: { stage = .menu },
          onPick: { image in
            cropImage = image
            cropBack = .search
            stage = .crop
          }
        )
      case .crop:
        if let cropImage {
          CoverCropView(
            image: cropImage,
            onCancel: { stage = cropBack },
            onDone: { cropped in
              guard let data = Self.encode(cropped) else {
                errorText = "图片处理失败，换一张试试"
                return
              }
              onApply(data)
            }
          )
        } else {
          Color.clear
        }
      }
    }
    .sheet(isPresented: $showFilePicker) {
      DocumentPicker(contentTypes: [.image], allowsMultiple: false) { urls in
        showFilePicker = false
        guard let url = urls.first else { return }
        loadFile(url)
      }
      .ignoresSafeArea()
    }
    .alert("换封面失败", isPresented: Binding(
      get: { errorText != nil },
      set: { if !$0 { errorText = nil } }
    )) {
      Button("好", role: .cancel) { errorText = nil }
    } message: {
      Text(errorText ?? "")
    }
  }

  // MARK: - 选择来源

  private var menu: some View {
    VStack(spacing: 0) {
      HStack(spacing: 0) {
        Button("取消", action: onCancel)
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(minWidth: 64, minHeight: 48, alignment: .leading)
        Spacer(minLength: 0)
        Text("更换封面")
          .font(AppFont.titleMedium())
          .foregroundColor(AppColor.onSurface)
        Spacer(minLength: 0)
        Color.clear.frame(width: 64, height: 48)
      }
      .padding(.horizontal, 16)
      .padding(.top, 8)

      Spacer().frame(height: 24)

      M3ListItem(
        systemName: "folder",
        title: "从文件选择",
        subtitle: "从「文件」App 里挑一张本地图片",
        action: { showFilePicker = true }
      )

      M3ListItem(
        systemName: "magnifyingglass",
        title: "搜索网络图片",
        subtitle: "按书名搜索封面，选一张再裁剪",
        action: { stage = .search }
      )

      Spacer()
    }
  }

  // MARK: - 文件

  private func loadFile(_ url: URL) {
    let accessed = url.startAccessingSecurityScopedResource()
    defer { if accessed { url.stopAccessingSecurityScopedResource() } }
    guard let data = try? Data(contentsOf: url), let image = UIImage(data: data) else {
      errorText = "这个文件不是有效的图片"
      return
    }
    cropImage = image
    // 从菜单进来的，裁完点「取消」应该回到菜单而不是搜索页
    cropBack = .menu
    stage = .crop
  }

  /// 优先 JPEG（体积小），失败退回 PNG
  static func encode(_ image: UIImage) -> Data? {
    if let jpeg = image.jpegData(compressionQuality: 0.9) { return jpeg }
    return image.pngData()
  }
}

// MARK: - 网络搜图

struct CoverSearchView: View {
  var initialQuery: String
  var onCancel: () -> Void
  var onPick: (UIImage) -> Void

  @State private var query: String = ""
  @State private var results: [CoverSearchClient.Item] = []
  @State private var page = 1
  @State private var loading = false
  @State private var failed: String?
  @State private var picking: String?
  @State private var searched = false

  private let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: 3)

  var body: some View {
    VStack(spacing: 0) {
      header

      HStack(spacing: 8) {
        TextField("搜索封面关键词", text: $query)
          .textInputAutocapitalization(.never)
          .autocorrectionDisabled()
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .padding(.horizontal, 16)
          .frame(height: 48)
          .background(AppColor.surfaceContainerHighest, in: Capsule())
          .onSubmit { Task { await search(reset: true) } }
        Button {
          Task { await search(reset: true) }
        } label: {
          Text("搜索")
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onPrimary)
            .padding(.horizontal, 16)
            .frame(height: 48)
            .background(AppColor.primary, in: Capsule())
        }
        .buttonStyle(.plain)
        .disabled(loading)
      }
      .padding(.horizontal, 16)
      .padding(.bottom, 12)

      content
    }
    .onAppear {
      if query.isEmpty {
        query = initialQuery
        Task { await search(reset: true) }
      }
    }
  }

  private var header: some View {
    HStack(spacing: 0) {
      Button("返回", action: onCancel)
        .font(AppFont.labelLarge())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(minWidth: 64, minHeight: 48, alignment: .leading)
      Spacer(minLength: 0)
      Text("搜索封面")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
      Spacer(minLength: 0)
      Color.clear.frame(width: 64, height: 48)
    }
    .padding(.horizontal, 16)
    .padding(.top, 8)
  }

  @ViewBuilder
  private var content: some View {
    if loading && results.isEmpty {
      VStack(spacing: 12) {
        ProgressView().tint(AppColor.primary)
        Text("正在搜索…")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else if let failed {
      VStack(spacing: 12) {
        Text(failed)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .multilineTextAlignment(.center)
          .padding(.horizontal, 32)
        Button("重试") { Task { await search(reset: true) } }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else if results.isEmpty {
      Text(searched ? "没搜到图片，换个关键词试试" : "输入关键词搜索封面")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    } else {
      ScrollView {
        LazyVGrid(columns: columns, spacing: 8) {
          ForEach(results) { item in
            Button {
              Task { await pick(item) }
            } label: {
              ZStack {
                AppColor.surfaceVariant
                AsyncImage(url: URL(string: item.thumbnail)) { phase in
                  if let image = phase.image {
                    image.resizable().scaledToFill()
                  } else if phase.error != nil {
                    AndroidIcon(glyph: .close, size: 20, color: AppColor.onSurfaceVariant)
                  } else {
                    ProgressView().tint(AppColor.onSurfaceVariant)
                  }
                }
                if picking == item.image {
                  Color.black.opacity(0.45)
                  ProgressView().tint(.white)
                }
              }
              .frame(height: 120)
              .clipped()
              .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
            }
            .buttonStyle(.plain)
          }
        }
        .padding(.horizontal, 16)

        Button {
          Task { await search(reset: false) }
        } label: {
          Text(loading ? "加载中…" : "换一批")
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.primary)
            .frame(maxWidth: .infinity)
            .frame(height: 48)
        }
        .buttonStyle(.plain)
        .disabled(loading)
        .padding(.vertical, 12)
      }
    }
  }

  // MARK: - 动作

  @MainActor
  private func search(reset: Bool) async {
    let text = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !text.isEmpty else { return }
    loading = true
    failed = nil
    if reset {
      page = 1
      results = []
    }
    do {
      let items = try await CoverSearchClient.search(query: text, page: page)
      if reset {
        results = items
      } else {
        // 去重，别让「换一批」叠出一堆重复封面
        let existing = Set(results.map(\.image))
        results += items.filter { !existing.contains($0.image) }
      }
      searched = true
      page += 1
    } catch {
      if reset { failed = error.localizedDescription }
    }
    loading = false
  }

  @MainActor
  private func pick(_ item: CoverSearchClient.Item) async {
    guard picking == nil else { return }
    picking = item.image
    defer { picking = nil }
    guard
      let data = await CoverSearchClient.download(item.image),
      let image = UIImage(data: data)
    else {
      failed = "这张图下载不下来，换一张试试"
      return
    }
    onPick(image)
  }
}
