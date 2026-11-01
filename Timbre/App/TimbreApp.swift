import SwiftUI

@main
struct TimbreApp: App {
  @StateObject private var app = AppState()

  var body: some Scene {
    WindowGroup {
      RootView()
        .environmentObject(app)
        .environmentObject(app.library)
        .environmentObject(app.settings)
        .environmentObject(app.extensions)
        .environmentObject(app.player)
        .tint(AppColor.accent)
        .preferredColorScheme(app.themeColorScheme)
    }
  }
}

struct RootView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var library: LibraryStore
  @EnvironmentObject private var settings: SettingsStore
  @EnvironmentObject private var extensions: ExtensionStore
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    ShelfView()
      .fullScreenCover(isPresented: $app.showPlayer) {
        PlayerView()
          .environmentObject(app)
          .environmentObject(library)
          .environmentObject(settings)
          .environmentObject(extensions)
          .environmentObject(player)
      }
      .sheet(isPresented: $app.showImporter) {
        DocumentPicker { urls in
          app.showImporter = false
          app.handleImport(urls: urls)
        }
        .ignoresSafeArea()
      }
      .sheet(isPresented: $app.showSettings) {
        SettingsView()
          .environmentObject(app)
          .environmentObject(library)
          .environmentObject(settings)
          .environmentObject(extensions)
          .environmentObject(player)
      }
      .overlay(alignment: .bottom) {
        if let toast = app.toast {
          Text(toast)
            .font(.footnote.weight(.medium))
            .foregroundColor(AppColor.textPrimary)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(AppColor.surfaceHigh, in: Capsule())
            .overlay(Capsule().stroke(AppColor.divider, lineWidth: 0.5))
            .padding(.bottom, 28)
            .transition(.opacity.combined(with: .move(edge: .bottom)))
        }
      }
      .overlay {
        if app.isImporting {
          HStack(spacing: 10) {
            ProgressView()
              .tint(AppColor.accent)
            Text("正在导入…")
              .font(.footnote.weight(.medium))
              .foregroundColor(AppColor.textPrimary)
          }
          .padding(.horizontal, 18)
          .padding(.vertical, 14)
          .background(AppColor.surface, in: Capsule())
          .overlay(Capsule().stroke(AppColor.divider, lineWidth: 0.5))
          .shadow(color: Color.black.opacity(0.2), radius: 16, y: 6)
        }
      }
      .alert(
        "出错了",
        isPresented: Binding(
          get: { app.errorMessage != nil },
          set: { if !$0 { app.errorMessage = nil } }
        )
      ) {
        Button("知道了", role: .cancel) { app.errorMessage = nil }
      } message: {
        Text(app.errorMessage ?? "")
      }
      .onChange(of: scenePhase) { phase in
        if phase != .active { player.flush() }
      }
  }
}
