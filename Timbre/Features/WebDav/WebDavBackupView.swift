import SwiftUI

/// 备份与恢复 —— 对齐安卓 `WebDavBackupScreen`：
/// 备份目录 / 备份书架与进度 / 从备份恢复，三行加一段结果说明。
struct WebDavBackupView: View {
  @EnvironmentObject private var app: AppState
  @Environment(\.dismiss) private var dismiss

  private enum Status {
    case idle
    case busy
    case backupDone(books: Int, server: String, label: String?)
    case restoreDone(restored: Int, total: Int, server: String, label: String?, missingWebDav: Int)
    case text(String, Bool)  // 文案, 是否是错误
  }

  @State private var status: Status = .idle
  @State private var showFolderPicker = false

  @State private var showLabelSheet = false
  @State private var labelText = ""
  @State private var existing: [WebDavBackupItem] = []
  @State private var loadingExisting = false

  @State private var showRestoreSheet = false
  @State private var restoreLoading = false
  @State private var restoreError: String?
  @State private var restoreItems: [WebDavBackupItem] = []

  @State private var showNoFolder = false

  private var busy: Bool {
    if case .busy = status { return true }
    return false
  }

  private var location: WebDavBackupLocation? { app.backup.location() }

  private var folderSummary: String {
    guard let location else { return "未设置，请先选择目录" }
    let base = location.serverBaseUrl
    let full = location.folderUrl
    let path = full.hasPrefix(base) ? String(full.dropFirst(base.count)) : full
    return "\(location.serverName) · \(path.isEmpty ? "/" : path)"
  }

  private var restoreMissingWebDav: Int? {
    if case .restoreDone(_, _, _, _, let missing) = status, missing > 0 { return missing }
    return nil
  }

