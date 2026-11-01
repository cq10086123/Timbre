import Foundation

/// WebDAV 协议客户端：PROPFIND 列目录、Basic 认证、可选信任自签名证书。
/// 行为对齐安卓 `core/webdav/WebDavClient.kt`；Digest 认证暂未实现，遇到会明确报错。
enum WebDavClient {
  private static let timeout: TimeInterval = 30

  private static let propfindBody = """
    <?xml version="1.0" encoding="utf-8"?>
    <d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:getcontentlength/>\
    <d:getlastmodified/><d:resourcetype/></d:prop></d:propfind>
    """

  /// 列出目录内容（Depth: 1），不含目录自身
  static func list(
    url: URL,
    server: WebDavServer,
    password: String
  ) async throws -> [WebDavResource] {
    let (data, response) = try await send(
      url: url,
      server: server,
      password: password,
      method: "PROPFIND",
      depth: "1",
      body: Data(propfindBody.utf8),
      headers: ["Content-Type": "text/xml; charset=utf-8"]
    )
    if response.statusCode == 404 { throw WebDavError.notFound }
    guard response.statusCode == 207 || response.statusCode == 200 else {
      throw WebDavError.status(response.statusCode)
    }
    return try MultiStatusParser.parse(data: data, requestURL: url)
  }

  /// 测试连接：能列出根目录，并顺手探测是否支持 Range（决定在线播放流畅度）
  static func test(server: WebDavServer, password: String) async throws -> String {
    guard let root = URL(string: server.normalizedBase + "/") else { throw WebDavError.badURL }
    let items = try await list(url: root, server: server, password: password)

    if let file = items.first(where: { !$0.isDirectory }), let fileURL = URL(string: file.url),
      let (_, ranged) = try? await send(
        url: fileURL,
        server: server,
        password: password,
        method: "GET",
        depth: nil,
        body: nil,
        headers: ["Range": "bytes=0-0"]
      )
    {
      if ranged.statusCode == 206 { return "连接成功，支持在线播放" }
      return "已连接，但服务器不支持 Range 断点请求，在线播放可能不流畅"
    }
    return "连接成功，支持在线播放"
  }

  /// 播放与下载用的请求头
  static func authHeaders(server: WebDavServer, password: String) -> [String: String] {
    guard let value = basicAuth(user: server.username, password: password) else { return [:] }
    return ["Authorization": value]
  }

  // MARK: - 底层请求

  private static func send(
    url: URL,
    server: WebDavServer,
    password: String,
    method: String,
    depth: String?,
    body: Data?,
    headers: [String: String]
  ) async throws -> (Data, HTTPURLResponse) {
    guard url.scheme != nil, url.host != nil else { throw WebDavError.badURL }

    var request = URLRequest(url: url)
    request.httpMethod = method
    request.timeoutInterval = timeout
    if let depth { request.setValue(depth, forHTTPHeaderField: "Depth") }
    if let auth = basicAuth(user: server.username, password: password) {
      request.setValue(auth, forHTTPHeaderField: "Authorization")
    }
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
    request.httpBody = body

    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = timeout
    configuration.timeoutIntervalForResource = timeout * 2
    let delegate = server.trustAllCertificates ? TrustAllDelegate() : nil
    let session = URLSession(
      configuration: configuration,
      delegate: delegate,
      delegateQueue: nil
    )
    defer { session.finishTasksAndInvalidate() }

    let result: (Data, URLResponse)
    do {
      result = try await session.data(for: request)
    } catch {
      throw WebDavError.transport(error.localizedDescription)
    }

    guard let http = result.1 as? HTTPURLResponse else {
      throw WebDavError.transport("响应不是 HTTP 响应")
    }
    if http.statusCode == 401 {
      let challenge = http.value(forHTTPHeaderField: "WWW-Authenticate") ?? ""
      if challenge.lowercased().contains("digest") { throw WebDavError.digestUnsupported }
      throw WebDavError.auth
    }
    DLog("webdav", "\(method) \(url.absoluteString) → \(http.statusCode) \(result.0.count)B")
    return (result.0, http)
  }

  private static func basicAuth(user: String, password: String) -> String? {
    guard !user.isEmpty else { return nil }
    let token = Data("\(user):\(password)".utf8).base64EncodedString()
    return "Basic \(token)"
  }
}

/// 自签名证书：仅在用户显式勾选时使用
private final class TrustAllDelegate: NSObject, URLSessionDelegate {
  func urlSession(
    _ session: URLSession,
    didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    guard let trust = challenge.protectionSpace.serverTrust else {
      completionHandler(.performDefaultHandling, nil)
      return
    }
    completionHandler(.useCredential, URLCredential(trust: trust))
  }
}

