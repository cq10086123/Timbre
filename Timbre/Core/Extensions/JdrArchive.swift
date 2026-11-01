import Foundation

/// manifest.json 里的单个源
struct ExtensionSourceMeta: Codable, Identifiable, Hashable {
  var id: String
  var name: String
  var script: String
  var capabilities: [String]

  init(id: String, name: String, script: String, capabilities: [String]) {
    self.id = id
    self.name = name
    self.script = script
    self.capabilities = capabilities
  }

  private enum CodingKeys: String, CodingKey { case id, name, script, capabilities }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    name = try c.decodeIfPresent(String.self, forKey: .name) ?? id
    script = try c.decodeIfPresent(String.self, forKey: .script) ?? ""
    capabilities =
      try c.decodeIfPresent([String].self, forKey: .capabilities) ?? ["search", "chapters", "audio"]
  }
}

/// manifest.json
struct ExtensionManifest: Codable, Identifiable, Hashable {
  var id: String
  var name: String
  var version: String
  var author: String
  var description: String
  var homepage: String
  var allowInsecure: Bool
  var sources: [ExtensionSourceMeta]

  private enum CodingKeys: String, CodingKey {
    case id, name, version, author, description, homepage, allowInsecure, sources
  }

  init(from decoder: Decoder) throws {
    let c = try decoder.container(keyedBy: CodingKeys.self)
    id = try c.decode(String.self, forKey: .id)
    name = try c.decodeIfPresent(String.self, forKey: .name) ?? id
    version = try c.decodeIfPresent(String.self, forKey: .version) ?? "1.0.0"
    author = try c.decodeIfPresent(String.self, forKey: .author) ?? ""
    description = try c.decodeIfPresent(String.self, forKey: .description) ?? ""
    homepage = try c.decodeIfPresent(String.self, forKey: .homepage) ?? ""
    allowInsecure = try c.decodeIfPresent(Bool.self, forKey: .allowInsecure) ?? false
    sources = try c.decodeIfPresent([ExtensionSourceMeta].self, forKey: .sources) ?? []
  }

  func encode(to encoder: Encoder) throws {
    var c = encoder.container(keyedBy: CodingKeys.self)
    try c.encode(id, forKey: .id)
    try c.encode(name, forKey: .name)
    try c.encode(version, forKey: .version)
    try c.encode(author, forKey: .author)
    try c.encode(description, forKey: .description)
    try c.encode(homepage, forKey: .homepage)
    try c.encode(allowInsecure, forKey: .allowInsecure)
    try c.encode(sources, forKey: .sources)
  }
}

struct JdrPackage {
  var manifest: ExtensionManifest
  /// key 是包内路径（与 manifest 里 source.script 一致）
  var scripts: [String: String]

  func script(for source: ExtensionSourceMeta) -> String? {
    scripts[source.script]
  }
}

enum JdrError: LocalizedError {
  case missingManifest
  case badManifest(String)
  case badSource(String)
  case missingScript(String)
  case emptyScript(String)

  var errorDescription: String? {
    switch self {
    case .missingManifest: return "包内缺少 manifest.json"
    case .badManifest(let reason): return "manifest.json 无效：\(reason)"
    case .badSource(let reason): return "源声明无效：\(reason)"
    case .missingScript(let name): return "manifest 引用的脚本不存在：\(name)"
    case .emptyScript(let name): return "脚本文件为空：\(name)"
    }
  }
}

enum JdrArchive {
  static let maxScriptBytes = 5 * 1024 * 1024
  private static let knownCapabilities: Set<String> = ["search", "chapters", "audio"]

  // 与安卓版 JdrArchive 的正则保持一致
  private static let manifestIdPattern = "^[a-z0-9][a-z0-9._-]{1,63}$"
  private static let sourceIdPattern = "^[a-z0-9][a-z0-9_-]{0,63}$"

  static func parse(data: Data) throws -> JdrPackage {
    let entries = try ZipArchive.entries(in: data)
    guard
      let manifestEntry = entries.first(where: {
        $0.name == "manifest.json" || $0.name.hasSuffix("/manifest.json")
      })
    else {
      throw JdrError.missingManifest
    }
    let manifestData = try ZipArchive.extract(manifestEntry, from: data)
    let manifest: ExtensionManifest
    do {
      manifest = try JSONDecoder().decode(ExtensionManifest.self, from: manifestData)
    } catch {
      throw JdrError.badManifest(error.localizedDescription)
    }

    guard matches(manifest.id, manifestIdPattern) else {
      throw JdrError.badManifest("id 非法（需小写字母/数字/._-，2-64 位）：\(manifest.id)")
    }
    guard !manifest.name.trimmingCharacters(in: .whitespaces).isEmpty else {
      throw JdrError.badManifest("name 不能为空")
    }
    guard !manifest.version.trimmingCharacters(in: .whitespaces).isEmpty else {
      throw JdrError.badManifest("version 不能为空")
    }
    guard !manifest.sources.isEmpty else {
      throw JdrError.badManifest("sources 不能为空")
    }

    var seenIds = Set<String>()
    for source in manifest.sources {
      guard matches(source.id, sourceIdPattern) else {
        throw JdrError.badSource("id 非法：\(source.id)")
      }
      guard seenIds.insert(source.id).inserted else {
        throw JdrError.badSource("源 id 重复：\(source.id)")
      }
      guard !source.name.trimmingCharacters(in: .whitespaces).isEmpty else {
        throw JdrError.badSource("源 \(source.id) 的 name 不能为空")
      }
      guard !source.script.trimmingCharacters(in: .whitespaces).isEmpty else {
        throw JdrError.badSource("源 \(source.id) 的 script 不能为空")
      }
      let unknown = Set(source.capabilities).subtracting(knownCapabilities)
      guard unknown.isEmpty else {
        throw JdrError.badSource("源 \(source.id) 含未知能力：\(unknown.sorted().joined(separator: ","))")
      }
    }

    let prefix = manifestEntry.name.hasSuffix("/manifest.json")
      ? String(manifestEntry.name.dropLast("manifest.json".count))
      : ""

    var scripts: [String: String] = [:]
    for source in manifest.sources {
      let candidates = [prefix + source.script, source.script]
      guard
        let entry = candidates.compactMap({ path in entries.first { $0.name == path } }).first
      else {
        throw JdrError.missingScript(source.script)
      }
      guard entry.uncompressedSize <= maxScriptBytes else {
        throw JdrError.badSource("脚本 \(source.script) 超过 5MB 上限")
      }
      let raw = try ZipArchive.extract(entry, from: data)
      guard let text = decodeText(raw) else {
        throw JdrError.missingScript(source.script)
      }
      guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
        throw JdrError.emptyScript(source.script)
      }
      scripts[source.script] = text
    }
    return JdrPackage(manifest: manifest, scripts: scripts)
  }

  private static func matches(_ text: String, _ pattern: String) -> Bool {
    text.range(of: pattern, options: .regularExpression) != nil
  }

  /// 优先 UTF-8，其次带 BOM 的 UTF-8 / GB18030（部分源作者在 Windows 记事本里存盘）
  private static func decodeText(_ data: Data) -> String? {
    if let text = String(data: data, encoding: .utf8) {
      return text.replacingOccurrences(of: "\u{FEFF}", with: "")
    }
    // 0x8000_0632 = CFStringConvertEncodingToNSStringEncoding(kCFStringEncodingGB_18030_2000)
    // 直接写常量，省掉 CoreFoundation 的桥接调用
    let gb18030 = String.Encoding(rawValue: 0x8000_0632)
    if let text = String(data: data, encoding: gb18030) { return text }
    return nil
  }
}
