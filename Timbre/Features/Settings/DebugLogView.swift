import SwiftUI

/// 调试日志页：看 + 导出 txt + 清空
struct DebugLogView: View {
  @Environment(\.dismiss) private var dismiss

  @State private var text = ""
  @State private var exportURL: URL?

  var body: some View {
    VStack(spacing: 0) {
      topBar

      ScrollView {
        Text(text)
          .font(.system(size: 11, design: .monospaced))
          .foregroundColor(AppColor.onSurface)
          .textSelection(.enabled)
          .frame(maxWidth: .infinity, alignment: .leading)
          .padding(12)
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .onAppear(perform: reload)
  }

  private var topBar: some View {
    HStack(spacing: 0) {
      IconButton48(systemName: "xmark", label: "关闭") { dismiss() }
      Text("调试日志")
        .font(AppFont.titleLarge())
        .foregroundColor(AppColor.onSurface)
      Spacer(minLength: 0)
      if let exportURL {
        ShareLink(item: exportURL) {
          Image(systemName: "square.and.arrow.up")
            .font(.system(size: 22))
            .foregroundColor(AppColor.onSurfaceVariant)
            .frame(width: 48, height: 48)
        }
      }
      IconButton48(systemName: "trash", label: "清空") {
        DebugLog.shared.clear()
        reload()
      }
    }
    .padding(.horizontal, 4)
    .frame(height: 64)
  }

  private func reload() {
    let content = DebugLog.shared.text()
    text = content
    exportURL = Self.writeTempFile(content)
  }

  private static func writeTempFile(_ content: String) -> URL? {
    let formatter = DateFormatter()
    formatter.locale = Locale(identifier: "en_US_POSIX")
    formatter.dateFormat = "MMdd-HHmmss"
    let name = "Timbre-log-\(formatter.string(from: Date())).txt"
    let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
    do {
      try content.write(to: url, atomically: true, encoding: .utf8)
      return url
    } catch {
      return nil
    }
  }
}
