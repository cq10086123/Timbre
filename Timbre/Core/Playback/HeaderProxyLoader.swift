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
  private let session: URLSession

  /// 已探明的媒体总长度（来自 Content-Range），回填给 contentInformationRequest
  private var contentLength: Int64?
  private var requestCount = 0

  init(origin: URL, headers: [String: String], contentTypeVariant: Int = 0) {
    self.origin = origin
    self.headers = headers
    self.contentTypeVariant = contentTypeVariant
    let config = URLSessionConfiguration.ephemeral
    config.timeoutIntervalForRequest = 30
    config.timeoutIntervalForResource = 120
    config.httpMaximumConnectionsPerHost = 4
    // 流有自己的 Range 语义，别让系统缓存插一脚
    config.requestCachePolicy = .reloadIgnoringLocalCacheData
    self.session = URLSession(configuration: config)
    super.init()
  }

  deinit {
    session.invalidateAndCancel()
  }

  /// 造一个走代理的 asset。调用方必须强引用本对象（resourceLoader.delegate 是 weak）。
  func makeAsset() -> AVURLAsset {
    var components = URLComponents(url: origin, resolvingAgainstBaseURL: false)
    components?.scheme = Self.scheme
    guard let proxied = components?.url else {
      return AVURLAsset(url: origin)
    }
    let asset = AVURLAsset(url: proxied)
    asset.resourceLoader.setDelegate(self, queue: queue)
    DLog("stream", "代理接管 \(origin.host ?? "-")\(origin.path) 头=\(headers.keys.sorted())")
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
      timeoutInterval: 30
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

    let rangeDesc = sent["Range"] ?? "-"
    let task = session.dataTask(with: request) { [weak self] data, response, error in
      guard let self else {
        loadingRequest.finishLoading()
        return
      }
      // URLSession 的 completion 跑在它自己的 delegate queue 上，
      // 必须切回 queue 再碰 contentLength / loadingRequest，否则就是数据竞争
      self.queue.async {
        self.finish(
          loadingRequest,
          data: data,
          response: response,
          error: error,
          range: rangeDesc,
          wantOffset: wantOffset,
          wantLength: wantLength,
          toEnd: toEnd,
          target: target
        )
      }
    }
    task.resume()
  }

  private func finish(
    _ loadingRequest: AVAssetResourceLoadingRequest,
    data: Data?,
    response: URLResponse?,
    error: Error?,
    range: String,
    wantOffset: Int64,
    wantLength: Int64,
    toEnd: Bool,
    target: URL
  ) {
    guard !loadingRequest.isCancelled else {
      DLog("stream", "请求已被 AVFoundation 取消 range=\(range)")
      return
    }
    if let error {
      DLog("stream", "✗ 拉流失败 range=\(range) \(error.localizedDescription)")
      loadingRequest.finishLoading(with: error)
      return
    }
    guard let http = response as? HTTPURLResponse else {
      loadingRequest.finishLoading(with: NSError(
        domain: "TimbreStream", code: -2,
        userInfo: [NSLocalizedDescriptionKey: "无 HTTP 响应"]
      ))
      return
    }

    if requestCount < 8 {
      requestCount += 1
      let ctype = http.value(forHTTPHeaderField: "Content-Type") ?? "-"
      let crange = http.value(forHTTPHeaderField: "Content-Range") ?? "-"
      let declared = resolveContentType(mime: ctype, pathExtension: target.pathExtension)
      DLog("stream", "#\(requestCount) 状态=\(http.statusCode) 类型=\(ctype) 区间=\(crange) 声明=\(declared)")
    }

    guard (200...299).contains(http.statusCode) else {
      // 403 是最常见的：CDN 不认我们的头，或者直链过期了
      DLog("stream", "✗ 拉流被拒 状态=\(http.statusCode) range=\(range)")
      loadingRequest.finishLoading(with: NSError(
        domain: "TimbreStream",
        code: http.statusCode,
        userInfo: [
          NSLocalizedDescriptionKey:
            "音频服务器返回 HTTP \(http.statusCode)，直链可能已过期或需要额外的请求头"
        ]
      ))
      return
    }

    if let total = Self.totalLength(from: http) {
      contentLength = total
    }
    if let information = loadingRequest.contentInformationRequest {
      information.isByteRangeAccessSupported = true
      information.contentType = resolveContentType(
        mime: http.value(forHTTPHeaderField: "Content-Type"),
        pathExtension: target.pathExtension
      )
      if let length = contentLength {
        information.contentLength = length
      }
    }

    guard let data, !data.isEmpty else {
      // 元信息请求到这里就够了
      loadingRequest.finishLoading()
      return
    }

    if let dataRequest = loadingRequest.dataRequest {
      if http.statusCode == 200, wantOffset > 0 || !toEnd {
        // 服务器忽略了 Range，回的是整份文件 —— 自己切出这一轮要的区间
        if wantOffset < Int64(data.count) {
          let end = toEnd
            ? Int64(data.count)
            : min(Int64(data.count), wantOffset + max(wantLength, 1))
          dataRequest.respond(with: data.subdata(in: Int(wantOffset)..<Int(end)))
        }
      } else {
        dataRequest.respond(with: data)
      }
    }
    loadingRequest.finishLoading()
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
    // 音轨优先按 m4a 声明（com.apple.m4a-audio），AVFoundation 会走音频解析路径；
    // variant 1 时退回 public.mpeg-4，两层里总有一个能过。
    if contentTypeVariant == 1 { return AVFileType.mp4.rawValue }
    if ext == "mp4" || lower.contains("video/mp4") { return AVFileType.mp4.rawValue }
    return AVFileType.m4a.rawValue
  }
}
