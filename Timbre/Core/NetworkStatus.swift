import Foundation
import Network

/// 粗粒度的联网状态，只用来区分「离线」和「服务器出错」两种提示（对齐安卓 hasNetwork）。
enum NetworkStatus {
  private static let monitor = NWPathMonitor()
  private static let queue = DispatchQueue(label: "timbre.network.monitor")
  private static let lock = NSLock()
  private static var state: NWPath.Status = .requiresConnection
  private static var started = false

  static var isOnline: Bool {
    lock.lock()
    let running = started
    let current = state
    lock.unlock()

    if !running {
      lock.lock()
      if !started {
        started = true
        monitor.pathUpdateHandler = { path in
          lock.lock()
          state = path.status
          lock.unlock()
        }
        monitor.start(queue: queue)
      }
      lock.unlock()
    }
    // 刚启动时拿不到结论，按「在线」处理，交给请求本身报错
    return current != .unsatisfied
  }
}
