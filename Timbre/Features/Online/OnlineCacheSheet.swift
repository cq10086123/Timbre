import SwiftUI

/// 在线书「缓存书籍」抽屉 —— 对齐安卓 `OnlineCacheDialog` / `OnlineCacheSheetContent`。
///
/// 从上到下：书名 + 已缓存多少、运行状态卡（状态点、总进度、当前集、章节方块图、
/// 失败明细）、缓存设置（集数 / 每集间隔）、底部主按钮与清除 / 关闭。
struct OnlineCacheSheet: View {
  @EnvironmentObject private var cache: OnlineCacheManager

  var book: Book
  var onClose: () -> Void

  @State private var info: OnlineCachedInfo?
  @State private var loading = true

  @State private var selectedCount = OnlineCacheLimits.defaultCacheCount
  @State private var customCount = ""
  @State private var delaySeconds = OnlineCacheLimits.defaultDelaySeconds
  @State private var customDelay = ""

  @State private var clearArmed = false
  @State private var showCountMenu = false
  @State private var showDelayMenu = false
  @State private var editingTarget: EditTarget?
  @State private var editorText = ""
  @State private var openedFailure: OnlineCacheFailure?
  @State private var pulse = false

  private enum EditTarget: Identifiable {
    case count
    case delay

    var id: Int {
      switch self {
      case .count: return 0
      case .delay: return 1
      }
    }
  }

  private let sidePadding: CGFloat = 20

  private var progress: OnlineCacheState? { cache.state(for: book.id) }
  private var downloading: Bool { progress?.downloading == true }

  /// 还能缓存多少集（含当前章）
  private var remaining: Int {
    guard let info, info.totalChapters > 0 else { return 0 }
    return max(0, info.totalChapters - info.currentIndex)
  }

  private var requestedCount: Int {
    if let value = Int(customCount), value > 0 { return value }
    return selectedCount
  }

  private var plannedCount: Int {
    remaining > 0 ? min(requestedCount, remaining) : requestedCount
  }

  var body: some View {
    ZStack {
      AppColor.background.ignoresSafeArea()

      VStack(spacing: 0) {
        header
        ScrollView {
          VStack(alignment: .leading, spacing: 12) {
            if loading {
              HStack {
                Spacer()
                ProgressView().tint(AppColor.primary)
                Spacer()
              }
              .padding(.vertical, 24)
            } else if info == nil {
              Text("读取缓存信息失败")
                .font(AppFont.bodyMedium())
                .foregroundColor(AppColor.onSurfaceVariant)
            } else {
              if let progress {
                statusCard(progress)
              }
              // 任务跑着的时候设置项只会积灰：抽屉留短，让实时状态占住位置
              if !downloading {
                settingsSection
              }
            }
          }
          .padding(.horizontal, sidePadding)
          .padding(.top, 18)
          .padding(.bottom, 8)
        }
        footer
      }

      if let confirmation = cache.meteredConfirmation, confirmation.bookId == book.id {
        meteredDialog(confirmation)
      }

      if let failure = openedFailure {
        failureDialog(failure)
      }

      if editingTarget != nil {
        M3TextFieldDialog(
          title: editingTarget == .count ? "自定义集数" : "自定义间隔（秒）",
          label: editingTarget == .count ? "集数" : "秒",
          text: $editorText,
          confirmTitle: "确定",
          dismissTitle: "取消",
          onConfirm: { confirmEditor() },
          onDismiss: { editingTarget = nil }
        )
      }
    }
    .task { await reload() }
    .onChange(of: downloading) { _ in
      // 一轮跑完后「已缓存多少」变了，重读一遍
      Task { await reload() }
    }
    .onAppear {
      withAnimation(.easeInOut(duration: 0.75).repeatForever(autoreverses: true)) {
        pulse = true
      }
    }
  }

  // MARK: - 顶部

