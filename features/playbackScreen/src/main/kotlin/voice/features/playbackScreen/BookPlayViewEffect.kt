package voice.features.playbackScreen

import voice.core.online.OnlinePlaybackErrorKind

internal sealed interface BookPlayViewEffect {
  data object BookmarkAdded : BookPlayViewEffect
  data object RequestIgnoreBatteryOptimization : BookPlayViewEffect
  data class OnlineSourceError(val kind: OnlinePlaybackErrorKind) : BookPlayViewEffect
}
