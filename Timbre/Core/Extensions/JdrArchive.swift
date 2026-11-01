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
  case scriptTooLarge

  var errorDescription: String? {
    switch self {
    case .missingManifest: return "包内缺少 manifest.json"
    case .badManifest(let reason): return "manifest.json 无效：\(reason)"
    case .badSource(let reason): return "源声明无效：\(reason)"
    case .missingScript(let name): return "包内缺少脚本文件 \(name)"
    case .scriptTooLarge: return "源脚本过大"
    }
  }
}

enum JdrArchive {
  static let maxScriptBytes = 2 * 1024 * 1024

  static func parse(data: Data) throws -> JdrPackage {
    let entries = try ZipArchive.entries(in: data)
    guard let manifestEntry = entries.first(where: {
      $0.name == "manifest.json" || $0.name.hasSuffix("/manifest.json")
    }) else {
      throw JdrError.missingManifest
    }
    let manifestData = try ZipArchive.extract(manifestEntry, from: data)
    let manifest: ExtensionManifest
    do {
      manifest = try JSONDecoder().decode(ExtensionManifest.self, from: manifestData)
    } catch {
      throw JdrError.badManifest(error.localizedDescription)
    }
    guard isValidId(manifest.id) else {
      throw JdrError.badManifest("id 只能是小写字母 / 数字 / . _ -")
    }
    guard !manifest.sources.isEmpty else {
      throw JdrError.badManifest("没有声明任何源")
    }

    let prefix = manifestEntry.name.hasSuffix("/manifest.json")
      ? String(manifestEntry.name.dropLast("manifest.json".count))
      : ""

    var scripts: [String: String] = [:]
    for source in manifest.sources {
      guard isValidId(source.id) else {
        throw JdrError.badSource("源 id 只能是小写字母 / 数字 / . _ -")
      }
      guard !source.script.isEmpty else {
        throw JdrError.badSource("源 \(source.id) 未声明 script")
      }
      let path = prefix + source.script
      guard let entry = entries.first(where: { $0.name == path || $0.name == source.script })
      else {
        throw JdrError.missingScript(source.script)
      }
      guard entry.uncompressedSize <= maxScriptBytes else { throw JdrError.scriptTooLarge }
      let raw = try ZipArchive.extract(entry, from: data)
      guard let text = String(data: raw, encoding: .utf8) else {
        throw JdrError.missingScript(source.script)
      }
      scripts[source.script] = text
    }
    return JdrPackage(manifest: manifest, scripts: scripts)
  }

  static func isValidId(_ id: String) -> Bool {
    guard !id.isEmpty, id.count <= 64 else { return false }
    let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789._-")
    return id.unicodeScalars.allSatisfy { allowed.contains($0) }
  }
}