  private var header: some View {
    VStack(alignment: .leading, spacing: 6) {
      Text(info?.title.isEmpty == false ? (info?.title ?? book.title) : "缓存书籍")
        .font(AppFont.titleMedium())
        .foregroundColor(AppColor.onSurface)
        .lineLimit(2)
      if let info, info.totalChapters > 0 {
        Text("已缓存 \(info.cachedCount) / \(info.totalChapters) 集 · \(Format.bytes(info.cachedBytes))")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal, sidePadding)
    .padding(.top, 16)
  }

  // MARK: - 状态卡

  private func statusCard(_ state: OnlineCacheState) -> some View {
    card {
      VStack(alignment: .leading, spacing: 12) {
        HStack(spacing: 8) {
          Circle()
            .fill(statusColor(state))
            .frame(width: 8, height: 8)
          Text(statusText(state))
            .font(AppFont.titleSmall())
            .foregroundColor(statusColor(state))
        }

        // 拿不到章节列表时根本没有可量进度，0% 的条只会谎称它在跑
        if state.total > 0 && (!state.marks.isEmpty || state.downloading) {
          M3ProgressBar(
            fraction: state.progressFraction,
            track: AppColor.outlineVariant.opacity(0.35),
            fill: AppColor.primary,
            height: 6
          )
          HStack(spacing: 0) {
            Text("本次 \(state.done) / \(state.total) 集")
              .font(AppFont.bodySmall())
              .foregroundColor(AppColor.onSurfaceVariant)
            Spacer(minLength: 8)
            Text("\(Int((state.progressFraction * 100).rounded(.down)))%")
              .font(AppFont.titleLarge())
              .foregroundColor(AppColor.onSurface)
          }
        }

        if state.downloading {
          currentChapter(state)
        }

        if !state.marks.isEmpty {
          chapterMap(state)
        }

        if !state.failures.isEmpty {
          Rectangle()
            .fill(AppColor.outlineVariant.opacity(0.4))
            .frame(height: 1)
          failureList(state)
        }
      }
      .padding(16)
    }
  }

  /// 正在下的一集：标题、本集进度、速率
  private func currentChapter(_ state: OnlineCacheState) -> some View {
    VStack(alignment: .leading, spacing: 6) {
      Text("正在下载：\(state.currentTitle)")
        .font(AppFont.bodyMedium())
        .foregroundColor(AppColor.onSurface)
        .lineLimit(2)
      if state.currentTotalBytes > 0 {
        M3ProgressBar(
          fraction: Double(state.currentBytes) / Double(state.currentTotalBytes),
          track: AppColor.outlineVariant.opacity(0.35),
          fill: AppColor.primary,
          height: 4
        )
      } else {
        M3ProgressBar(
          fraction: 0,
          track: AppColor.outlineVariant.opacity(0.35),
          fill: AppColor.primary,
          height: 4
        )
      }
      Text(chapterLine(state))
        .font(AppFont.bodySmall())
        .foregroundColor(AppColor.onSurfaceVariant)
    }
  }

  /// 一集一个方块：已缓存 / 待缓存（在下的那格会呼吸）/ 失败
  private func chapterMap(_ state: OnlineCacheState) -> some View {
    let failedByIndex = Dictionary(
      state.failures.map { ($0.index, $0) },
      uniquingKeysWith: { first, _ in first }
    )
    return VStack(alignment: .leading, spacing: 10) {
      LazyVGrid(
        columns: [GridItem(.adaptive(minimum: 12, maximum: 12), spacing: 3)],
        alignment: .leading,
        spacing: 3
      ) {
        ForEach(Array(state.marks.enumerated()), id: \.offset) { pair in
          cell(pair.element, failure: failedByIndex[pair.offset])
        }
      }
      HStack(spacing: 14) {
        legendItem(color: AppColor.primary, text: "已缓存")
        legendItem(color: AppColor.outlineVariant, text: "待缓存")
        legendItem(color: AppColor.error, text: "失败")
      }
      if state.total > state.marks.count {
        Text("图中显示前 \(state.marks.count) 集，本次共 \(state.total) 集")
          .font(AppFont.labelSmall())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
    }
  }

  private func cell(_ mark: OnlineCacheMark, failure: OnlineCacheFailure?) -> some View {
    let color: Color
    switch mark {
    case .cached: color = AppColor.primary
    case .failed: color = AppColor.error
    // 正在下的一格画成会呼吸的待缓存，同一时刻只有一集，不需要单独图例
    case .running: color = AppColor.primary.opacity(pulse ? 0.3 : 1)
    case .pending: color = AppColor.outlineVariant
    }
    return RoundedRectangle(cornerRadius: 3, style: .continuous)
      .fill(color)
      .frame(width: 12, height: 12)
      .contentShape(Rectangle())
      .onTapGesture {
        if let failure { openedFailure = failure }
      }
  }

  private func legendItem(color: Color, text: String) -> some View {
    HStack(spacing: 5) {
      RoundedRectangle(cornerRadius: 2, style: .continuous)
        .fill(color)
        .frame(width: 8, height: 8)
      Text(text)
        .font(AppFont.labelSmall())
        .foregroundColor(AppColor.onSurfaceVariant)
    }
  }

  /// 「3 集失败」不可操作，「书源说链接过期了」才可以 —— 所以带上原因，点开看原文
  private func failureList(_ state: OnlineCacheState) -> some View {
    VStack(alignment: .leading, spacing: 8) {
      Text("失败明细（\(state.failed) 集）")
        .font(AppFont.labelLarge())
        .foregroundColor(AppColor.error)
      ForEach(state.failures) { failure in
        Button {
          openedFailure = failure
        } label: {
          HStack(spacing: 0) {
            VStack(alignment: .leading, spacing: 2) {
              Text(failureTitle(failure))
                .font(AppFont.bodyMedium())
                .foregroundColor(AppColor.onSurface)
                .lineLimit(2)
                .multilineTextAlignment(.leading)
              Text(failure.reason.text)
                .font(AppFont.bodySmall())
                .foregroundColor(AppColor.error)
            }
            Spacer(minLength: 8)
            Text("查看")
              .font(AppFont.labelMedium())
              .foregroundColor(AppColor.primary)
          }
          .padding(.horizontal, 12)
          .padding(.vertical, 10)
          .frame(maxWidth: .infinity)
          .background(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
              .fill(AppColor.surfaceContainerHighest)
          )
        }
        .buttonStyle(.plain)
      }
      let hidden = state.failed - state.failures.count
      if hidden > 0 {
        Text("另有 \(hidden) 集失败未列出")
          .font(AppFont.labelSmall())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
    }
  }

  // MARK: - 设置

  private var settingsSection: some View {
    VStack(alignment: .leading, spacing: 12) {
      Text("缓存设置")
        .font(AppFont.labelLarge())
        .foregroundColor(AppColor.onSurfaceVariant)
        .padding(.leading, 4)

      card {
        VStack(spacing: 0) {
          settingRow(
            label: "缓存集数",
            value: countSummary,
            hint: nil
          ) { showCountMenu = true }
          .confirmationDialog(
            "缓存集数",
            isPresented: $showCountMenu,
            titleVisibility: .visible
          ) {
            ForEach(countOptions, id: \.self) { option in
              Button(countLabel(option)) {
                selectedCount = option
                customCount = ""
              }
            }
            Button("自定义…") {
              editorText = customCount.isEmpty ? "\(selectedCount)" : customCount
              editingTarget = .count
            }
            Button("取消", role: .cancel) {}
          }

          Rectangle()
            .fill(AppColor.outlineVariant.opacity(0.3))
            .frame(height: 1)

          settingRow(
            label: "每集间隔",
            value: delaySummary,
            hint: "每集下载完成后等待几秒再下下一集，降低连续下载被限流的失败率"
          ) { showDelayMenu = true }
          .confirmationDialog(
            "每集间隔",
            isPresented: $showDelayMenu,
            titleVisibility: .visible
          ) {
            ForEach(OnlineCacheLimits.delayPresets, id: \.self) { option in
              Button(option == 0 ? "不延时" : "\(option) 秒") {
                delaySeconds = option
                customDelay = ""
              }
            }
            Button("自定义…") {
              editorText = customDelay.isEmpty ? "\(delaySeconds)" : customDelay
              editingTarget = .delay
            }
            Button("取消", role: .cancel) {}
          }
        }
        .padding(.horizontal, 16)
      }

      VStack(alignment: .leading, spacing: 4) {
        if let info, info.currentIndex > 0 {
          Text("从当前章节（第 \(info.currentIndex + 1) 集）开始缓存")
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
        Text("已缓存的可离线收听，未缓存的继续在线播放。")
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.onSurfaceVariant)
      }
      .padding(.leading, 4)
    }
  }

  /// 管理器会把运行截到「还剩多少集」，菜单也不能给一本只剩 20 集的书摆 500
  private var countOptions: [Int] {
    if remaining <= 0 { return OnlineCacheLimits.countPresets }
    let preset = OnlineCacheLimits.countPresets.filter { $0 < remaining }
    return (preset + [remaining]).sorted()
  }

  private func countLabel(_ option: Int) -> String {
    if remaining > 0 && option == remaining { return "全部" }
    return "\(option) 集"
  }

  private var countSummary: String {
    if let value = Int(customCount), value > 0 { return "\(value) 集" }
    // 剩 20 集时选 100 仍然是「全部」，不是「100」
    if remaining > 0 && selectedCount >= remaining { return "全部" }
    return "\(selectedCount) 集"
  }

  private var delaySummary: String {
    if let value = Int(customDelay) {
      return value == 0 ? "不延时" : "\(value) 秒"
    }
    return delaySeconds == 0 ? "不延时" : "\(delaySeconds) 秒"
  }

  private func settingRow(
    label: String,
    value: String,
    hint: String?,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 0) {
        VStack(alignment: .leading, spacing: 2) {
          Text(label)
            .font(AppFont.bodyLarge())
            .foregroundColor(AppColor.onSurface)
          if let hint {
            Text(hint)
              .font(AppFont.labelSmall())
              .foregroundColor(AppColor.onSurfaceVariant)
              .lineLimit(3)
              .multilineTextAlignment(.leading)
              .padding(.trailing, 12)
          }
        }
        Spacer(minLength: 8)
        Text(value)
          .font(AppFont.titleSmall())
          .foregroundColor(AppColor.primary)
        Text("›")
          .font(AppFont.titleMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
          .padding(.leading, 6)
      }
      .padding(.vertical, 16)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  // MARK: - 底部

  private var footer: some View {
    VStack(spacing: 0) {
      Rectangle()
        .fill(AppColor.outlineVariant.opacity(0.4))
        .frame(height: 1)
      VStack(spacing: 2) {
        Button {
          if downloading {
            cache.cancel(bookId: book.id)
          } else {
            startCache()
          }
        } label: {
          Text(primaryTitle)
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onPrimary)
            .frame(maxWidth: .infinity)
            .frame(height: 50)
            .background(
              RoundedRectangle(cornerRadius: 16, style: .continuous)
                .fill(primaryEnabled ? AppColor.primary : AppColor.primary.opacity(0.4))
            )
        }
        .buttonStyle(.plain)
        .disabled(!primaryEnabled)

        HStack(spacing: 0) {
          Button {
            clearCache()
          } label: {
            Text(clearArmed ? "再点一次清除" : "清除缓存")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.error.opacity(clearEnabled ? 1 : 0.4))
              .padding(.horizontal, 12)
              .frame(height: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
          .disabled(!clearEnabled)

          Spacer(minLength: 8)

          Button {
            onClose()
          } label: {
            Text("关闭")
              .font(AppFont.labelLarge())
              .foregroundColor(AppColor.primary)
              .padding(.horizontal, 12)
              .frame(height: 40)
              .contentShape(Rectangle())
          }
          .buttonStyle(.plain)
        }
      }
      .padding(.horizontal, sidePadding)
      .padding(.top, 12)
      .padding(.bottom, 8)
    }
    .background(AppColor.surfaceContainerLow)
  }

  private var primaryTitle: String {
    if downloading { return "停止缓存" }
    if let reason = progress?.stopReason, needsRetry(reason) { return "重试" }
    if remaining <= 0 { return "开始缓存" }
    return "缓存 \(plannedCount) 集"
  }

  private var primaryEnabled: Bool {
    if downloading { return true }
    return !loading && info != nil && remaining > 0
  }

  private var clearEnabled: Bool {
    downloading || (info?.cachedCount ?? 0) > 0
  }

  // MARK: - 动作

  private func reload() async {
    loading = true
    let fresh = await cache.cachedInfo(for: book)
    info = fresh
    loading = false
    if clearArmed && fresh.cachedCount == 0 { clearArmed = false }
  }

  private func startCache() {
    clearArmed = false
    cache.cacheUpcoming(
      book: book,
      count: plannedCount,
      delaySeconds: Int(customDelay) ?? delaySeconds
    )
  }

  private func clearCache() {
    if !clearArmed {
      clearArmed = true
      return
    }
    clearArmed = false
    Task {
      _ = await cache.clearBook(book)
      await reload()
    }
  }

  private func confirmEditor() {
    let digits = editorText.filter { $0.isNumber }
    if let target = editingTarget {
      switch target {
      case .count:
        customCount = String(digits.prefix(5))
      case .delay:
        let value = Int(digits) ?? delaySeconds
        customDelay = "\(min(max(0, value), OnlineCacheLimits.maxEpisodeDelaySeconds))"
      }
    }
    editingTarget = nil
  }

  // MARK: - 文案与颜色

  private func statusText(_ state: OnlineCacheState) -> String {
    if state.awaitingConfirmation { return "已暂停：等待确认是否使用流量缓存" }
    if state.downloading { return "正在缓存" }
    switch state.stopReason {
    case .completed: return "本次缓存已全部完成"
    case .tooManyFailures: return "已停止：连续多集失败，书源可能限流或链接失效"
    case .noChapterList: return "已停止：拿不到章节列表，请检查网络或书源"
    case .meteredDeclined: return "已取消：未允许使用移动数据"
    case .gaveUp: return "已放弃：多次启动都没有缓存成功，请检查书源"
    case .error: return "已中断：出现意外错误，可重试"
    case nil: return "已停止"
    }
  }

  private func statusColor(_ state: OnlineCacheState) -> Color {
    if state.awaitingConfirmation { return AppColor.tertiary }
    if state.downloading { return AppColor.primary }
    if state.stopReason == .completed { return AppColor.primary }
    if state.stopReason == nil { return AppColor.onSurfaceVariant }
    return AppColor.error
  }

  /// 「本集 12.3 MB / 45.6 MB · 1.2 MB/s」，未知的部分省掉
  private func chapterLine(_ state: OnlineCacheState) -> String {
    let head: String
    if state.currentTotalBytes > 0 {
      head = "本集 \(Format.bytes(state.currentBytes)) / \(Format.bytes(state.currentTotalBytes))"
    } else if state.currentBytes > 0 {
      head = "本集已下载 \(Format.bytes(state.currentBytes))"
    } else {
      head = "正在读取本集大小…"
    }
    if state.bytesPerSecond <= 0 { return head }
    return "\(head) · \(Format.bytes(state.bytesPerSecond))/s"
  }

  private func needsRetry(_ reason: OnlineCacheStopReason) -> Bool {
    switch reason {
    case .tooManyFailures, .noChapterList, .gaveUp, .error: return true
    case .completed, .meteredDeclined: return false
    }
  }

  /// 多数书源的章节标题本身就写着「第38集」，前面再加一次会读成「第 38 集 · 第38集」
  private func failureTitle(_ failure: OnlineCacheFailure) -> String {
    guard failure.chapterOrder > 0 else { return failure.title }
    if Self.titleStatesChapter(failure.title, order: failure.chapterOrder) {
      return failure.title
    }
    return "第 \(failure.chapterOrder) 集 · \(failure.title)"
  }

  static func titleStatesChapter(_ title: String, order: Int) -> Bool {
    if order <= 0 { return false }
    if let wrapped = firstMatch(#"第\s*(\d+)\s*[集话章节期回]"#, in: title), wrapped == order {
      return false
    }
    if let bare = firstMatch(#"^0*(\d+)(?=$|[\s.、,:：\-·—])"#, in: title), bare == order {
      return false
    }
    return true
  }

  private static func firstMatch(_ pattern: String, in text: String) -> Int? {
    guard let regex = try? NSRegularExpression(pattern: pattern) else { return nil }
    let range = NSRange(text.startIndex..<text.endIndex, in: text)
    guard let match = regex.firstMatch(in: text, range: range), match.numberOfRanges > 1,
      let captured = Range(match.range(at: 1), in: text)
    else { return nil }
    return Int(text[captured])
  }

  // MARK: - 弹窗

  private func meteredDialog(_ confirmation: OnlineCacheConfirmation) -> some View {
    M3AlertDialog(title: "当前为流量", onDismiss: { cache.declineMetered() }) {
      VStack(alignment: .leading, spacing: 8) {
        Text("当前使用的是移动数据流量，继续缓存会消耗流量。")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
        Text("书籍：\(confirmation.title)")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
        Text("本次将缓存 \(confirmation.chapters) 集")
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurfaceVariant)
        HStack(spacing: 8) {
          Spacer(minLength: 0)
          Button("取消") { cache.declineMetered() }
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.primary)
            .padding(.horizontal, 12)
            .frame(height: 40)
            .buttonStyle(.plain)
          Button("继续缓存") { cache.confirmMetered() }
            .font(AppFont.labelLarge())
            .foregroundColor(AppColor.onPrimary)
            .padding(.horizontal, 20)
            .frame(height: 40)
            .background(
              RoundedRectangle(cornerRadius: 20, style: .continuous).fill(AppColor.primary)
            )
            .buttonStyle(.plain)
        }
        .padding(.top, 4)
      }
    }
  }

  /// 书源（或服务器）到底回了什么，原样、可滚动
  private func failureDialog(_ failure: OnlineCacheFailure) -> some View {
    M3AlertDialog(
      title: "缓存失败",
      actionTitle: "关闭",
      onAction: { openedFailure = nil },
      onDismiss: { openedFailure = nil }
    ) {
      VStack(alignment: .leading, spacing: 10) {
        Text(failureTitle(failure))
          .font(AppFont.bodyMedium())
          .foregroundColor(AppColor.onSurface)
        Text(failure.reason.text)
          .font(AppFont.bodySmall())
          .foregroundColor(AppColor.error)
        if let raw = failure.detail, !raw.isEmpty {
          Text("原始返回")
            .font(AppFont.labelMedium())
            .foregroundColor(AppColor.onSurfaceVariant)
          ScrollView {
            Text(raw)
              .font(.system(size: 12, design: .monospaced))
              .foregroundColor(AppColor.onSurface)
              .frame(maxWidth: .infinity, alignment: .leading)
              .textSelection(.enabled)
              .padding(12)
          }
          .frame(maxHeight: 220)
          .background(
            RoundedRectangle(cornerRadius: 12, style: .continuous)
              .fill(AppColor.surfaceContainerHighest)
          )
        } else {
          Text("书源没有返回更多信息。")
            .font(AppFont.bodySmall())
            .foregroundColor(AppColor.onSurfaceVariant)
        }
      }
    }
  }

  // MARK: - 卡片容器

  private func card<Content: View>(@ViewBuilder content: () -> Content) -> some View {
    content()
      .frame(maxWidth: .infinity)
      .background(
        RoundedRectangle(cornerRadius: 20, style: .continuous)
          .fill(AppColor.surfaceContainerHigh)
      )
      .overlay(
        RoundedRectangle(cornerRadius: 20, style: .continuous)
          .stroke(AppColor.outlineVariant.opacity(0.4), lineWidth: 1)
      )
  }
}
