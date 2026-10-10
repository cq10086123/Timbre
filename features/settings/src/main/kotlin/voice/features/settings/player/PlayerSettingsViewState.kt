package voice.features.settings.player

data class PlayerSettingsViewState(
  val onlinePreloadEnabled: Boolean,
  val onlinePreloadTriggerSeconds: Int,
  val onlinePreloadChapterCount: Int,
  val onlinePreloadIntervalSeconds: Int,
  val dialog: Dialog?,
) {
  enum class Dialog {
    OnlinePreloadTrigger,
    OnlinePreloadCount,
    OnlinePreloadInterval,
  }
}
