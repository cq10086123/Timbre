import SwiftUI

/// WebDAV 书籍缓存配置 —— 对齐安卓 `WebDavCacheScreen`：
/// 已缓存多少、清除缓存、上限几档、预取策略、是否允许移动网络预取。
struct WebDavCacheView: View {
  @EnvironmentObject private var app: AppState
  @Environment(\.dismiss) private var dismiss

  @State private var cachedBytes: Int64 = 0
  @State private var showClearConfirm = false
  @State private var loaded = false

  private static let mb512: Int64 = 512 * 1024 * 1024
  private static let gb1: Int64 = 1024 * 1024 * 1024
  private static let gb2: Int64 = 2 * 1024 * 1024 * 1024
  private static let gb4: Int64 = 4 * 1024 * 1024 * 1024

  private var settings: AppSettings { app.settings.settings }

  var body: some View {
    VStack(spacing: 0) {
      WebDavTopBar(title: "书籍缓存配置", leadingIcon: "chevron.left") { dismiss() }

      ScrollView {
        VStack(alignment: .leading, spacing: 0) {
          Text("已缓存：\(Format.bytes(cachedBytes)) / \(Format.bytes(settings.webdavCacheMaxBytes))")
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)

          Button {
            showClearConfirm = true
          } label: {
            Text("清除缓存")
              .font(AppFont.bodyLarge())
              .foregroundColor(AppColor.onSurface)
              .frame(maxWidth: .infinity, alignment: .leading)
              .padding(.horizontal, 16)
              .padding(.vertical, 14)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)

          groupTitle("缓存上限")
          HStack(spacing: 4) {
            chip("512 MB", selected: settings.webdavCacheMaxBytes == Self.mb512) {
              set(maxBytes: Self.mb512)
            }
            chip("1 GB", selected: settings.webdavCacheMaxBytes == Self.gb1) {
              set(maxBytes: Self.gb1)
            }
            chip("2 GB", selected: settings.webdavCacheMaxBytes == Self.gb2) {
              set(maxBytes: Self.gb2)
            }
            chip("4 GB", selected: settings.webdavCacheMaxBytes == Self.gb4) {
              set(maxBytes: Self.gb4)
            }
          }
          .padding(.horizontal, 16)

          HStack(spacing: 4) {
            chip("关闭", selected: settings.webdavCacheMaxBytes == 0) {
              set(maxBytes: 0)
            }
          }
          .padding(.horizontal, 16)
          .padding(.top, 4)

          groupTitle("预取未听章节")
          HStack(spacing: 4) {
            chip("整本填满", selected: settings.webdavPrefetchMode == .fillBook) {
              set(mode: .fillBook)
            }
            chip("20 章", selected: settings.webdavPrefetchMode == .chapters20) {
              set(mode: .chapters20)
            }
            chip("5 章", selected: settings.webdavPrefetchMode == .chapters5) {
              set(mode: .chapters5)
            }
          }
          .padding(.horizontal, 16)

          HStack(spacing: 4) {
            chip("关闭", selected: settings.webdavPrefetchMode == .disabled) {
              set(mode: .disabled)
            }
          }
          .padding(.horizontal, 16)
          .padding(.top, 4)

          M3ListItem(
            systemName: nil,
            title: "允许移动网络预取",
            subtitle: "关闭时只在 Wi-Fi 下预取后面的章节"
          ) {
            M3Switch(
              isOn: Binding(
                get: { settings.webdavPrefetchOnMetered },
                set: { value in
                  app.settings.settings.webdavPrefetchOnMetered = value
                }
              )
            )
          }
          .padding(.top, 8)

          Spacer().frame(height: 32)
        }
      }
    }
    .background(AppColor.background.ignoresSafeArea())
    .alert("清除缓存", isPresented: $showClearConfirm) {
      Button("清除", role: .destructive) {
        clearCache()
      }
      Button("取消", role: .cancel) {}
    } message: {
      Text("删除所有已缓存的音频数据，之后在线播放会按需重新下载。")
    }
    .onAppear {
      guard !loaded else { return }
      loaded = true
      refreshUsage()
    }
    .onChange(of: settings.webdavCacheMaxBytes) { _ in
      refreshUsage()
    }
  }

  // MARK: - 组件

  private func groupTitle(_ text: String) -> some View {
    Text(text)
      .font(AppFont.labelLarge())
      .foregroundColor(AppColor.onSurfaceVariant)
      .padding(.horizontal, 16)
      .padding(.top, 16)
      .padding(.bottom, 8)
  }

  private func chip(_ label: String, selected: Bool, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Text(label)
        .font(AppFont.labelLarge())
        .foregroundColor(selected ? AppColor.onSecondaryContainer : AppColor.onSurfaceVariant)
        .padding(.horizontal, 12)
        .frame(height: 32)
        .background(
          Capsule().fill(selected ? AppColor.secondaryContainer : Color.clear)
        )
        .overlay(
          Capsule().stroke(selected ? Color.clear : AppColor.outlineVariant, lineWidth: 1)
        )
    }
    .buttonStyle(.plain)
  }

  // MARK: - 动作

  private func set(maxBytes: Int64) {
    app.settings.settings.webdavCacheMaxBytes = maxBytes
    if maxBytes > 0 {
      Task.detached(priority: .utility) {
        WebDavPrefetchCache.evictIfNeeded(maxBytes: maxBytes)
      }
    }
    refreshUsage()
  }

  private func set(mode: WebDavPrefetchMode) {
    app.settings.settings.webdavPrefetchMode = mode
  }

  private func clearCache() {
    Task.detached(priority: .utility) {
      WebDavPrefetchCache.clearAll()
    }
    cachedBytes = 0
  }

  private func refreshUsage() {
    Task {
      let value = await Task.detached(priority: .utility) {
        WebDavPrefetchCache.totalBytes()
      }.value
      cachedBytes = value
    }
  }
}
