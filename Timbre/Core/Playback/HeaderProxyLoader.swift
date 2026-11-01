import AVFoundation
import Foundation

/// 带请求头与 MIME 修正的流代理。
///
/// 为什么需要它：
/// 部分接口源的直链（典型是 B 站 DASH 音轨 `*.m4s`）由 CDN 做防盗链校验，
/// 要求拉流请求同时带 `Referer` 与浏览器 `User-Agent`，否则返回 403。
/// `AVURLAssetHTTPHeaderFieldsKey` 能补请求头，但有两个坑：
///   1. 只在该 key 生效的那次请求上补头，AVFoundation 后续补发的子请求不一定继承；
///   2. CDN 常把 `.m4s` 的 `Content-Type` 回成 `application/octet-stream`，
///      AVFoundation 靠它判容器，判不出来就直接 `item.status = .failed`（静默不播）。
///
/// 做法：把原始 URL 的 scheme 换成 `timbrestream`，交给
/// `AVAssetResourceLoaderDelegate` 接管。代理里用 URLSession 带完整请求头
/// 去拉真链接，主动回填 `contentType` 与 `contentLength`，再按 Range 把字节喂回去。
///
/// **必须边收边喂**：AVFoundation 对 fMP4 的第一手往往就是「从 0 到文件末尾」的
/// 整段区间（B 站音轨动辄 90 MB+）。早先的实现用 `dataTask` 的 completion 回调，
/// 等于要把整段下载完拿到 `Data` 才 `respond`，于是每次开始播放前都要干等
/// 整份文件落盘（实测 11–25 秒），期间界面一直转圈。现在改成
/// `URLSessionDataDelegate` 逐块 `respond`，收到第一个分片就开始喂。
///
/// 全过程写 DLog：日志里能看到每一次拉流请求的真实 HTTP 状态码，
/// 以后再遇到「这家的源播不了」，导日志就能分清是 CDN 拒绝还是容器不支持。
final class HeaderProxyLoader: NSObject, AVAssetResourceLoaderDelegate {

  /// 自定义 scheme，必须 ≥ 2 字符且不能是 http/https
  static let scheme = "timbrestream"

  private let origin: URL
  private let headers: [String: String]
  /// 0 = 按 m4a 声明（com.apple.m4a-audio），1 = 按 mp4 声明（public.mpeg-4）
  private let contentTypeVariant: Int
  /// 串行队列：既是 resourceLoader 的 delegate queue，也是所有状态读写的唯一入口
  private let queue = DispatchQueue(label: "app.timbre.stream-proxy", qos: .userInitiated)
  private var session: URLSession!
  /// URLSession 会强引用自己的 delegate，而 delegate 就是本对象时会造成循环引用、
  /// deinit 永远不执行。用这个弱引用转发器隔一层。
  private let delegateProxy = ProxySessionDelegate()

  /// 已探明的媒体总长度（来自 Content-Range），回填给 contentInformationRequest
  private var contentLength: Int64?
  private var requestCount = 0

  /// 为了让系统认出容器，给代理 URL 补的后缀（真实地址里没有，取流前要摘掉）
  private var appendedExtension: String?
  /// 系统类型表里认得的音频后缀，已经是这些就不用补
  private static let knownAudioExtensions: Set<String> = [
    "m4a", "m4b", "mp4", "mp3", "flac", "aac", "wav", "aif", "aiff", "caf", "ogg", "opus", "m4r",
  ]

  /// 已经交还给播放器的字节数 —— 用来区分「网慢」和「容器解不开」
  private let counterLock = NSLock()
  private var deliveredTotal: Int64 = 0
  var deliveredBytes: Int64 {
    counterLock.lock()
    defer { counterLock.unlock() }
    return deliveredTotal
  }

  /// 每一条仍在进行的字节流（taskIdentifier → 状态）
  private var streams: [Int: StreamState] = [:]

  private struct StreamState {
    let loadingRequest: AVAssetResourceLoadingRequest
    let requestedOffset: Int64
    let toEnd: Bool
    let wantLength: Int64
    /// 服务器忽略 Range 回了整份文件时，要丢弃的前缀字节数
    var skipRemaining: Int64 = 0
    /// 已经 respond 出去的字节数
    var delivered: Int64 = 0
  }

