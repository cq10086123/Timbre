import Combine
import Foundation

final class SettingsStore: ObservableObject {
  @Published var settings: AppSettings {
    didSet { persist() }
  }

  init() {
    if let data = try? Data(contentsOf: AppPaths.settingsFile),
      let decoded = try? JSONCoding.decoder().decode(AppSettings.self, from: data)
    {
      settings = decoded
    } else {
      settings = AppSettings()
    }
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(settings) else { return }
    try? data.write(to: AppPaths.settingsFile, options: .atomic)
  }
}
