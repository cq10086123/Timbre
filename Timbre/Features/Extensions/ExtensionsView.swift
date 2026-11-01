import SwiftUI

struct ExtensionsView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var extensions: ExtensionStore
  @Environment(\.dismiss) private var dismiss

  @State private var showPicker = false
  @State private var showLinkAlert = false
  @State private var linkText = ""

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()
      ScrollView {
        VStack(alignment: .leading, spacing: 16) {
          hint

          if extensions.extensions.isEmpty {
            emptyCard
          } else {
            VStack(spacing: 10) {
              ForEach(extensions.extensions) { ext in
                extensionCard(ext)
              }
            }
          }

          VStack(spacing: 10) {
            Button {
              showPicker = true
            } label: {
              Label("从文件导入 .jdr", systemImage: "doc.badge.plus")
                .font(.subheadline.weight(.medium))
                .foregroundColor(AppColor.background)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(AppColor.accent, in: Capsule())
            }
            Button {
              linkText = ""
              showLinkAlert = true
            } label: {
              Label("从链接导入", systemImage: "link")
                .font(.subheadline.weight(.medium))
                .foregroundColor(AppColor.accent)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 13)
                .background(AppColor.accentSoft, in: Capsule())
            }
          }
          .padding(.top, 4)
        }
        .padding(16)
      }
    }
    .navigationTitle("接口源")
    .navigationBarTitleDisplayMode(.inline)
    .sheet(isPresented: $showPicker) {
      DocumentPicker(contentTypes: [.data], allowsMultiple: false) { urls in
        showPicker = false
        app.installExtension(urls: urls)
      }
    }
    .alert("从链接导入", isPresented: $showLinkAlert) {
      TextField("https://.../xxx.jdr", text: $linkText)
        .textInputAutocapitalization(.never)
        .autocorrectionDisabled()
      Button("导入") {
        app.installExtension(fromLink: linkText.trimmingCharacters(in: .whitespacesAndNewlines))
      }
      Button("取消", role: .cancel) {}
    } message: {
      Text("支持 .jdr 直链，例如 jsDelivr 上的源合集。")
    }
  }

  private var hint: some View {
    Text("接口源（.jdr）是一个压缩包，里面包含源脚本。搜索、章节、播放直链全部在手机本地由 JavaScriptCore 沙箱执行，请求从你自己的 IP 直连源站。")
      .font(.footnote)
      .foregroundColor(AppColor.textSecondary)
      .padding(14)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
  }

  private var emptyCard: some View {
    VStack(spacing: 10) {
      Image(systemName: "shippingbox")
        .font(.system(size: 34, weight: .light))
        .foregroundColor(AppColor.textSecondary)
      Text("还没有安装任何接口源")
        .font(.subheadline)
        .foregroundColor(AppColor.textPrimary)
      Text("导入现成的 .jdr 包，或在安卓版的 samples 目录里找示例")
        .font(.caption)
        .foregroundColor(AppColor.textSecondary)
        .multilineTextAlignment(.center)
    }
    .frame(maxWidth: .infinity)
    .padding(.vertical, 30)
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
  }

  private func extensionCard(_ ext: InstalledExtension) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack(alignment: .top) {
        VStack(alignment: .leading, spacing: 3) {
          Text(ext.name)
            .font(.system(size: 15, weight: .semibold))
            .foregroundColor(AppColor.textPrimary)
          Text("v\(ext.version)\(ext.author.isEmpty ? "" : " · \(ext.author)")")
            .font(.system(size: 11))
            .foregroundColor(AppColor.textSecondary)
        }
        Spacer()
        Toggle(
          "",
          isOn: Binding(
            get: { ext.enabled },
            set: { extensions.setEnabled(ext, enabled: $0) }
          )
        )
        .labelsHidden()
        .tint(AppColor.accent)
      }

      if !ext.summary.isEmpty {
        Text(ext.summary)
          .font(.system(size: 12))
          .foregroundColor(AppColor.textSecondary)
          .lineLimit(3)
      }

      HStack(spacing: 6) {
        ForEach(ext.sources) { source in
          Text(source.name)
            .font(.system(size: 10, weight: .medium))
            .foregroundColor(AppColor.accent)
            .padding(.horizontal, 7)
            .padding(.vertical, 3)
            .background(AppColor.accentSoft, in: Capsule())
        }
      }

      HStack {
        Text("来源 id：\(ext.id)")
          .font(.system(size: 10))
          .foregroundColor(AppColor.textSecondary)
        Spacer()
        Button {
          extensions.remove(ext)
          app.showToast("已删除 \(ext.name)")
        } label: {
          Text("删除")
            .font(.system(size: 11, weight: .medium))
            .foregroundColor(AppColor.danger)
        }
      }
    }
    .padding(14)
    .background(AppColor.surface, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
  }
}