  init(origin: URL, headers: [String: String], contentTypeVariant: Int = 0) {
    self.origin = origin
    self.headers = headers
    self.contentTypeVariant = contentTypeVariant
    super.init()

    delegateProxy.owner = self
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 60
    // 整段 90 MB 的音轨按正常网速也要几分钟，别让资源超时把它掐了
    config.timeoutIntervalForResource = 3600
    config.httpMaximumConnectionsPerHost = 4
    // 流有自己的 Range 语义，别让系统缓存插一脚
    config.requestCachePolicy = .reloadIgnoringLocalCacheData
    // 回调跑在同一条串行队列上，streams / contentLength 就不用额外加锁
    let callbackQueue = OperationQueue()
    callbackQueue.underlyingQueue = queue
    callbackQueue.maxConcurrentOperationCount = 1
    session = URLSession(
      configuration: config,
      delegate: delegateProxy,
      delegateQueue: callbackQueue
    )
  }

  deinit {
    session.invalidateAndCancel()
  }

  /// 造一个走代理的 asset。调用方必须强引用本对象（resourceLoader.delegate 是 weak）。
  func makeAsset() -> AVURLAsset {
    var components = URLComponents(url: origin, resolvingAgainstBaseURL: false)
    components?.scheme = Self.scheme
    // 自定义 scheme 的 URL 必须带一个系统类型表里认得的后缀。
    // `.m4s` 不在表里（它是 DASH 分片），播放器判不出容器就只会一直拉数据、迟迟不 ready——
    // 表现就是「点了播放一直转圈」。补一个 .m4a，取流时再摘掉。
    if components != nil, !Self.knownAudioExtensions.contains(origin.pathExtension.lowercased()) {
      components?.path += ".m4a"
      appendedExtension = ".m4a"
    }
    guard let proxied = components?.url else {
      return AVURLAsset(url: origin)
    }
    let asset = AVURLAsset(url: proxied)
    asset.resourceLoader.setDelegate(self, queue: queue)
    DLog(
      "stream",
      "代理接管 \(origin.host ?? "-")\(origin.path) 头=\(headers.keys.sorted()) "
        + "对外后缀=\(proxied.pathExtension)"
    )
    return asset
  }

  static let browserUserAgent =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

  /// 拉流用的完整请求头：源给的 + 浏览器兜底
  private func mergedHeaders() -> [String: String] {
    var merged = headers
    func hasHeader(_ name: String) -> Bool {
      merged.keys.contains { $0.lowercased() == name }
    }
    // CDN 对 AVFoundation 默认的 AppleCoreMedia UA 常常直接 403
    if !hasHeader("user-agent") { merged["User-Agent"] = Self.browserUserAgent }
    if !hasHeader("accept") { merged["Accept"] = "*/*" }
    // 别让 CDN 用 gzip 压字节流，解压后 offset 就对不上 Range 了
    if !hasHeader("accept-encoding") { merged["Accept-Encoding"] = "identity" }
    return merged
  }

