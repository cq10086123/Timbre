import Combine
import Foundation

/// 接口源导入失败的原因，直接作为提示文案展示
enum ExtensionInstallError: LocalizedError {
  case readFailed(String, String)
  case emptyFile
  case badLink
  case downloadFailed(String)
  case invalidPackage(String)

  var errorDescription: String? {
    switch self {
    case .readFailed(let name, let reason):
      return "读不到文件「\(name)」：\(reason)"
    case .emptyFile:
      return "文件是空的（0 字节）"
    case .badLink:
      return "链接必须以 http:// 或 https:// 开头"
    case .downloadFailed(let reason):
      return "下载失败：\(reason)"
    case .invalidPackage(let reason):
      return "不是有效的 .jdr 包：\(reason)"
    }
  }
}

/// 数组逐条容错解码：单条坏数据只丢这一条，不连累整个数组
struct FailableDecodable<T: Decodable>: Decodable {
  let value: T?

  init(from decoder: Decoder) throws {
    value = try? T(from: decoder)
  }
}

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
  /// 被单独关掉的源 id（安卓版是每个源一个开关，不是整包开关）
  var disabledSources: [String]
  var installedAt: Date
  /// 相对 Documents 的目录
  var folder: String

  init(
    id: String,
    name: String,
    version: String,
    author: String,
    summary: String,
    homepage: String,
    allowInsecure: Bool,
    sources: [ExtensionSourceMeta],
    disabledSources: [String] = [],
    installedAt: Date,
    folder: String
  ) {
    self.id = id
    self.name = name
    self.version = version
    self.author = author
    self.summary = summary
    self.homepage = homepage
    self.allowInsecure = allowInsecure
    self.sources = sources
    self.disabledSources = disabledSources
    self.installedAt = installedAt
    self.folder = folder
  }

  // 旧版本 extensions.json 没有 disabledSources / 部分字段，全部按缺省值兜底，
  // 否则一次解码失败就会让整份列表变空（这正是上一版「装好了也看不到」的原因之一）
  private enum CodingKeys: String, CodingKey {
    case id, name, version, author, summary, homepage, allowInsecure, sources
    case disabledSources, installedAt, folder
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decodeIfPresent(String.self, forKey: .id) ?? ""
    name = try c.decodeIfPresent(String.self, forKey: .name) ?? id
    version = try c.decodeIfPresent(String.self, forKey: .version) ?? ""
    author = try c.decodeIfPresent(String.self, forKey: .author) ?? ""
    summary = try c.decodeIfPresent(String.self, forKey: .summary) ?? ""
    homepage = try c.decodeIfPresent(String.self, forKey: .homepage) ?? ""
    allowInsecure = try c.decodeIfPresent(Bool.self, forKey: .allowInsecure) ?? false
    sources = try c.decodeIfPresent([ExtensionSourceMeta].self, forKey: .sources) ?? []
    disabledSources = try c.decodeIfPresent([String].self, forKey: .disabledSources) ?? []
    installedAt = try c.decodeIfPresent(Date.self, forKey: .installedAt) ?? Date()
    folder = try c.decodeIfPresent(String.self, forKey: .folder) ?? "Extensions/\(id)"
  }

  func isSourceEnabled(_ sourceId: String) -> Bool {
    !disabledSources.contains(sourceId)
  }
}

/// 扁平化后的单个源，用于搜索时选择
struct InstalledSource: Identifiable, Hashable {
  var extensionId: String
  var extensionName: String
  var allowInsecure: Bool
  var meta: ExtensionSourceMeta

  /// 与安卓路由一致：`jdr:<sourceId>`
  var id: String { "jdr:\(meta.id)" }
  var routeId: String { "jdr:\(meta.id)" }
  var displayName: String { meta.name }
}

final class ExtensionStore: ObservableObject {
  @Published var extensions: [InstalledExtension] = []

  init() {
    load()
  }

  // MARK: - 持久化

  private func load() {
    guard let data = try? Data(contentsOf: AppPaths.extensionsFile) else { return }
    // 逐条容错：某一条记录坏了只丢这一条，不让整份列表变空
    let decoded =
      (try? JSONCoding.decoder().decode([FailableDecodable<InstalledExtension>].self, from: data))
      ?? []
    extensions = decoded.compactMap(\.value).filter { !$0.id.isEmpty }.map { ext in
      var fixed = ext
      if fixed.folder.isEmpty { fixed.folder = "Extensions/\(fixed.id)" }
      return fixed
    }
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(extensions) else { return }
    AppPaths.ensure(AppPaths.extensionsDir)
    try? data.write(to: AppPaths.extensionsFile, options: .atomic)
  }

  // MARK: - 查询

  var enabledSources: [InstalledSource] {
    extensions.flatMap { ext in
      ext.sources.filter { !$0.id.isEmpty && ext.isSourceEnabled($0.id) }.map {
        InstalledSource(
          extensionId: ext.id,
          extensionName: ext.name,
          allowInsecure: ext.allowInsecure,
          meta: $0
        )
      }
    }
  }

  var totalSourceCount: Int {
    extensions.reduce(0) { $0 + $1.sources.count }
  }

  func installedExtension(withId id: String) -> InstalledExtension? {
    extensions.first { $0.id == id }
  }

  func source(for installed: InstalledSource) -> InstalledExtension? {
    installedExtension(withId: installed.extensionId)
  }

  /// 读取源码文本（脚本可能带子目录，例如 `src/a.js`）
  func scriptText(for installed: InstalledSource) -> String? {
    guard let ext = installedExtension(withId: installed.extensionId) else { return nil }
    let url = AppPaths.url(for: ext.folder)
      .appendingPathComponent(installed.meta.script)
    guard let data = try? Data(contentsOf: url) else { return nil }
    return String(data: data, encoding: .utf8)
  }

  // MARK: - 安装 / 卸载

  /// 写入磁盘并登记。纯主线程调用，重量级解析在外部先做完。
  @discardableResult
  func install(package: JdrPackage) -> InstalledExtension {
    let manifest = package.manifest
    let folder = "Extensions/\(manifest.id)"
    let dir = AppPaths.ensure(AppPaths.url(for: folder))

    // 清空旧文件后重写，避免旧版本残留的多余脚本
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
      disabledSources: [],
      installedAt: Date(),
      folder: folder
    )

    if let index = extensions.firstIndex(where: { $0.id == record.id }) {
      var updated = record
      // 升级时保留用户已有的开关状态
      updated.disabledSources = extensions[index].disabledSources.filter { id in
        manifest.sources.contains { $0.id == id }
      }
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

  func setSourceEnabled(_ ext: InstalledExtension, sourceId: String, enabled: Bool) {
    guard let index = extensions.firstIndex(where: { $0.id == ext.id }) else { return }
    var disabled = Set(extensions[index].disabledSources)
    if enabled {
      disabled.remove(sourceId)
    } else {
      disabled.insert(sourceId)
    }
    extensions[index].disabledSources = Array(disabled).sorted()
    persist()
  }
}
