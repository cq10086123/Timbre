import SwiftUI

/// 选备份目录 —— 对齐安卓 `WebDavBackupFolderScreen`：
/// 先选服务器，再一层层点进去，最后「使用此目录」。
///
/// 备份绝不会自己猜一个路径：目录必须是用户亲手点进来的。
struct WebDavBackupFolderView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var webdav: WebDavStore
  @Environment(\.dismiss) private var dismiss

  private struct Crumb: Identifiable, Hashable {
    var url: URL
    var title: String
    var id: String { url.absoluteString }
  }

  @State private var server: WebDavServer?
  @State private var stack: [Crumb] = []
  @State private var items: [WebDavResource] = []
  @State private var loading = false
  @State private var errorText: String?
  @State private var started = false

  private var current: Crumb? { stack.last }

  private var folders: [WebDavResource] {
    WebDavAudio.sorted(items.filter { $0.isDirectory })
  }

  var body: some View {
    VStack(spacing: 0) {
      topBar
      content
      bottomBar
    }
    .background(AppColor.background.ignoresSafeArea())
    .onAppear(perform: start)
  }

  private var title: String {
    guard let server else { return "选择服务器" }
    if let current {
      return "\(server.name) · \(relativePath)"
    }
    return server.name
  }

  private var relativePath: String {
    guard let server, let current else { return "/" }
    let base = server.normalizedBase
    let full = current.url.absoluteString
    guard full.hasPrefix(base) else { return "/" }
    let rest = String(full.dropFirst(base.count))
    return rest.isEmpty ? "/" : rest
  }

  private var topBar: some View {
    HStack(spacing: 0) {
      IconButton48(
        systemName: current == nil ? "xmark" : "chevron.left",
        label: current == nil ? "关闭" : "上一级"
      ) {
        if current == nil {
          dismiss()
        } else {
          stack.removeLast()
          reload()
        }
      }
      Text(title)
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
        .lineLimit(1)
      Spacer(minLength: 8)
    }
    .padding(.horizontal, 4)
    .frame(height: 64)
  }

  @ViewBuilder
  private var content: some View {
    if loading {
      HStack {
        Spacer()
        ProgressView()
        Spacer()
      }
      .frame(maxHeight: .infinity)
    } else if let errorText {
      VStack(spacing: 12) {
        Image(systemName: "exclamationmark.triangle")
          .font(.system(size: 40))
          .foregroundColor(AppColor.onSurfaceVariant)
        Text(errorText)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .multilineTextAlignment(.center)
          .padding(.horizontal, 32)
        Button("重试") { reload() }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 20)
          .frame(height: 44)
          .overlay(Capsule().stroke(AppColor.outlineVariant, lineWidth: 1))
          .buttonStyle(.plain)
      }
      .frame(maxHeight: .infinity)
    } else if server == nil {
      if webdav.servers.isEmpty {
        Text("请先添加一个 WebDAV 服务器")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .frame(maxHeight: .infinity)
      } else {
        ScrollView {
          VStack(spacing: 0) {
            ForEach(webdav.servers) { item in
              M3ListItem(
                systemName: "externaldrive.badge.icloud",
                title: item.name,
                subtitle: item.normalizedBase,
                action: { choose(item) }
              )
            }
          }
        }
      }
    } else if folders.isEmpty {
      Text("没有子文件夹，可直接使用当前目录")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .multilineTextAlignment(.center)
        .padding(.horizontal, 32)
        .frame(maxHeight: .infinity)
    } else {
      ScrollView {
        VStack(spacing: 0) {
          ForEach(folders) { item in
            M3ListItem(
              systemName: "folder",
              title: item.name,
              action: { open(item) }
            )
          }
        }
      }
    }
  }

  @ViewBuilder
  private var bottomBar: some View {
    if server != nil, errorText == nil, !loading {
      VStack(spacing: 0) {
        Rectangle()
          .fill(AppColor.outlineVariant.opacity(0.4))
          .frame(height: 1)
        Button {
          guard let server, let current else { return }
          app.backup.setLocation(serverId: server.id, folderUrl: current.url.absoluteString)
          dismiss()
        } label: {
          Text("使用此目录")
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(
              RoundedRectangle(cornerRadius: 16, style: .continuous).fill(AppColor.primary)
            )
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
      }
      .background(AppColor.surfaceContainerLow)
    }
  }

  // MARK: - 动作

  private func start() {
    guard !started else { return }
    started = true
    // 只有一个服务器就直接进去，省一次没有意义的选择
    if webdav.servers.count == 1, let only = webdav.servers.first {
      choose(only)
    }
  }

  private func choose(_ item: WebDavServer) {
    server = item
    guard let root = URL(string: item.normalizedBase + "/") else {
      errorText = "服务器地址不合法"
      return
    }
    stack = [Crumb(url: root, title: item.name)]
    reload()
  }

  private func open(_ resource: WebDavResource) {
    guard let url = URL(string: resource.url) else { return }
    stack.append(Crumb(url: url, title: resource.name))
    reload()
  }

  private func reload() {
    guard let server, let current else { return }
    loading = true
    errorText = nil
    Task {
      do {
        let list = try await WebDavClient.list(
          url: current.url,
          server: server,
          password: webdav.password(for: server.id)
        )
        items = list
      } catch {
        items = []
        errorText = error.localizedDescription
      }
      loading = false
    }
  }
}