  private func realURL(from url: URL) -> URL? {
    guard var components = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
      return nil
    }
    components.scheme = origin.scheme
    // 摘掉 makeAsset 为了「让系统认出容器」临时补上的后缀
    if let appendedExtension, components.path.hasSuffix(appendedExtension) {
      components.path = String(components.path.dropLast(appendedExtension.count))
    }
    return components.url
  }

  // MARK: - AVAssetResourceLoaderDelegate

  func resourceLoader(
    _ resourceLoader: AVAssetResourceLoader,
    shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
  ) -> Bool {
    handle(loadingRequest)
    return true
  }

  /// 返回 false = 交回系统默认处理证书挑战（自签名 NAS 由 Info.plist 的 ATS 兜底）
  func resourceLoader(
    _ resourceLoader: AVAssetResourceLoader,
    shouldWaitForResponseTo authenticationChallenge: URLAuthenticationChallenge
  ) -> Bool {
    false
  }

  private func handle(_ loadingRequest: AVAssetResourceLoadingRequest) {
    guard let requestURL = loadingRequest.request.url,
      let target = realURL(from: requestURL)
    else {
      loadingRequest.finishLoading(with: NSError(
        domain: "TimbreStream", code: -1,
        userInfo: [NSLocalizedDescriptionKey: "代理无法还原真实地址"]
      ))
      return
    }

    var request = URLRequest(
      url: target,
      cachePolicy: .reloadIgnoringLocalCacheData,
      timeoutInterval: 60
    )
    var sent = mergedHeaders()

    // AVFoundation 这一轮要的字节区间，原样转成 Range 发出去
    let wantOffset: Int64
    let wantLength: Int64
    let toEnd: Bool
    if let dataRequest = loadingRequest.dataRequest {
      wantOffset = dataRequest.requestedOffset
      // requestedOffset 是 Int64，但 requestedLength 是 Int —— 别直接赋
      wantLength = Int64(dataRequest.requestedLength)
      toEnd = dataRequest.requestsAllDataToEndOfResource
      if toEnd || wantLength <= 0 {
        sent["Range"] = "bytes=\(wantOffset)-"
      } else {
        sent["Range"] = "bytes=\(wantOffset)-\(wantOffset + wantLength - 1)"
      }
    } else {
      // 只问元信息（容器 / 总长），用最小探测
      wantOffset = 0
      wantLength = 2
      toEnd = false
      sent["Range"] = "bytes=0-1"
    }

    for (key, value) in sent {
      request.setValue(value, forHTTPHeaderField: key)
    }

    let task = session.dataTask(with: request)
    streams[task.taskIdentifier] = StreamState(
      loadingRequest: loadingRequest,
      requestedOffset: wantOffset,
      toEnd: toEnd,
      wantLength: wantLength
    )
    task.resume()
  }

  // MARK: - 流式回调（全部跑在同一条串行队列上）

  func handleResponse(
    _ response: URLResponse,
    for dataTask: URLSessionDataTask,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    guard let state = streams[dataTask.taskIdentifier] else {
      completionHandler(.cancel)
      return
    }
    guard let http = response as? HTTPURLResponse else {
      finish(dataTask, error: NSError(
        domain: "TimbreStream", code: -2,
        userInfo: [NSLocalizedDescriptionKey: "无 HTTP 响应"]
      ))
      completionHandler(.cancel)
      return
    }

    if requestCount < 8 {
      requestCount += 1
      let ctype = http.value(forHTTPHeaderField: "Content-Type") ?? "-"
      let crange = http.value(forHTTPHeaderField: "Content-Range") ?? "-"
      let declared = resolveContentType(mime: ctype, pathExtension: originExtension)
      DLog("stream", "#\(requestCount) 状态=\(http.statusCode) 类型=\(ctype) 区间=\(crange) 声明=\(declared)")
    }

    guard (200...299).contains(http.statusCode) else {
      // 403 是最常见的：CDN 不认我们的头，或者直链过期了
      DLog("stream", "✗ 拉流被拒 状态=\(http.statusCode)")
      finish(dataTask, error: NSError(
        domain: "TimbreStream",
        code: http.statusCode,
        userInfo: [
          NSLocalizedDescriptionKey:
            "音频服务器返回 HTTP \(http.statusCode)，直链可能已过期或需要额外的请求头"
        ]
      ))
      completionHandler(.cancel)
      return
    }

    if let total = Self.totalLength(from: http) {
      contentLength = total
    }
    if let information = state.loadingRequest.contentInformationRequest {
      information.isByteRangeAccessSupported = true
      information.contentType = resolveContentType(
        mime: http.value(forHTTPHeaderField: "Content-Type"),
        pathExtension: originExtension
      )
      if let length = contentLength {
        information.contentLength = length
      }
    }

    // 只要元信息的那一轮：拿到响应头就够了，没必要把整个文件拉下来
    if state.loadingRequest.dataRequest == nil {
      streams.removeValue(forKey: dataTask.taskIdentifier)
      state.loadingRequest.finishLoading()
      completionHandler(.cancel)
      return
    }

    // 服务器忽略了 Range、回了整份文件：记住要跳过前面 wantOffset 个字节
    if http.statusCode == 200, state.requestedOffset > 0 {
      var updated = state
      updated.skipRemaining = state.requestedOffset
      streams[dataTask.taskIdentifier] = updated
    }

    completionHandler(.allow)
  }

  func handleData(_ data: Data, for dataTask: URLSessionDataTask) {
    guard var state = streams[dataTask.taskIdentifier] else { return }

    // AVFoundation 换 seek 目标时会取消旧请求，立刻停止下载，别白烧流量
    if state.loadingRequest.isCancelled {
      streams.removeValue(forKey: dataTask.taskIdentifier)
      dataTask.cancel()
      DLog("stream", "请求已被 AVFoundation 取消，停止下载")
      return
    }

    var chunk = data
    if state.skipRemaining > 0 {
      let drop = Int(min(state.skipRemaining, Int64(chunk.count)))
      chunk = chunk.dropFirst(drop)
      state.skipRemaining -= Int64(drop)
    }
    if !state.toEnd, state.wantLength > 0 {
      let remaining = state.wantLength - state.delivered
      if remaining <= 0 {
        streams.removeValue(forKey: dataTask.taskIdentifier)
        dataTask.cancel()
        return
      }
      if Int64(chunk.count) > remaining {
        chunk = chunk.prefix(Int(remaining))
      }
    }
    state.delivered += Int64(chunk.count)
    streams[dataTask.taskIdentifier] = state

    guard !chunk.isEmpty else { return }
    // 关键：一边收一边喂，播放器不用等整份文件
    state.loadingRequest.dataRequest?.respond(with: chunk)
    counterLock.lock()
    deliveredTotal += Int64(chunk.count)
    counterLock.unlock()
  }

  func handleCompletion(_ task: URLSessionTask, error: Error?) {
    // 已经从 streams 里摘掉的（主动取消 / 提前完成）不再重复 finish
    guard let state = streams.removeValue(forKey: task.taskIdentifier) else { return }
    if let error, (error as NSError).code != NSURLErrorCancelled {
      DLog("stream", "✗ 拉流中断 \(error.localizedDescription)")
      state.loadingRequest.finishLoading(with: error)
    } else {
      state.loadingRequest.finishLoading()
    }
  }

  /// 出错时收尾：摘掉状态 + 把错误交给播放器
  private func finish(_ task: URLSessionTask, error: Error) {
    guard let state = streams.removeValue(forKey: task.taskIdentifier) else {
      task.cancel()
      return
    }
    task.cancel()
    DLog("stream", "✗ 拉流失败 \(error.localizedDescription)")
    state.loadingRequest.finishLoading(with: error)
  }

  /// 从 `Content-Range: bytes 0-1/12345678` 或 `Content-Length` 里取总长
  private static func totalLength(from response: HTTPURLResponse) -> Int64? {
    if let range = response.value(forHTTPHeaderField: "Content-Range"),
      let slash = range.lastIndex(of: "/")
    {
      let tail = range[range.index(after: slash)...].trimmingCharacters(in: .whitespaces)
      if tail != "*", let total = Int64(tail) { return total }
    }
    if response.statusCode == 200,
      let raw = response.value(forHTTPHeaderField: "Content-Length"),
      let total = Int64(raw)
    {
      return total
    }
    return nil
  }

  /// 容器类型。这里必须用 `AVFileType` 的真值，不能手写 UTI 字符串。
  ///
  /// 踩过的坑：`public.mp4` 不是合法 UTI（Apple 的 MPEG-4 电影是 `public.mpeg-4`），
  /// 回填一个查不到的 UTI，AVFoundation 直接判 `AVErrorFileFormatNotRecognized (-11828)`，
  /// 而且报错时机在 item 加载阶段，看起来就像「这个源播不了」。
  /// B 站 DASH 音轨是标准 fMP4（ftyp iso5 + moov/mvex + stsd mp4a），
  /// 容器本身没问题，错的一直是我们回填的类型。
  private func resolveContentType(mime: String?, pathExtension: String) -> String {
    let lower = (mime ?? "").lowercased()
    let ext = pathExtension.lowercased()

    // FLAC 在 AVFileType 里没有对应常量，用系统 UTI
    if ext == "flac" || lower.contains("flac") { return "org.xiph.flac" }
    if ext == "mp3" || (lower.contains("mpeg") && lower.contains("audio")) {
      return AVFileType.mp3.rawValue
    }
    // 真正的 .mp4（WebDAV 里的有声书也常是它）保持「电影」声明，别动已经能播的路径
    if ext == "mp4" || ext == "m4v" { return AVFileType.mp4.rawValue }
    // 其余（含 DASH 的 .m4s）都是纯音轨：先按 com.apple.m4a-audio 声明。
    // 注意不能因为 CDN 回了 `video/mp4` 就改成 public.mpeg-4 —— B 站音轨是单条 AAC，
    // 按「电影」声明反而让播放器一直等视频轨。variant 1 才退回 mpeg-4 兜底。
    if contentTypeVariant == 1 { return AVFileType.mp4.rawValue }
    return AVFileType.m4a.rawValue
  }

  /// 真实直链的后缀（不是代理 URL 上补出来的 .m4a）
  private var originExtension: String { origin.pathExtension.lowercased() }
}

/// URLSession 的 delegate 弱转发：让 session 只强引用这个中间人，
/// 真正的 loader 才能正常 deinit（session 会一直持有 delegate 直到 invalidate）。
private final class ProxySessionDelegate: NSObject, URLSessionDataDelegate {
  weak var owner: HeaderProxyLoader?

  func urlSession(
    _ session: URLSession,
    dataTask: URLSessionDataTask,
    didReceive response: URLResponse,
    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
  ) {
    guard let owner else {
      completionHandler(.cancel)
      return
    }
    owner.handleResponse(response, for: dataTask, completionHandler: completionHandler)
  }

  func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
    owner?.handleData(data, for: dataTask)
  }

  func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
    owner?.handleCompletion(task, error: error)
  }
}
