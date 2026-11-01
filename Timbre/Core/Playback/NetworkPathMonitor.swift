import Foundation
import Network

/// 网络类型监视：给「大文件后台预下载」把关。
///
/// DASH 长音频一章动辄 100MB+，蜂窝网络下自动预下载等于偷跑用户流量，
/// 所以只有确认是 Wi-Fi（非蜂窝、非受限）才允许。路径回调几乎立刻到达，
/// 但在收到第一次回调之前按「不允许」处理，宁可少预取也不冒险。
final class NetworkPathMonitor {
  static let shared = NetworkPathMonitor()

  private let monitor = NWPathMonitor()
  private let lock = NSLock()
  private var known = false
  private var expensive = true

  private init() {
    monitor.pathUpdateHandler = { [weak self] path in
      guard let self else { return }
      self.lock.lock()
      self.known = true
      // isExpensive = 蜂窝 / Personal Hotspot；isConstrained = 低数据模式
      self.expensive = path.isExpensive || path.isConstrained
      self.lock.unlock()
      DLog("preload", "网络判定 \(self.expensive ? "蜂窝/受限，预下载跳过" : "Wi-Fi，允许预下载")")
    }
    monitor.start(queue: DispatchQueue(label: "app.timbre.netmon"))
  }

  /// 当前是否允许大文件后台预下载
  var allowBulkDownload: Bool {
    lock.lock()
    defer { lock.unlock() }
    return known && !expensive
  }
}
