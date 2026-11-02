import SwiftUI
import UniformTypeIdentifiers

struct ExtensionsView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var extensions: ExtensionStore
  @Environment(\.dismiss) private var dismiss

  @State private var showImporter = false
  @State private var showLinkDialog = false
  @State private var linkText = ""
  @State private var busy = false
  @State private var toast: String?
  @State private var errorText: String?
  @State private var uninstallTarget: InstalledExtension?

  /// 默认参考源：本仓库 samples/Timbre.jdr 的 jsDelivr 直链。
  /// raw.githubusercontent.com 部分用户无法访问，jsDelivr 国内可达；
  /// 「从链接导入」打开时预填这个地址，用户不需要可自行清空更换。
  static let defaultSourceURL =
    "https://cdn.jsdelivr.net/gh/cq10086123/Timbre@main/samples/Timbre.jdr"

  /// Android 打开文件选择器用的是 `*/*`，这里同理放宽：.jdr 是自定义扩展名，
  /// 只允许 .data 会让部分「文件」提供方把它置灰点不动。
  private var importTypes: [UTType] {
    var types: [UTType] = []
    if let jdr = UTType(filenameExtension: "jdr") { types.append(jdr) }
    if let zip = UTType(filenameExtension: "zip") { types.append(zip) }
    types.append(.archive)
    types.append(.data)
    return types
  }

  var body: some View {
    ZStack(alignment: .bottom) {
      AppColor.background.ignoresSafeArea()

      VStack(spacing: 0) {
        topBar
          .alert("从链接导入", isPresented: $showLinkDialog) {
            TextField("https://example.com/source.jdr", text: $linkText)
              .textInputAutocapitalization(.never)
              .autocorrectionDisabled()
              .keyboardType(.URL)
            Button("导入") { importLink() }
              .disabled(linkText.trimmingCharacters(in: .whitespaces).isEmpty)
            Button("取消", role: .cancel) { linkText = "" }
          }
        ScrollView {
          LazyVStack(spacing: 0) {
            importRows
            if extensions.extensions.isEmpty {
              emptyText
            } else {
              ForEach(extensions.extensions) { ext in
                packageHeader(ext)
                ForEach(ext.sources) { source in
                  sourceRow(ext: ext, source: source)
                }
              }
            }
            Spacer().frame(height: 40)
          }
        }
        .alert("导入失败", isPresented: errorBinding) {
          Button("知道了", role: .cancel) { errorText = nil }
        } message: {
          Text(errorText ?? "")
        }
      }

      if let toast {
        snackbar(toast)
          .padding(.bottom, 24)
          .transition(.opacity.combined(with: .move(edge: .bottom)))
      }
    }
    .animation(.easeInOut(duration: 0.2), value: toast)
    .fileImporter(
      isPresented: $showImporter,
      allowedContentTypes: importTypes,
      allowsMultipleSelection: false
    ) { result in
      switch result {
      case .success(let urls):
        if let url = urls.first { importFile(url) }
      case .failure(let error):
        showError("选择文件失败：\(error.localizedDescription)")
      }
    }
    // 卸载确认走 confirmationDialog：和上面两个 alert 属于不同呈现类型，不会互相抢
    .confirmationDialog(
      "卸载扩展包",
      isPresented: Binding(
        get: { uninstallTarget != nil },
        set: { if !$0 { uninstallTarget = nil } }
      ),
      titleVisibility: .visible,
      presenting: uninstallTarget
    ) { ext in
      Button("卸载扩展包", role: .destructive) {
        extensions.remove(ext)
        uninstallTarget = nil
        showToast("已卸载 \(ext.name)")
      }
      Button("取消", role: .cancel) { uninstallTarget = nil }
    } message: { _ in
      Text("确定卸载该扩展包吗？其源将从搜索中移除；已加入书架的书籍在删除前仍可播放。")
    }
  }

  // MARK: - 顶栏

  private var topBar: some View {
    HStack(spacing: 0) {
      IconButton48(systemName: "xmark", label: "关闭") { dismiss() }
      Text("接口源管理")
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
      Spacer()
    }
    .frame(height: 64)
  }

  // MARK: - 导入入口

  private var importRows: some View {
    VStack(spacing: 0) {
      M3ListItem(
        title: "导入 .jdr 文件",
        subtitle: "从本机选择一个 .jdr 扩展包",
        action: { showImporter = true }
      ) {
        if busy { ProgressView().scaleEffect(0.85) }
      }
      M3ListItem(
        title: "从链接导入",
        subtitle: "下载并安装网络上的 .jdr 扩展包",
        action: {
          // 默认预填项目自带的参考源（仓库 samples/Timbre.jdr 的 GitHub 直链），
          // 不需要的话清掉换成自己的链接就行
          linkText = Self.defaultSourceURL
          // iOS 16 的 alert TextField 有个渲染怪癖：同一帧里先赋值再弹出，
          // 输入框显示成空白（文本其实已经写进去了，所以点导入能正常用）。
          // 延一拍再弹，让预填的链接看得见。
          DispatchQueue.main.asyncAfter(deadline: .now() + 0.15) { showLinkDialog = true }
        }
      )
      .disabled(busy)
    }
  }

  private var emptyText: some View {
    Text("尚未安装接口源。「从链接导入」已预填作者提供的参考源，点导入即可使用；不需要可换成自己的链接。")
      .font(AppFont.bodyMedium())
      .foregroundColor(AppColor.onSurfaceVariant)
      .frame(maxWidth: .infinity, alignment: .leading)
      .padding(24)
  }

  // MARK: - 包 / 源

  private func packageHeader(_ ext: InstalledExtension) -> some View {
    M3ListItem(
      title: ext.name,
      subtitle: ext.author.isEmpty ? "版本 \(ext.version)" : "版本 \(ext.version)\n作者：\(ext.author)"
    ) {
      Button {
        uninstallTarget = ext
      } label: {
        Image(systemName: "trash")
          .font(.system(size: 18))
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(width: 48, height: 48)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel("卸载扩展包")
    }
    .padding(.top, 8)
  }

  private func sourceRow(ext: InstalledExtension, source: ExtensionSourceMeta) -> some View {
    M3ListItem(
      title: source.name,
      subtitle: "jdr:\(source.id) · \(scriptStatus(ext: ext, source: source))"
    ) {
      Toggle(
        "",
        isOn: Binding(
          get: { ext.isSourceEnabled(source.id) },
          set: { extensions.setSourceEnabled(ext, sourceId: source.id, enabled: $0) }
        )
      )
      .labelsHidden()
      .tint(AppColor.primary)
    }
  }

  /// 直接读盘确认脚本在不在：在线搜索失败的常见原因就是脚本没落到沙盒里，
  /// 与其让用户猜，不如把状态摆在源名下面。
  private func scriptStatus(ext: InstalledExtension, source: ExtensionSourceMeta) -> String {
    let url = AppPaths.url(for: ext.folder).appendingPathComponent(source.script)
    guard let attrs = try? FileManager.default.attributesOfItem(atPath: url.path),
      let size = attrs[.size] as? NSNumber
    else {
      return "脚本缺失"
    }
    let kb = Double(size.intValue) / 1024
    return String(format: "脚本 %.1f KB", kb)
  }

  // MARK: - 提示

  private func snackbar(_ text: String) -> some View {
    Text(text)
      .font(AppFont.bodyMedium())
      .foregroundColor(AppColor.surfaceContainerLowest)
      .padding(.horizontal, 16)
      .padding(.vertical, 14)
      .background(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
          .fill(AppColor.onSurface.opacity(0.92))
      )
      .padding(.horizontal, 16)
  }

  private var errorBinding: Binding<Bool> {
    Binding(
      get: { errorText != nil },
      set: { if !$0 { errorText = nil } }
    )
  }

  private func showToast(_ text: String) {
    toast = text
    DispatchQueue.main.asyncAfter(deadline: .now() + 2.6) {
      if toast == text { toast = nil }
    }
  }

  private func showError(_ text: String) {
    errorText = text
  }

  // MARK: - 动作

  private func importFile(_ url: URL) {
    runImport {
      try await app.installExtension(url: url)
    }
  }

  private func importLink() {
    let link = linkText.trimmingCharacters(in: .whitespacesAndNewlines)
    linkText = ""
    runImport {
      try await app.installExtension(link: link)
    }
  }

  /// 解析在后台线程做，回写 @Published 与提示都在主线程，避免「后台线程发布变更」告警
  private func runImport(_ work: @escaping () async throws -> InstalledExtension) {
    guard !busy else { return }
    busy = true
    Task { @MainActor in
      do {
        let installed = try await work()
        busy = false
        showToast(
          "已导入 \(installed.name) v\(installed.version)（\(installed.sources.count) 个源）"
        )
      } catch {
        busy = false
        showError(error.localizedDescription)
      }
    }
  }
}
