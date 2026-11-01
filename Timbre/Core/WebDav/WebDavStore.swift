import Combine
import Foundation

/// WebDAV 服务器配置的持久化。密码走钥匙串，json 里只有地址与用户名。
final class WebDavStore: ObservableObject {
  @Published private(set) var servers: [WebDavServer] = []

  private var fileURL: URL { AppPaths.documents.appendingPathComponent("webdav.json") }

  init() {
    load()
  }

  func server(withId id: String) -> WebDavServer? {
    servers.first { $0.id == id }
  }

  func password(for id: String) -> String {
    KeychainStore.get(account: id) ?? ""
  }

  @discardableResult
  func save(_ server: WebDavServer, password: String) -> WebDavServer {
    var next = server
    next.name = next.name.trimmingCharacters(in: .whitespacesAndNewlines)
    next.baseUrl = next.normalizedBase
    next.username = next.username.trimmingCharacters(in: .whitespacesAndNewlines)
    if next.name.isEmpty { next.name = next.baseUrl }

    if let index = servers.firstIndex(where: { $0.id == next.id }) {
      servers[index] = next
    } else {
      servers.append(next)
    }
    if !password.isEmpty {
      KeychainStore.set(password, account: next.id)
    }
    persist()
    return next
  }

  func delete(_ id: String) {
    servers.removeAll { $0.id == id }
    KeychainStore.remove(account: id)
    persist()
  }

  // MARK: - 存取

  private func load() {
    guard let data = try? Data(contentsOf: fileURL),
      let decoded = try? JSONCoding.decoder().decode([WebDavServer].self, from: data)
    else { return }
    servers = decoded.filter { !$0.id.isEmpty && !$0.baseUrl.isEmpty }
  }

  private func persist() {
    guard let data = try? JSONCoding.encoder().encode(servers) else { return }
    try? data.write(to: fileURL, options: .atomic)
  }
}
