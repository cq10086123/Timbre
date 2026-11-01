import AVFoundation
import Foundation

/// 在解码后的 PCM 上做两件事，对齐安卓的 `VolumeGainSetter` 与 `skipSilenceEnabled`：
///
/// 1. **音量提升**：安卓用系统的 `LoudnessEnhancer.setTargetGain(mB)`，上限 9 dB。
///    AVPlayer 的 `volume` 只能衰减到 1.0，超过 1.0 的增益必须在采样上乘，
///    所以走 `MTAudioProcessingTap`。
/// 2. **跳过空白部分**：安卓是 ExoPlayer 内置的 `SkipSilenceAudioProcessor`，
///    iOS 没有等价物。这里在 tap 里实时算峰值，连续静音超过阈值就置位，
///    由主线程轮询后向前 seek。
///
/// 关键取舍：**只在开关打开时才挂 tap**。默认两者都关，播放链路完全不受影响 ——
/// 刚修好 B 站直链，不能因为加了音频处理把能播的源拖坏。
final class AudioProcessor {

  /// 连续静音多久算「空白」，对齐 ExoPlayer 的观感（它按 100ms 窗口累计）
  private static let silenceHoldSeconds: Double = 1.2
  /// 峰值低于这个值就算静音，约 -46 dBFS
  private static let silenceThreshold: Float = 0.005

  /// 线性增益，1.0 = 不处理
  private var gainFactor: Float = 1
  private var skipSilenceEnabled = false

  private var sampleRate: Double = 0
  private let stateLock = NSLock()
  private var silentSeconds: Double = 0
  /// 给日志用：上次真正出声的时刻
  private var lastSoundAt = Date()

  // MARK: - 对外配置

  /// `decibels` 为 0 时等于关闭（对应安卓 `Decibel.Zero` 会直接 detach 效果）
  func setGain(decibels: Double) {
    let clamped = min(max(decibels, 0), 9)
    gainFactor = clamped <= 0.01 ? 1 : Float(pow(10, clamped / 20))
    stateLock.lock()
    silentSeconds = 0
    stateLock.unlock()
    DLog("audio", "增益设置 \(String(format: "%.1f", clamped))dB → ×\(String(format: "%.2f", gainFactor))")
  }

  func setSkipSilence(_ enabled: Bool) {
    skipSilenceEnabled = enabled
    stateLock.lock()
    silentSeconds = 0
    stateLock.unlock()
    DLog("audio", "跳过空白 \(enabled ? "开" : "关")")
  }

  /// 当前配置下值不值得挂 tap —— 都关着就别动播放链路
  var isActive: Bool {
    gainFactor != 1 || skipSilenceEnabled
  }

  // MARK: - 挂到 AVPlayerItem

  /// 给 item 装 audioMix。必须在 asset 的 audio 轨加载出来之后调用。
  func attach(to item: AVPlayerItem, asset: AVAsset) {
    guard isActive else { return }
    Task { [weak self] in
      guard let self else { return }
      guard let track = try? await asset.loadTracks(withMediaType: .audio).first else {
        DLog("audio", "拿不到音频轨，跳过音频处理")
        return
      }
      guard let tap = self.makeTap() else {
        DLog("audio", "MTAudioProcessingTap 创建失败，跳过音频处理")
        return
      }
      let parameters = AVMutableAudioMixInputParameters(track: track)
      parameters.audioTapProcessor = tap
      let mix = AVMutableAudioMix()
      mix.inputParameters = [parameters]
      await MainActor.run {
        item.audioMix = mix
        DLog(
          "audio",
          "音频处理已挂载 增益×\(String(format: "%.2f", self.gainFactor)) "
            + "跳空白=\(self.skipSilenceEnabled)"
        )
      }
    }
  }

  /// 主线程轮询：静音够久就返回 true，调用方据此向前跳
  func consumeSilenceJump() -> Bool {
    guard skipSilenceEnabled else { return false }
    stateLock.lock()
    defer { stateLock.unlock() }
    guard silentSeconds >= Self.silenceHoldSeconds else { return false }
    silentSeconds = 0
    return true
  }

