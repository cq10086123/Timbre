import SwiftUI

// MARK: - 服务器列表

struct WebDavServersView: View {
  @EnvironmentObject private var webdav: WebDavStore
  @EnvironmentObject private var library: LibraryStore
  @Environment(\.dismiss) private var dismiss

  @State private var editing: WebDavServer?
  @State private var showForm = false
  @State private var browsing: WebDavServer?
  @State private var deletingId: String?

  var body: some View {
    VStack(spacing: 0) {
      topBar

      ScrollView {
        VStack(spacing: 0) {
          if webdav.servers.isEmpty {
            emptyState
          } else {
            ForEach(webdav.servers) { server in
              row(server)
            }
          }
          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .sheet(isPresented: $showForm) {
      WebDavServerForm(existing: editing)
        .environmentObject(webdav)
    }
    .fullScreenCover(item: $browsing) { server in
      WebDavBrowseView(server: server)
        .environmentObject(webdav)
        .environmentObject(library)
    }
    .confirmationDialog(
      "删除服务器",
      isPresented: Binding(
        get: { deletingId != nil },
        set: { if !$0 { deletingId = nil } }
      ),
      titleVisibility: .visible
    ) {
      Button("删除", role: .destructive) {
        if let id = deletingId { webdav.delete(id) }
        deletingId = nil
      }
      Button("取消", role: .cancel) { deletingId = nil }
    } message: {
      Text("移除该服务器及其书目注册，不会删除 NAS 上的文件。")
    }
  }

  private var topBar: some View {
    HStack(spacing: 0) {
      IconButton48(systemName: "xmark", label: "关闭") { dismiss() }
      Text("WebDAV")
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
      Spacer(minLength: 0)
      IconButton48(systemName: "plus", label: "添加服务器") {
        editing = nil
        showForm = true
      }
    }
    .padding(.horizontal, 4)
    .frame(height: 64)
  }

  private var emptyState: some View {
    VStack(spacing: 12) {
      Image(systemName: "externaldrive.badge.icloud")
        .font(.system(size: 48))
        .foregroundColor(AppColor.onSurfaceVariant.opacity(0.6))
      Text("还没有 WebDAV 服务器")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
      Text("添加 NAS 地址后，可以直接浏览远程目录，把整本书的音频导入书架在线播放。")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurfaceVariant)
        .multilineTextAlignment(.center)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.horizontal, 32)
      Button {
        editing = nil
        showForm = true
      } label: {
        Text("添加服务器")
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.onPrimary)
          .padding(.horizontal, 24)
          .frame(height: 44)
          .background(AppColor.primary, in: Capsule())
      }
      .buttonStyle(.plain)
      .padding(.top, 8)
    }
    .frame(maxWidth: .infinity)
    .padding(.top, 80)
  }

  private func row(_ server: WebDavServer) -> some View {
    HStack(spacing: 0) {
      Image(systemName: "externaldrive.badge.icloud")
        .font(.system(size: 24))
        .foregroundColor(AppColor.onSurfaceVariant)
        .frame(width: 24, height: 24)
        .padding(.trailing, 16)

      VStack(alignment: .leading, spacing: 0) {
        Text(server.name)
          .font(AppFont.bodyLarge())
          .foregroundColor(AppColor.onSurface)
          .lineLimit(1)
        Text(server.normalizedBase)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .lineLimit(1)
      }

      Spacer(minLength: 8)

      IconButton48(systemName: "slider.horizontal.3", label: "编辑") {
        editing = server
        showForm = true
      }
      IconButton48(systemName: "trash", label: "删除") {
        deletingId = server.id
      }
    }
    .padding(.leading, 16)
    .padding(.vertical, 6)
    .frame(minHeight: 56)
    .contentShape(Rectangle())
    .onTapGesture { browsing = server }
  }
}

// MARK: - 添加 / 编辑表单

struct WebDavServerForm: View {
  @EnvironmentObject private var webdav: WebDavStore
  @Environment(\.dismiss) private var dismiss

  var existing: WebDavServer?

  init(existing: WebDavServer?) {
    self.existing = existing
  }

  @State private var name = ""
  @State private var baseUrl = ""
  @State private var username = ""
  @State private var password = ""
  @State private var trustAll = false
  @State private var testing = false
  @State private var message: String?
  @State private var loaded = false

  private var isEditing: Bool { existing != nil }

