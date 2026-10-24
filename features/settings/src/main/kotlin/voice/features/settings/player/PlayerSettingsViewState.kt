package voice.features.settings.player

data class PlayerSettingsViewState(
  val onlinePreloadEnabled: Boolean,
  val onlinePreloadTriggerSeconds: Int,
  val onlinePreloadChapterCount: Int,
  val onlinePreloadIntervalSeconds: Int,
  val seekTimeInSeconds: Int,
  val autoRewindInSeconds: Int,
  val btSkipToChapter: Boolean,
  val dialog: Dialog?,
) {
  enum class Dialog {
    OnlinePreloadTrigger,
    OnlinePreloadCount,
    OnlinePreloadInterval,
    SeekTime,
    AutoRewindAmount,
  }
}