  var body: some View {
    VStack(spacing: 0) {
      WebDavTopBar(title: "备份与恢复", leadingIcon: "chevron.left") { dismiss() }

      ScrollView {
        VStack(alignment: .leading, spacing: 0) {
          M3ListItem(
            systemName: "folder",
            title: "备份目录",
            subtitle: folderSummary,
            action: { showFolderPicker = true }
          )
          M3ListItem(
            systemName: "arrow.triangle.2.circlepath",
            title: "备份书架与进度",
            subtitle: "把书架和每本书的播放位置写到备份目录",
            tint: busy ? AppColor.onSurfaceVariant : AppColor.onSurface,
            action: { if !busy { runBackup() } }
          )
          M3ListItem(
            systemName: "clock.arrow.circlepath",
            title: "从备份恢复",
            subtitle: "读取备份目录，把进度合并到本机已有的书",
            tint: busy ? AppColor.onSurfaceVariant : AppColor.onSurface,
            action: { if !busy { runRestoreList() } }
          )

          if let message = statusText {
            Text(message)
              .font(AppFont.bodySmall())
              .foregroundColor(statusIsError ? AppColor.error : AppColor.onSurfaceVariant)
              .padding(.horizontal, 16)
              .padding(.vertical, 8)
              .fixedSize(horizontal: false, vertical: true)
          }

          if let missing = restoreMissingWebDav {
            Text(
              "有 \(missing) 本 WebDAV 书籍还没导入进来，需先重新添加对应书源并等扫描完成，再恢复一次才能合上进度"
            )
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.primary)
            .padding(.horizontal, 16)
            .padding(.bottom, 8)
            .fixedSize(horizontal: false, vertical: true)
          }

          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .fullScreenCover(isPresented: $showFolderPicker) {
      WebDavBackupFolderView()
        .environmentObject(app)
        .environmentObject(app.webdav)
    }
    .sheet(isPresented: $showLabelSheet) {
      labelSheet
    }
    .sheet(isPresented: $showRestoreSheet) {
      restoreSheet
    }
    .alert("还没设置备份目录", isPresented: $showNoFolder) {
      Button("去设置目录") {
        showFolderPicker = true
      }
      Button("取消", role: .cancel) {}
    } message: {
      Text("请先在 WebDAV 服务器上选择一个目录。备份和恢复都写到那里，没选之前不会备份任何内容。")
    }
  }

  private var statusText: String? {
    switch status {
    case .idle, .busy:
      return nil
    case .backupDone(let books, let server, _):
      return "已备份 \(books) 本 → \(server)"
    case .restoreDone(let restored, let total, let server, _, _):
      return "已从 \(server) 恢复（\(restored)/\(total)）"
    case .text(let message, _):
      return message
    }
  }

  private var statusIsError: Bool {
    if case .text(_, let isError) = status { return isError }
    return false
  }

  // MARK: - 备份

  private func runBackup() {
    switch app.backup.targetStatus() {
    case .noServer:
      status = .text("请先添加一个 WebDAV 服务器", true)
      return
    case .noFolder:
      showNoFolder = true
      return
    case .ready:
      break
    }
    labelText = app.backup.lastLabel ?? ""
    existing = []
    loadingExisting = true
    showLabelSheet = true

    Task {
      let result = await app.backup.backups()
      if case .success(let items, _) = result {
        existing = items
      }
      loadingExisting = false
    }
  }

  private func confirmBackup() {
    let label = labelText.trimmingCharacters(in: .whitespacesAndNewlines)
    showLabelSheet = false
    status = .busy
    Task {
      let result = await app.backup.backup(label: label.isEmpty ? nil : label)
      switch result {
      case .success(let books, let server, let usedLabel):
        app.backup.rememberLabel(usedLabel)
        status = .backupDone(books: books, server: server, label: usedLabel)
      case .noServer:
        status = .text("请先添加一个 WebDAV 服务器", true)
      case .noFolder:
        status = .text("请先设置备份目录", true)
      case .emptyShelf:
        status = .text("书架是空的，没有可备份的内容", true)
      case .booksLoading(let count):
        status = .text("还有 \(count) 本书没导入完成，请等导入结束后再备份", true)
      case .failure(let reason):
        status = .text("失败：\(reason)", true)
      }
    }
  }

  // MARK: - 恢复

  private func runRestoreList() {
    switch app.backup.targetStatus() {
    case .noServer:
      status = .text("请先添加一个 WebDAV 服务器", true)
      return
    case .noFolder:
      showNoFolder = true
      return
    case .ready:
      break
    }
    restoreItems = []
    restoreError = nil
    restoreLoading = true
    showRestoreSheet = true

    Task {
      let result = await app.backup.backups()
      switch result {
      case .success(let items, _):
        restoreItems = items
      case .noServer:
        restoreError = "请先添加一个 WebDAV 服务器"
      case .noFolder:
        restoreError = "请先设置备份目录"
      case .failure(let reason):
        restoreError = "读取备份列表失败：\(reason)"
      }
      restoreLoading = false
    }
  }

  private func confirmRestore(label: String?) {
    showRestoreSheet = false
    status = .busy
    Task {
      let result = await app.backup.restore(label: label)
      switch result {
      case .success(let restored, let total, let server, let usedLabel, let missing):
        status = .restoreDone(
          restored: restored,
          total: total,
          server: server,
          label: usedLabel,
          missingWebDav: missing
        )
      case .noServer:
        status = .text("请先添加一个 WebDAV 服务器", true)
      case .noFolder:
        status = .text("请先设置备份目录", true)
      case .noBackupFile:
        status = .text("服务器上没有找到备份文件", true)
      case .failure(let reason):
        status = .text("失败：\(reason)", true)
      }
    }
  }

  // MARK: - 标签对话框

  private var labelSheet: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text("备份标签")
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
        .padding(.top, 20)

      TextField("例如「搞笑书籍备份」", text: $labelText)
        .font(AppFont.bodyLarge())
        .foregroundColor(AppColor.onSurface)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
        .padding(.horizontal, 12)
        .frame(height: 48)
        .background(
          AppColor.surfaceContainerHigh,
          in: RoundedRectangle(cornerRadius: 8, style: .continuous)
        )
        .padding(.top, 16)

      Text("留空即为默认备份；同名标签会覆盖之前的备份。")
        .font(AppFont.bodySmall())
        .foregroundColor(AppColor.onSurfaceVariant)
        .padding(.top, 8)
        .fixedSize(horizontal: false, vertical: true)

      if loadingExisting {
        HStack {
          Spacer()
          ProgressView().tint(AppColor.primary)
          Spacer()
        }
        .padding(.vertical, 16)
      } else if !existing.isEmpty {
        Text("这个目录里已有的备份，点击即可覆盖")
          .font(AppFont.labelMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.top, 16)
        ScrollView {
          VStack(spacing: 0) {
            ForEach(existing) { item in
              Button {
                labelText = item.label ?? ""
              } label: {
                HStack(spacing: 0) {
                  Text(item.label ?? "默认备份")
                    .font(AppFont.bodyMedium())
                    .foregroundColor(AppColor.onSurface)
                    .lineLimit(1)
                  Spacer(minLength: 8)
                  Text(Self.dateText(item.lastModified))
                    .font(AppFont.bodySmall())
                    .foregroundColor(AppColor.onSurfaceVariant)
                }
                .padding(.vertical, 10)
                .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
            }
          }
        }
        .frame(maxHeight: 160)
      }

      Spacer(minLength: 16)

      HStack(spacing: 8) {
        Spacer(minLength: 0)
        Button("取消") { showLabelSheet = false }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 12)
          .frame(height: 40)
          .buttonStyle(.plain)
        Button {
          confirmBackup()
        } label: {
          Text("备份")
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onPrimary)
            .padding(.horizontal, 24)
            .frame(height: 40)
            .background(
              RoundedRectangle(cornerRadius: 20, style: .continuous).fill(AppColor.primary)
            )
        }
        .buttonStyle(.plain)
      }
    }
    .padding(.horizontal, 24)
    .padding(.bottom, 20)
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
  }

  // MARK: - 恢复选择

  private var restoreSheet: some View {
    VStack(alignment: .leading, spacing: 0) {
      Text("选择要恢复的备份")
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
        .padding(.top, 20)
        .padding(.bottom, 12)

      if restoreLoading {
        HStack {
          Spacer()
          ProgressView().tint(AppColor.primary)
          Spacer()
        }
        .padding(.vertical, 32)
      } else if let restoreError {
        Text(restoreError)
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .fixedSize(horizontal: false, vertical: true)
        Button("重试") { runRestoreList() }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.top, 12)
      } else if restoreItems.isEmpty {
        Text("这个目录里还没有备份")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
      } else {
        ScrollView {
          VStack(spacing: 0) {
            ForEach(restoreItems) { item in
              Button {
                confirmRestore(label: item.label)
              } label: {
                VStack(alignment: .leading, spacing: 2) {
                  Text(item.label ?? "默认备份")
                    .font(AppFont.bodyLarge())
                    .foregroundColor(AppColor.onSurface)
                  Text("\(Self.dateText(item.lastModified)) · \(Format.bytes(item.size))")
                    .font(AppFont.bodySmall())
                    .foregroundColor(AppColor.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 12)
                .contentShape(Rectangle())
              }
              .buttonStyle(.plain)
            }
          }
        }
      }

      Spacer(minLength: 16)

      HStack {
        Spacer(minLength: 0)
        Button("取消") { showRestoreSheet = false }
          .font(AppFont.labelLarge())
          .foregroundColor(AppColor.primary)
          .padding(.horizontal, 12)
          .frame(height: 40)
          .buttonStyle(.plain)
      }
    }
    .padding(.horizontal, 24)
    .padding(.bottom, 20)
    .presentationDetents([.medium, .large])
    .presentationDragIndicator(.visible)
  }

  private static func dateText(_ timestamp: TimeInterval) -> String {
    guard timestamp > 0 else { return "时间未知" }
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "zh_CN")
    formatter.dateFormat = "yyyy-MM-dd HH:mm"
    return formatter.string(from: Date(timeIntervalSince1970: timestamp))
  }
}