  private var canSave: Bool {
    !baseUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  var body: some View {
    NavigationView {
      ScrollView {
        VStack(alignment: .leading, spacing: 16) {
          field("名称", text: $name, placeholder: "我的 NAS")
          field("服务器地址", text: $baseUrl, placeholder: "https://nas.example.com:5006/dav", url: true)
          field("用户名", text: $username, placeholder: "可选")
          field("密码", text: $password, placeholder: isEditing ? "留空表示不修改" : "可选", secure: true)

          HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
              Text("信任所有证书")
                .font(AppFont.bodyLarge())
                .foregroundColor(AppColor.onSurface)
              Text("用于自签名证书，仅在可信网络中开启")
                .font(AppFont.bodySmall())
                .foregroundColor(AppColor.onSurfaceVariant)
            }
            Spacer(minLength: 8)
            M3Switch(isOn: $trustAll)
          }
          .padding(.vertical, 4)

          if let message {
            Text(message)
              .font(AppFont.bodyMedium())
              .foregroundColor(AppColor.onSurface)
              .padding(12)
              .frame(maxWidth: .infinity, alignment: .leading)
              .background(
                AppColor.surfaceContainerHigh,
                in: RoundedRectangle(cornerRadius: 12, style: .continuous)
              )
          }

          HStack(spacing: 12) {
            Button {
              runTest()
            } label: {
              HStack(spacing: 8) {
                if testing { ProgressView().scaleEffect(0.8) }
                Text(testing ? "测试中…" : "测试连接")
                  .font(AppFont.labelLarge())
                  .foregroundColor(AppColor.primary)
              }
              .padding(.horizontal, 20)
              .frame(height: 44)
              .overlay(
                Capsule().stroke(AppColor.outlineVariant, lineWidth: 1)
              )
            }
            .buttonStyle(.plain)
            .disabled(testing || !canSave)

            Spacer()
          }
          .padding(.top, 4)

          Spacer().frame(height: 24)
        }
        .padding(20)
      }
      .background(AppColor.background.ignoresSafeArea())
      .navigationTitle(isEditing ? "编辑服务器" : "添加服务器")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("取消") { dismiss() }
        }
        ToolbarItem(placement: .confirmationAction) {
          Button("保存") { saveAction() }
            .disabled(!canSave)
        }
      }
    }
    .onAppear {
      guard !loaded else { return }
      loaded = true
      if let existing {
        name = existing.name
        baseUrl = existing.baseUrl
        username = existing.username
        trustAll = existing.trustAllCertificates
      }
    }
  }

  private func field(
    _ title: String,
    text: Binding<String>,
    placeholder: String,
    url: Bool = false,
    secure: Bool = false
  ) -> some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(title)
        .font(AppFont.bodySmall())
        .foregroundColor(AppColor.onSurfaceVariant)
      Group {
        if secure {
          SecureField(placeholder, text: text)
        } else {
          TextField(placeholder, text: text)
        }
      }
      .font(AppFont.bodyLarge())
      .foregroundColor(AppColor.onSurface)
      .textInputAutocapitalization(.never)
      .autocorrectionDisabled()
      .keyboardType(url ? .URL : .default)
      .padding(.horizontal, 12)
      .frame(height: 48)
      .background(
        AppColor.surfaceContainerHigh,
        in: RoundedRectangle(cornerRadius: 8, style: .continuous)
      )
    }
  }

  private func draft() -> WebDavServer {
    var server = existing ?? WebDavServer(name: "", baseUrl: "", username: "")
    server.name = name.trimmingCharacters(in: .whitespacesAndNewlines)
    server.baseUrl = baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
    server.username = username.trimmingCharacters(in: .whitespacesAndNewlines)
    server.trustAllCertificates = trustAll
    return server
  }

  private func effectivePassword() -> String {
    if !password.isEmpty { return password }
    if let existing { return webdav.password(for: existing.id) }
    return ""
  }

  private func runTest() {
    let server = draft()
    let pwd = effectivePassword()
    testing = true
    message = nil
    Task {
      var text: String
      do {
        text = try await WebDavClient.test(server: server, password: pwd)
      } catch {
        text = error.localizedDescription
      }
      let result = text
      await MainActor.run {
        message = result
        testing = false
      }
    }
  }

  private func saveAction() {
    let server = draft()
    _ = webdav.save(server, password: password)
    dismiss()
  }
}
