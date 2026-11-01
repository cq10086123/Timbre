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
        .tint(AppColor.primary)
        .preferredColorScheme(app.themeColorScheme)
    }
  }
}

struct RootView: View {
  @EnvironmentObject private var app: AppState
  @EnvironmentObject private var player: PlaybackEngine
  @Environment(\.scenePhase) private var scenePhase

  var body: some View {
    ShelfView()
      .onChange(of: scenePhase) { phase in
        if phase != .active { player.flush() }
      }
  }
}
