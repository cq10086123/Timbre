import Foundation

/// 把一集在线音频边下边写进临时文件。
///
/// 对齐安卓 `OnlineBookCacheManager.downloadToCache`：
/// **200 不代表拿到了音频** —— 书源经常回一个 html 错误页、或者过期链接回一段 json，
/// 而一个被截断的传输看起来和正常结束一模一样。所以只有「字节数与服务器声明的
/// 一致」且「开头不像网页」时才转正，否则这些文件会让 `isCached` 永远跳过这一集。
final class ChapterDownloader: NSObject, URLSessionDataDelegate {

  enum Outcome {
    case stored
    case failed(OnlineCacheFailureReason, String?)
  }

  /// 每 200ms 回报一次转移进度（对齐安卓 PROGRESS_INTERVAL_MS）
  private static let progressInterval: TimeInterval = 0.2
  /// 判断「像不像网页」只看开头这么多字节
  private static let sniffBytes = 8
  /// 错误原文最多留这么多字节
  private static let previewBytes = 240

  private let tmpURL: URL
  private let fileURL: URL
  private let onProgress: (Int64, Int64) -> Void

  private let lock = NSLock()
  private var session: URLSession?
  private var handle: FileHandle?
  private var continuation: CheckedContinuation<Outcome, Never>?
  private var finished = false
  private var cancelled = false
  private var written: Int64 = 0
  private var expected: Int64 = -1
  private var headChecked = false
  private var contentType: String?
  private var statusCode = 0
  private var lastEmit: TimeInterval = 0

  private init(tmpURL: URL, fileURL: URL, onProgress: @escaping (Int64, Int64) -> Void) {
    self.tmpURL = tmpURL
    self.fileURL = fileURL
    self.onProgress = onProgress
  }

  static func download(
    url: URL,
    headers: [String: String],
    tmpURL: URL,
    fileURL: URL,
    onProgress: @escaping (Int64, Int64) -> Void
  ) async -> Outcome {
    let downloader = ChapterDownloader(tmpURL: tmpURL, fileURL: fileURL, onProgress: onProgress)
    return await withTaskCancellationHandler {
      await downloader.start(url: url, headers: headers)
    } onCancel: {
      downloader.cancel()
    }
  }

  // MARK: - 生命周期

  private func start(url: URL, headers: [String: String]) async -> Outcome {
    var request = URLRequest(url: url)
    request.timeoutInterval = 60
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }

    // 临时文件所在目录可能还没建（这本书第一次缓存）
    try? FileManager.default.createDirectory(
      at: tmpURL.deletingLastPathComponent(),
      withIntermediateDirectories: true
    )