// MARK: - 207 Multi-Status 解析

private final class MultiStatusParser: NSObject, XMLParserDelegate {
  static func parse(data: Data, requestURL: URL) throws -> [WebDavResource] {
    let parser = MultiStatusParser(requestURL: requestURL)
    let xml = XMLParser(data: data)
    xml.delegate = parser
    // 交给解析器处理命名空间，配合 localName() 兼容带 d: / D: / lp1: 前缀的服务器
    xml.shouldProcessNamespaces = true
    xml.shouldResolveExternalEntities = false
    guard xml.parse() else { throw WebDavError.parse }
    return parser.items
  }

  /// 去掉命名空间前缀，取本地名
  private static func localName(_ name: String) -> String {
    guard let index = name.firstIndex(of: ":") else { return name }
    return String(name[name.index(after: index)...])
  }

  private let requestURL: URL
  private var items: [WebDavResource] = []
  private var inResponse = false
  private var href: String?
  private var displayName: String?
  private var contentLength: Int64 = -1
  private var lastModified: TimeInterval = 0
  private var isCollection = false
  private var text = ""

  private init(requestURL: URL) {
    self.requestURL = requestURL
  }

  func parser(
    _ parser: XMLParser,
    didStartElement elementName: String,
    namespaceURI: String?,
    qualifiedName qName: String?,
    attributes attributeDict: [String: String] = [:]
  ) {
    text = ""
    switch Self.localName(elementName) {
    case "response":
      inResponse = true
      href = nil
      displayName = nil
      contentLength = -1
      lastModified = 0
      isCollection = false
    case "collection":
      if inResponse { isCollection = true }
    default:
      break
    }
  }

  func parser(_ parser: XMLParser, foundCharacters string: String) {
    text += string
  }

  func parser(
    _ parser: XMLParser,
    didEndElement elementName: String,
    namespaceURI: String?,
    qualifiedName qName: String?
  ) {
    guard inResponse else { return }
    switch Self.localName(elementName) {
    case "href":
      href = text.trimmingCharacters(in: .whitespacesAndNewlines)
    case "displayname":
      displayName = text.trimmingCharacters(in: .whitespacesAndNewlines)
    case "getcontentlength":
      contentLength = Int64(text.trimmingCharacters(in: .whitespacesAndNewlines)) ?? -1
    case "getlastmodified":
      lastModified = Self.date(from: text.trimmingCharacters(in: .whitespacesAndNewlines))
    case "response":
      inResponse = false
      appendCurrent()
    default:
      break
    }
    text = ""
  }

  private func appendCurrent() {
    guard let raw = href, !raw.isEmpty else { return }
    guard let resolved = resolve(raw) else { return }
    let resolvedPath = resolved.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    let requestPath = requestURL.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
    // 第一个 response 通常是目录自身，跳过
    if resolvedPath == requestPath { return }

    let decoded = displayName?.removingPercentEncoding ?? ""
    let fallback = resolved.lastPathComponent.removingPercentEncoding ?? resolved.lastPathComponent
    let name = decoded.isEmpty ? fallback : decoded
    guard !name.isEmpty else { return }

    items.append(
      WebDavResource(
        url: resolved.absoluteString,
        name: name,
        isDirectory: isCollection || name.hasSuffix("/"),
        contentLength: contentLength,
        lastModified: lastModified
      )
    )
  }

  private func resolve(_ raw: String) -> URL? {
    if let url = URL(string: raw), url.scheme != nil { return url }
    if raw.hasPrefix("/") {
      guard let scheme = requestURL.scheme, let host = requestURL.host else { return nil }
      var prefix = "\(scheme)://\(host)"
      if let port = requestURL.port { prefix += ":\(port)" }
      return URL(string: prefix + raw)
    }
    return URL(string: raw, relativeTo: requestURL)?.absoluteURL
  }

  private static func date(from text: String) -> TimeInterval {
    guard !text.isEmpty else { return 0 }
    let formats = [
      "EEE, dd MMM yyyy HH:mm:ss zzz",
      "EEE, dd MMM yyyy HH:mm:ss Z",
      "yyyy-MM-dd'T'HH:mm:ss'Z'",
      "yyyy-MM-dd'T'HH:mm:ssZ",
    ]
    for format in formats {
      let formatter = DateFormatter()
      formatter.locale = Locale(identifier: "en_US_POSIX")
      formatter.timeZone = TimeZone(identifier: "GMT")
      formatter.dateFormat = format
      if let date = formatter.date(from: text) { return date.timeIntervalSince1970 }
    }
    return 0
  }
}
