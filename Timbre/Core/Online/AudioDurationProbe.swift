import AVFoundation
import Foundation

/// 问一条音频直链「这一集有多长」。
///
/// 直链只是地址，里面不含时长，必须真的向服务器要一次文件头：
/// `AVURLAsset` 读 mp3 的 Xing/ID3 或 m4a 的 moov，通常一次 Range 请求就够。
/// 代价是每集多一次请求，所以只在章节还没记录时长时调用（`Chapter.duration <= 0`）。
///
/// 拿不到是正常情况：DASH 分片（`.m4s`）的索引散在 moof 里，
/// 本工程的播放器也解不出可靠时长（见 `PlaybackEngine.installResolved`），
/// 这类源探测会超时或返回 0，交给调用方按「不知道时长」处理。
enum AudioDurationProbe {

  /// 与 `PlaybackEngine` 里同一个键：iOS SDK 没把 `AVURLAssetHTTPHeaderFieldsKey`
  /// 导出成符号，只能按文档里的字符串传
  private static let httpHeaderFieldsKey = "AVURLAssetHTTPHeaderFieldsKey"

  /// 探测超时。源站慢的时候别让预加载链条卡在这里，这一集按未知处理。
  private static let timeout: TimeInterval = 8

  static func duration(url: URL, headers: [String: String]) async -> Double? {
    let probe = Task<Double?, Never> { () -> Double? in
      let asset =
        headers.isEmpty
        ? AVURLAsset(url: url)
        : AVURLAsset(url: url, options: [httpHeaderFieldsKey: headers])
      do {
        let value = try await asset.load(.duration)
        let seconds = value.seconds
        guard seconds.isFinite, seconds > 1 else { return nil }
        return seconds
      } catch {
        DLog("preload", "时长探测失败 \(url.host ?? "-")\(url.path)：\(error.localizedDescription)")
        return nil
      }
    }
    let watchdog = Task {
      try? await Task.sleep(nanoseconds: UInt64(timeout * 1_000_000_000))
      probe.cancel()
    }
    let result = await probe.value
    watchdog.cancel()
    return result
  }
}