    let configuration = URLSessionConfiguration.ephemeral
    configuration.timeoutIntervalForRequest = 60
    configuration.timeoutIntervalForResource = 3600
    configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
    let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)

    lock.lock()
    self.session = session
    let alreadyCancelled = cancelled
    lock.unlock()
    if alreadyCancelled {
      session.invalidateAndCancel()
      return .failed(.download, "已取消")
    }

    return await withCheckedContinuation { continuation in
      lock.lock()
      // 取消可能刚好发生在上面那次检查之后、这里挂上 continuation 之前：
      // 那时 finish 已经把 finished 置上却没有 continuation 可 resume，
      // 这里必须自己收尾，否则这个 await 永远不返回。
      if finished {
        lock.unlock()
        continuation.resume(returning: .failed(.download, "已取消"))
        return
      }
      self.continuation = continuation
      lock.unlock()
      session.dataTask(with: request).resume()
    }
  }

  func cancel() {
    lock.lock()
    cancelled = true
    let session = self.session
    lock.unlock()
    session?.invalidateAndCancel()
    finish(.failed(.download, "已取消"))
  }

  private func finish(_ outcome: Outcome) {
    lock.lock()
    if finished {
      lock.unlock()
      return
    }
    finished = true
    let continuation = self.continuation
    self.continuation = nil
    let handle = self.handle
    self.handle = nil
    let session = self.session
    self.session = nil
    lock.unlock()

    try? handle?.close()
    session?.finishTasksAndInvalidate()
    if case .failed = outcome {
      try? FileManager.default.removeItem(at: tmpURL)
    }
    continuation?.resume(returning: outcome)
  }

  // MARK: - URLSessionDataDelegate

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    lock.lock()
    if let http = response as? HTTPURLResponse {
      statusCode = http.statusCode
      expected = http.expectedContentLength
      contentType = http.value(forHTTPHeaderField: "Content-Type")
    } else {
      expected = response.expectedContentLength
    }
    let code = statusCode
    let type = contentType
    lock.unlock()

    if code != 0 && !(200..<300).contains(code) {
      completionHandler(.cancel)
      finish(.failed(.download, "HTTP \(code)"))
      return
    }
    if Self.isErrorContentType(type) {
      completionHandler(.cancel)
      finish(.failed(.notAudio, "Content-Type: \(type ?? "-")"))
      return
    }

    FileManager.default.createFile(atPath: tmpURL.path, contents: nil)
    guard let handle = try? FileHandle(forWritingTo: tmpURL) else {
      completionHandler(.cancel)
      finish(.failed(.download, "无法写入缓存文件"))
      return
    }
    lock.lock()
    self.handle = handle
    lock.unlock()
    completionHandler(.allow)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    lock.lock()
    if finished {
      lock.unlock()
      return
    }
    let handle = self.handle
    lock.unlock()

    guard let handle else { return }

    // 过期链接经常 200 回一个 "{" 或 "<!doctype"：只看第一块就够把它们挡在缓存之外
    if !headChecked {
      headChecked = true
      if Self.looksLikeText(data) {
        // 从中间截断的 UTF-8 会以替换符结尾，预告里没必要留着
        let head = String(decoding: data.prefix(Self.previewBytes), as: UTF8.self)
          .trimmingCharacters(in: CharacterSet(charactersIn: "\u{FFFD}"))
        handle.closeFile()
        lock.lock()
        let type = contentType
        lock.unlock()
        finish(.failed(.notAudio, head.isEmpty ? "Content-Type: \(type ?? "-")" : head))
        return
      }
    }

    handle.write(data)

    lock.lock()
    written += Int64(data.count)
    let now = Date().timeIntervalSince1970
    let shouldEmit = now - lastEmit >= Self.progressInterval
    if shouldEmit { lastEmit = now }
    let currentWritten = written
    let currentExpected = expected
    lock.unlock()

    if shouldEmit {
      onProgress(currentWritten, currentExpected)
    }
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    lock.lock()
    let failure = error
    let writtenBytes = written
    let expectedBytes = expected
    let code = statusCode
    let cancelledNow = cancelled
    lock.unlock()

    if let failure {
      let nsError = failure as NSError
      if nsError.code == NSURLErrorCancelled {
        finish(.failed(.download, "已取消"))
      } else {
        DLog("cache", "下载中断：\(failure.localizedDescription)")
        finish(.failed(.download, failure.localizedDescription))
      }
      return
    }

    if writtenBytes <= 0 {
      finish(.failed(.download, "HTTP \(code) · 0 bytes"))
      return
    }
    if expectedBytes > 0 && writtenBytes != expectedBytes {
      finish(.failed(.incomplete, "\(writtenBytes) / \(expectedBytes) bytes"))
      return
    }
    if cancelledNow {
      finish(.failed(.download, "已取消"))
      return
    }

    // 转正才代表这一集真的在设备上了
    if FileManager.default.fileExists(atPath: fileURL.path) {
      try? FileManager.default.removeItem(at: fileURL)
    }
    do {
      try FileManager.default.moveItem(at: tmpURL, to: fileURL)
      finish(.stored)
    } catch {
      DLog("cache", "缓存文件转正失败：\(error.localizedDescription)")
      finish(.failed(.download, "转存失败：\(error.localizedDescription)"))
    }
  }

  // MARK: - 辅助

  /// 「这是网页 / 接口返回」，绝不是音频。
  /// 未知类型一律放行 —— 不少 CDN 把 mp3 标成 application/octet-stream
  private static func isErrorContentType(_ value: String?) -> Bool {
    guard let value else { return false }
    let lower = value.lowercased()
    let main = lower.split(separator: ";").first.map(String.init) ?? lower
    if main.hasPrefix("text/") { return true }
    return main.contains("html") || main.contains("json") || main.contains("xml")
  }

  /// 开头像不像标记语言（而不是音频）
  private static func looksLikeText(_ data: Data) -> Bool {
    for byte in data.prefix(sniffBytes) {
      switch byte {
      // 带 UTF-8 BOM 或者前导空白，还是 html
      case 0xEF, 0xBB, 0xBF, 0x20, 0x09, 0x0A, 0x0D:
        continue
      case 0x3C, 0x7B, 0x5B:  // < { [
        return true
      default:
        return false
      }
    }
    return false
  }
}