  // MARK: - Tap 实现

  private func makeTap() -> MTAudioProcessingTap? {
    var callbacks = MTAudioProcessingTapCallbacks(
      version: kMTAudioProcessingTapCallbacksVersion_0,
      clientInfo: Unmanaged.passUnretained(self).toOpaque(),
      init: tapInit,
      finalize: tapFinalize,
      prepare: tapPrepare,
      unprepare: tapUnprepare,
      process: tapProcess
    )
    var tap: Unmanaged<MTAudioProcessingTap>?
    let status = MTAudioProcessingTapCreate(
      kCFAllocatorDefault,
      &callbacks,
      kMTAudioProcessingTapCreationFlag_PostEffects,
      &tap
    )
    guard status == noErr else { return nil }
    return tap?.takeRetainedValue()
  }

  fileprivate func prepare(format: AudioStreamBasicDescription) {
    sampleRate = format.mSampleRate > 0 ? format.mSampleRate : 44100
    stateLock.lock()
    silentSeconds = 0
    stateLock.unlock()
    DLog(
      "audio",
      "tap 就绪 采样率=\(Int(sampleRate)) 声道=\(format.mChannelsPerFrame) "
        + "位深=\(format.mBitsPerChannel)"
    )
  }

  /// 实时线程调用，别在这里做任何分配或加锁以外的重活
  fileprivate func render(_ bufferList: UnsafeMutablePointer<AudioBufferList>, frames: CMItemCount) {
    let abl = UnsafeMutableAudioBufferListPointer(bufferList)
    var peak: Float = 0
    let gain = gainFactor

    for buffer in abl {
      guard let raw = buffer.mData else { continue }
      let samples = raw.assumingMemoryBound(to: Float.self)
      let count = Int(buffer.mDataByteSize) / MemoryLayout<Float>.size
      if count <= 0 { continue }

      if gain != 1 {
        for i in 0..<count {
          // 硬限幅：+9dB 时语音峰值会冲破 1.0，削顶比整体压回去更保真
          var value = samples[i] * gain
          if value > 1 { value = 1 } else if value < -1 { value = -1 }
          samples[i] = value
        }
      }
      for i in 0..<count {
        let magnitude = abs(samples[i])
        if magnitude > peak { peak = magnitude }
      }
    }

    guard sampleRate > 0 else { return }
    let seconds = Double(frames) / sampleRate
    stateLock.lock()
    if peak < Self.silenceThreshold {
      silentSeconds += seconds
      // 静音累计到阈值就封顶，别让数值无限涨
      if silentSeconds > 30 { silentSeconds = 30 }
    } else {
      silentSeconds = 0
      lastSoundAt = Date()
    }
    stateLock.unlock()
  }
}

// MARK: - C 回调

private let tapInit: MTAudioProcessingTapInitCallback = { _, clientInfo, tapStorageOut in
  tapStorageOut.pointee = clientInfo
}

private let tapFinalize: MTAudioProcessingTapFinalizeCallback = { _ in }

private let tapPrepare: MTAudioProcessingTapPrepareCallback = { tap, _, format in
  let storage = MTAudioProcessingTapGetStorage(tap)
  let processor = Unmanaged<AudioProcessor>.fromOpaque(storage).takeUnretainedValue()
  processor.prepare(format: format.pointee)
}

private let tapUnprepare: MTAudioProcessingTapUnprepareCallback = { _ in }

private let tapProcess: MTAudioProcessingTapProcessCallback = {
  tap, numberFrames, _, bufferListInOut, numberFramesOut, flagsOut in
  let status = MTAudioProcessingTapGetSourceAudio(
    tap,
    numberFrames,
    bufferListInOut,
    flagsOut,
    nil,
    numberFramesOut
  )
  guard status == noErr else { return }
  let storage = MTAudioProcessingTapGetStorage(tap)
  let processor = Unmanaged<AudioProcessor>.fromOpaque(storage).takeUnretainedValue()
  processor.render(bufferListInOut, frames: numberFramesOut.pointee)
}
