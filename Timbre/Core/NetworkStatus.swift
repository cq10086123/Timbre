import Foundation
import Network

/// 粗粒度的联网状态：区分「离线」和「服务器出错」（对齐安卓 hasNetwork），
/// 以及书架自动刷新时的「仅 Wi-Fi」网络策略（对齐安卓 `autoRefreshWifi`）。
enum NetworkStatus {
  private static let monitor = NWPathMonitor()
  private static let queue = DispatchQueue(label: "timbre.network.monitor")
  private static let lock = NSLock()
  private static var state: NWPath.Status = .requiresConnection
  private static var wifi = false
  /// 走的是计量网络（蜂窝 / 热点）—— 对齐安卓 MeteredNetworkChecker
  private static var expensive = false
  /// NWPathMonitor 第一次回报之前 `wifi` 只是默认值，不能拿来判断
  private static var settled = false
  private static var started = false

  static var isOnline: Bool {
    ensureStarted()
    lock.lock()
    let current = state
    lock.unlock()
    // 刚启动时拿不到结论，按「在线」处理，交给请求本身报错
    return current != .unsatisfied
  }

  /// 当前是否走 Wi-Fi
  static var isWiFi: Bool {
    ensureStarted()
    lock.lock()
    defer { lock.unlock() }
    return wifi
  }

  /// 当前是不是计量网络。首次回报之前按 false 处理 —— 宁可多下一次，也不想
  /// 在 Wi-Fi 上凭空弹一个「要用流量吗」的对话框。
  static var isExpensive: Bool {
    ensureStarted()
    lock.lock()
    defer { lock.unlock() }
    return settled && expensive && !wifi
  }

  /// 冷启动时 NWPathMonitor 要过一会儿才回报，直接读 `isWiFi` 会拿到默认的 false，
  /// 于是明明在 Wi-Fi 上也会被「仅 Wi-Fi 下自动刷新」跳过。这里等首个判定。
  static func waitForFirstVerdict(timeout: TimeInterval = 1.5) async {
    ensureStarted()
    let deadline = Date().addingTimeInterval(timeout)
    while Date() < deadline {
      lock.lock()
      let done = settled
      lock.unlock()
      if done { return }
      try? await Task.sleep(nanoseconds: 100_000_000)
    }
  }

  private static func ensureStarted() {
    lock.lock()
    let needStart = !started
    if needStart { started = true }
    lock.unlock()
    guard needStart else { return }
    monitor.pathUpdateHandler = { path in
      lock.lock()
      state = path.status
      wifi = path.usesInterfaceType(.wifi)
      expensive = path.isExpensive
      settled = true
      lock.unlock()
    }
    monitor.start(queue: queue)
  }
}
