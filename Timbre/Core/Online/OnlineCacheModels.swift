import Foundation

/// 缓存窗口里某一集的状态，画成进度图上的一个方块（对齐安卓 `OnlineCacheMark`）
enum OnlineCacheMark: Hashable {
  /// 还没轮到
  case pending
  /// 正在下
  case running
  /// 完整落盘
  case cached
  /// 试过，失败了
  case failed
}

/// 一集为什么没缓存下来（对齐安卓 `OnlineCacheFailureReason`）
enum OnlineCacheFailureReason: Hashable {
  /// 书源没给出可播的地址
  case resolve
  /// 传输断了（网络错误、超时、被拒）
  case download
  /// 服务器回的是网页或 json，不是音频
  case notAudio
  /// 传完了但没达到声明的长度
  case incomplete

  var text: String {
    switch self {
    case .resolve: return "解析播放地址失败"
    case .download: return "下载失败：网络中断或服务器拒绝"
    case .notAudio: return "返回的不是音频，链接可能已失效"
    case .incomplete: return "文件不完整，连接提前断开"
    }
  }
}

/// 一集失败：原因 + 书源/服务器到底说了什么
struct OnlineCacheFailure: Identifiable, Hashable {
  var id: String { "\(index)-\(chapterOrder)-\(reasonKey)" }

  var title: String
  var reason: OnlineCacheFailureReason
  /// 书里的第几集（1 起）；书源没编号时为 0
  var chapterOrder: Int = 0
  /// 在缓存窗口里的下标，进度图靠它找回对应方块
  var index: Int = 0
  /// 书源或服务器原话（业务提示、异常、HTTP 状态），没说话就是 nil
  var detail: String?

  private var reasonKey: String {
    switch reason {
    case .resolve: return "resolve"
    case .download: return "download"
    case .notAudio: return "notAudio"
    case .incomplete: return "incomplete"
    }
  }
}

/// 一次缓存运行为什么结束 —— 对话框要能解释清楚，而不是只说「停了」
enum OnlineCacheStopReason: Hashable {
  /// 窗口里的每一集都在设备上了
  case completed
  /// 连续太多集失败，书源多半在限流
  case tooManyFailures
  /// 章节列表根本拿不到
  case noChapterList
  /// 用户没允许用移动数据
  case meteredDeclined
  /// 连续几次启动一集都没缓存成功
  case gaveUp
  /// 出了意外
  case error
}

/// 一次手动缓存任务的进度（对齐安卓 `OnlineCacheState`）
struct OnlineCacheState {
  var total: Int
  var done: Int
  var failed: Int
  var downloading: Bool
  var currentTitle: String = ""
  /// 任务还活着，但在等用户允许用移动数据
  var awaitingConfirmation: Bool = false
  /// 当前这一集已经写下的字节
  var currentBytes: Int64 = 0
  /// 当前这一集的大小；服务器还没声明就是 -1
  var currentTotalBytes: Int64 = -1
  /// 当前这一集的平滑速率，未知时 0
  var bytesPerSecond: Int64 = 0
  /// 窗口里每集一个标记，顺序一致
  var marks: [OnlineCacheMark] = []
  /// 失败的集，最多列 `maxListedFailures` 条
  var failures: [OnlineCacheFailure] = []
  /// 运行结束后才有值
  var stopReason: OnlineCacheStopReason?

  var progressFraction: Double {
    guard total > 0 else { return 0 }
    return min(1, max(0, Double(done) / Double(total)))
  }
}

/// 缓存对话框顶部那两行：书名 + 已经存了多少（对齐安卓 `OnlineCachedInfo`）
struct OnlineCachedInfo {
  var bookId: String
  var title: String
  var totalChapters: Int
  /// 从第几集开始缓存（0 起，当前在听的那一集）
  var currentIndex: Int
  var cachedCount: Int
  var cachedBytes: Int64
}

/// 一次想花流量的任务，等用户点头（对齐安卓 `OnlineCacheConfirmation`）
struct OnlineCacheConfirmation: Identifiable {
  var id: String { bookId }
  var bookId: String
  var title: String
  /// 这次想缓存的集数
  var chapters: Int
}

// MARK: - 常量（与安卓 OnlineBookCacheManager 一致）

enum OnlineCacheLimits {
  /// 单次手动缓存上限
  static let maxManualCache = 10_000
  /// 每集间隔上限 5 分钟：打错一个数字不该把缓存卡几小时
  static let maxEpisodeDelaySeconds = 300
  /// 连续失败这么多集就收手，死网络别一集集磨完
  static let abortAfterConsecutiveFailures = 5
  /// 连续几次启动一集都没下成，就放弃这个任务
  static let maxResumeAttempts = 3
  /// 进度图只画窗口前这么多集
  static let maxMarks = 240
  /// 失败明细最多列这么多条
  static let maxListedFailures = 12
  /// 失败原文最多留这么多字
  static let maxFailureDetailCharacters = 400

  static let defaultCacheCount = 100
  static let countPresets = [50, 100, 200, 500]
  static let defaultDelaySeconds = 5
  static let delayPresets = [0, 2, 5, 10]
}
