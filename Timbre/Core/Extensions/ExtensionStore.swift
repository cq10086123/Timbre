import Combine
import Foundation

/// 已安装的 .jdr 包
struct InstalledExtension: Codable, Identifiable, Hashable {
  var id: String
  var name: String
  var version: String
  var author: String
  var summary: String
  var homepage: String
  var allowInsecure: Bool
  var sources: [ExtensionSourceMeta]
  var enabled: Bool
  var installedAt: Date
  /// 相对 Documents 的目录
  var folder: String
}

/// 扁平化后的单个源，用于搜索时选择
struct InstalledSource: Identifiable, Hashable {
  var extensionId: String
  var extensionName: String
  var allowInsecure: Bool
  var meta: ExtensionSourceMeta

  var id: String { "\(extensionId)/\(meta.id)" }
  var displayName: String { meta.name }
}

final class ExtensionStore: ObservableObject {
  @Published var extensions: [InstalledExtension] = []

  init() {
    load()
  }

  private func load() {
    guard let data = try? Data(contentsOf: AppPaths.extensionsFile),
      let decoded = try? JSONCoding.decoder().decode([InstalledExtension].self, from: data)
    else { return }
    extensions = decoded
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(extensions) else { return }
    try? data.write(to: AppPaths.extensionsFile, options: .atomic)
  }

  var enabledSources: [InstalledSource] {
    extensions.filter { $0.enabled }.flatMap { ext in
      ext.sources.map {
        InstalledSource(
          extensionId: ext.id,
          extensionName: ext.name,
          allowInsecure: ext.allowInsecure,
          meta: $0
        )
      }
    }
  }

  func installedExtension(withId id: String) -> InstalledExtension? {
    extensions.first { $0.id == id }
  }

  func source(for installed: InstalledSource) -> InstalledExtension? {
    installedExtension(withId: installed.extensionId)
  }

  /// 读取源码文本
  func scriptText(for installed: InstalledSource) -> String? {
    guard let ext = installedExtension(withId: installed.extensionId) else { return nil }
    let url = AppPaths.url(for: ext.folder).appendingPathComponent(installed.meta.script)
    guard let data = try? Data(contentsOf: url) else { return nil }
    return String(data: data, encoding: .utf8)
  }

  /// 安装 / 更新一个 .jdr 包
  func install(data: Data) throws -> InstalledExtension {
    let package = try JdrArchive.parse(data: data)
    let manifest = package.manifest
    let folder = "Extensions/\(manifest.id)"
    let dir = AppPaths.ensure(AppPaths.url(for: folder))

    // 清理旧文件后重写
    if let existing = try? FileManager.default.contentsOfDirectory(atPath: dir.path) {
      for name in existing {
        try? FileManager.default.removeItem(at: dir.appendingPathComponent(name))
      }
    }

    for (name, script) in package.scripts {
      let destination = dir.appendingPathComponent(name)
      try? FileManager.default.createDirectory(
        at: destination.deletingLastPathComponent(),
        withIntermediateDirectories: true
      )
      try? script.write(to: destination, atomically: true, encoding: .utf8)
    }
    if let manifestData = try? JSONCoding.encoder().encode(manifest) {
      try? manifestData.write(to: dir.appendingPathComponent("manifest.json"), options: .atomic)
    }

    let record = InstalledExtension(
      id: manifest.id,
      name: manifest.name,
      version: manifest.version,
      author: manifest.author,
      summary: manifest.description,
      homepage: manifest.homepage,
      allowInsecure: manifest.allowInsecure,
      sources: manifest.sources,
      enabled: true,
      installedAt: Date(),
      folder: folder
    )

    if let index = extensions.firstIndex(where: { $0.id == record.id }) {
      var updated = record
      updated.enabled = extensions[index].enabled
      extensions[index] = updated
    } else {
      extensions.append(record)
    }
    persist()
    return record
  }

  func remove(_ ext: InstalledExtension) {
    extensions.removeAll { $0.id == ext.id }
    try? FileManager.default.removeItem(at: AppPaths.url(for: ext.folder))
    persist()
  }

  func setEnabled(_ ext: InstalledExtension, enabled: Bool) {
    guard let index = extensions.firstIndex(where: { $0.id == ext.id }) else { return }
    extensions[index].enabled = enabled
    persist()
  }
}
