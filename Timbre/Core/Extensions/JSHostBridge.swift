import Foundation
import JavaScriptCore

/// 接口源脚本的宿主能力桥：http / 加密 / 编码 / 随机数。
/// 全部通过 @convention(block) 注入到 JSContext，避免 JSExport 的命名差异。
enum JSHostBridge {

  static func install(into context: JSContext, allowInsecure: Bool) {
    func put(_ name: String, _ object: AnyObject) {
      context.setObject(object, forKeyedSubscript: name as NSString)
    }

    let md5: @convention(block) (String) -> String = { CryptoSupport.md5Hex($0) }
    put("__md5Hex", unsafeBitCast(md5, to: AnyObject.self))

    let sha: @convention(block) (String) -> String = { CryptoSupport.sha256Hex($0) }
    put("__sha256Hex", unsafeBitCast(sha, to: AnyObject.self))

    let hmac: @convention(block) (String, String) -> String = { key, text in
      CryptoSupport.hmacSha256Hex(key: key, text: text)
    }
    put("__hmacSha256Hex", unsafeBitCast(hmac, to: AnyObject.self))

    let aesEcbEnc: @convention(block) (String, String) -> String = { dataB64, keyB64 in
      do {
        guard let data = Data(base64Encoded: dataB64), let key = Data(base64Encoded: keyB64) else {
          return JSHostBridge.errJSON("aesEcbEncrypt: base64 解码失败")
        }
        return JSHostBridge.okJSON(try CryptoSupport.aesEcbEncrypt(data, key: key).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__aesEcbEncryptB64", unsafeBitCast(aesEcbEnc, to: AnyObject.self))

    let aesEcbDec: @convention(block) (String, String) -> String = { dataB64, keyB64 in
      do {
        guard let data = Data(base64Encoded: dataB64), let key = Data(base64Encoded: keyB64) else {
          return JSHostBridge.errJSON("aesEcbDecrypt: base64 解码失败")
        }
        return JSHostBridge.okJSON(try CryptoSupport.aesEcbDecrypt(data, key: key).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__aesEcbDecryptB64", unsafeBitCast(aesEcbDec, to: AnyObject.self))

    let aesGcmEnc: @convention(block) (String, String, String) -> String = { keyB64, nonceB64, dataB64 in
      do {
        guard let key = Data(base64Encoded: keyB64),
          let nonce = Data(base64Encoded: nonceB64),
          let data = Data(base64Encoded: dataB64)
        else { return JSHostBridge.errJSON("aesGcmEncrypt: base64 解码失败") }
        return JSHostBridge.okJSON(try CryptoSupport.aesGcmEncrypt(key: key, nonce: nonce, data: data).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__aesGcmEncryptB64", unsafeBitCast(aesGcmEnc, to: AnyObject.self))

    let aesGcmDec: @convention(block) (String, String, String) -> String = { keyB64, nonceB64, dataB64 in
      do {
        guard let key = Data(base64Encoded: keyB64),
          let nonce = Data(base64Encoded: nonceB64),
          let data = Data(base64Encoded: dataB64)
        else { return JSHostBridge.errJSON("aesGcmDecrypt: base64 解码失败") }
        return JSHostBridge.okJSON(try CryptoSupport.aesGcmDecrypt(key: key, nonce: nonce, combined: data).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__aesGcmDecryptB64", unsafeBitCast(aesGcmDec, to: AnyObject.self))

    let chachaEnc: @convention(block) (String, String, String) -> String = { keyB64, nonceB64, dataB64 in
      do {
        guard let key = Data(base64Encoded: keyB64),
          let nonce = Data(base64Encoded: nonceB64),
          let data = Data(base64Encoded: dataB64)
        else { return JSHostBridge.errJSON("chacha20Poly1305Encrypt: base64 解码失败") }
        return JSHostBridge.okJSON(try CryptoSupport.chachaEncrypt(key: key, nonce: nonce, data: data).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__chacha20Poly1305EncryptB64", unsafeBitCast(chachaEnc, to: AnyObject.self))

    let chachaDec: @convention(block) (String, String, String) -> String = { keyB64, nonceB64, dataB64 in
      do {
        guard let key = Data(base64Encoded: keyB64),
          let nonce = Data(base64Encoded: nonceB64),
          let data = Data(base64Encoded: dataB64)
        else { return JSHostBridge.errJSON("chacha20Poly1305Decrypt: base64 解码失败") }
        return JSHostBridge.okJSON(try CryptoSupport.chachaDecrypt(key: key, nonce: nonce, combined: data).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__chacha20Poly1305DecryptB64", unsafeBitCast(chachaDec, to: AnyObject.self))

    let sm4Enc: @convention(block) (String, String) -> String = { dataB64, keyB64 in
      do {
        guard let data = Data(base64Encoded: dataB64), let key = Data(base64Encoded: keyB64) else {
          return JSHostBridge.errJSON("sm4EcbEncrypt: base64 解码失败")
        }
        let out = try SM4.encrypt([UInt8](data), key: [UInt8](key))
        return JSHostBridge.okJSON(Data(out).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__sm4EcbEncryptB64", unsafeBitCast(sm4Enc, to: AnyObject.self))

    let sm4Dec: @convention(block) (String, String) -> String = { dataB64, keyB64 in
      do {
        guard let data = Data(base64Encoded: dataB64), let key = Data(base64Encoded: keyB64) else {
          return JSHostBridge.errJSON("sm4EcbDecrypt: base64 解码失败")
        }
        let out = try SM4.decrypt([UInt8](data), key: [UInt8](key))
        return JSHostBridge.okJSON(Data(out).base64EncodedString())
      } catch {
        return JSHostBridge.errJSON(error.localizedDescription)
      }
    }
    put("__sm4EcbDecryptB64", unsafeBitCast(sm4Dec, to: AnyObject.self))

    let random: @convention(block) (Double) -> String = { count in
      okJSON(CryptoSupport.randomBytes(Int(count)).base64EncodedString())
    }
    put("__randomBytesB64", unsafeBitCast(random, to: AnyObject.self))

    let timestamp: @convention(block) () -> Double = { Date().timeIntervalSince1970.rounded(.down) }
    put("__timestamp", unsafeBitCast(timestamp, to: AnyObject.self))

    let timestampMs: @convention(block) () -> Double = { (Date().timeIntervalSince1970 * 1000).rounded(.down) }
    put("__timestampMs", unsafeBitCast(timestampMs, to: AnyObject.self))

    let urlEncode: @convention(block) (String) -> String = { CryptoSupport.urlEncode($0) }
    put("__urlEncode", unsafeBitCast(urlEncode, to: AnyObject.self))

    let urlDecode: @convention(block) (String) -> String = { CryptoSupport.urlDecode($0) }
    put("__urlDecode", unsafeBitCast(urlDecode, to: AnyObject.self))

    let log: @convention(block) (String) -> Void = { message in
      #if DEBUG
        print("[source] \(message)")
      #endif
    }
    put("__log", unsafeBitCast(log, to: AnyObject.self))

    let http: @convention(block) (String, String, String) -> String = { method, url, options in
      HTTPBridge.request(method: method, url: url, optionsJSON: options, allowInsecure: allowInsecure)
    }
    put("__httpRequest", unsafeBitCast(http, to: AnyObject.self))
  }

  private static func okJSON(_ value: String) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: ["value": value]),
      let text = String(data: data, encoding: .utf8)
    else { return "{\"value\":\"\"}" }
    return text
  }

  private static func errJSON(_ message: String) -> String {
    guard let data = try? JSONSerialization.data(withJSONObject: ["error": message]),
      let text = String(data: data, encoding: .utf8)
    else { return "{\"error\":\"未知错误\"}" }
    return text
  }
}

/// 同步 http 桥：阻塞在调用线程（脚本线程）上，等 URLSession 完成
enum HTTPBridge {
  private static let maxResponseBytes = 10 * 1024 * 1024
  private static let defaultTimeoutMs = 30_000.0

  final class InsecureDelegate: NSObject, URLSessionDelegate {
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

  static func request(method: String, url: String, optionsJSON: String, allowInsecure: Bool) -> String {
    let options = (try? JSONSerialization.jsonObject(with: Data(optionsJSON.utf8)))
      as? [String: Any] ?? [:]

    guard var components = URLComponents(string: url) else {
      return errorJSON("URL 不合法：\(url)")
    }
    if let params = options["params"] as? [String: Any], !params.isEmpty {
      var items = components.queryItems ?? []
      for key in params.keys.sorted() {
        items.append(URLQueryItem(name: key, value: stringValue(params[key])))
      }
      components.queryItems = items
    }
    guard let finalURL = components.url else {
      return errorJSON("URL 不合法：\(url)")
    }

    var request = URLRequest(url: finalURL)
    request.httpMethod = method.uppercased()
    let timeoutSeconds = (options["timeoutMs"] as? NSNumber)?.doubleValue ?? defaultTimeoutMs
    request.timeoutInterval = max(3, timeoutSeconds / 1000)

    if let headers = options["headers"] as? [String: Any] {
      for (name, value) in headers {
        request.setValue(stringValue(value), forHTTPHeaderField: name)
      }
    }

    if let json = options["json"] {
      request.setValue("application/json; charset=utf-8", forHTTPHeaderField: "Content-Type")
      request.httpBody = try? JSONSerialization.data(withJSONObject: json)
    } else if let form = options["form"] as? [String: Any] {
      request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
      var items: [URLQueryItem] = []
      for key in form.keys.sorted() {
        items.append(URLQueryItem(name: key, value: stringValue(form[key])))
      }
      var bodyComponents = URLComponents()
      bodyComponents.queryItems = items
      request.httpBody = bodyComponents.percentEncodedQuery.map { Data($0.utf8) }
    } else if let body = options["body"] {
      request.httpBody = stringValue(body).data(using: .utf8)
    } else if method.uppercased() == "POST" {
      request.httpBody = Data()
    }

    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = request.timeoutInterval
    configuration.timeoutIntervalForResource = max(10, request.timeoutInterval * 2)
    let delegate = allowInsecure ? InsecureDelegate() : nil
    let session = URLSession(
      configuration: configuration,
      delegate: delegate,
      delegateQueue: nil
    )
    defer { session.finishTasksAndInvalidate() }

    let semaphore = DispatchSemaphore(value: 0)
    var responseData: Data?
    var statusCode = 0
    var responseHeaders: [String: String] = [:]
    var failure: String?

    let task = session.dataTask(with: request) { data, response, error in
      if let error {
        failure = error.localizedDescription
      } else if let httpResponse = response as? HTTPURLResponse {
        statusCode = httpResponse.statusCode
        var merged: [String: String] = [:]
        for (key, value) in httpResponse.allHeaderFields {
          let name = "\(key)".lowercased()
          if let existing = merged[name] {
            merged[name] = existing + ", " + "\(value)"
          } else {
            merged[name] = "\(value)"
          }
        }
        responseHeaders = merged
        responseData = data
      } else {
        failure = "响应不是 HTTP 响应"
      }
      semaphore.signal()
    }
    task.resume()

    if semaphore.wait(timeout: .now() + request.timeoutInterval + 5) == .timedOut {
      task.cancel()
      return errorJSON("请求超时：\(url)")
    }
    if let failure {
      return errorJSON(failure)
    }
    guard let data = responseData else {
      return errorJSON("空响应：\(url)")
    }
    if data.count > maxResponseBytes {
      return errorJSON("响应超过 10MB 上限：\(url)")
    }
    let body = String(data: data, encoding: .utf8)
      ?? String(decoding: data, as: UTF8.self)

    let payload: [String: Any] = [
      "status": statusCode,
      "headers": responseHeaders,
      "body": body,
    ]
    guard let out = try? JSONSerialization.data(withJSONObject: payload),
      let text = String(data: out, encoding: .utf8)
    else {
      return errorJSON("响应序列化失败")
    }
    return text
  }

  private static func stringValue(_ value: Any?) -> String {
    guard let value else { return "" }
    if let text = value as? String { return text }
    if let number = value as? NSNumber { return number.stringValue }
    return "\(value)"
  }

  private static func errorJSON(_ message: String) -> String {
    let payload: [String: Any] = ["__error": message]
    guard let data = try? JSONSerialization.data(withJSONObject: payload),
      let text = String(data: data, encoding: .utf8)
    else { return "{\"__error\":\"请求失败\"}" }
    return text
  }
}
