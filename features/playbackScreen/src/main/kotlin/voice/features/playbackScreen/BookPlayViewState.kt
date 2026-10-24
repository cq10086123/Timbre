package voice.features.playbackScreen

import androidx.compose.runtime.Immutable
import voice.core.online.OnlinePlaybackErrorKind
import voice.core.playback.misc.Decibel
import voice.features.sleepTimer.SleepTimerViewState
import kotlin.time.Duration

@Immutable
data class BookPlayViewState(
  val chapterName: String?,
  val showPreviousNextButtons: Boolean,
  val title: String,
  val sleepTimerState: SleepTimerViewState,
  val playedTime: Duration,
  val duration: Duration,
  val playing: Boolean,
  val cover: String?,
  val skipSilence: Boolean,
  val loading: Boolean = false,
) {

  sealed interface SleepTimerViewState {
    data object Disabled : SleepTimerViewState

    sealed interface Enabled : SleepTimerViewState {
      data object WithEndOfChapter : Enabled

      @JvmInline
      value class WithDuration(val leftDuration: Duration) : Enabled
    }
  }

  init {
    require(duration > Duration.ZERO) {
      "Duration must be positive in $this"
    }
  }
}

internal sealed interface BookPlayDialogViewState {
  data class SpeedDialog(val speed: Float) : BookPlayDialogViewState {

    val maxSpeed: Float get() = if (speed < 2F) 2F else 3.5F
  }

  data class VolumeGainDialog(
    val gain: Decibel,
    val valueFormatted: String,
    val maxGain: Decibel,
  ) : BookPlayDialogViewState

  data class SkipDialog(
    val skipIntroSeconds: Long,
    val skipOutroSeconds: Long,
  ) : BookPlayDialogViewState

  data class SelectChapterDialog(
    val items: List<ItemViewState>,
    val ranges: List<RangeViewState> = emptyList(),
  ) : BookPlayDialogViewState {

    data class ItemViewState(
      val number: Int,
      val name: String,
      val active: Boolean,
      val time: String,
    )

    data class RangeViewState(
      val firstNumber: Int,
      val lastNumber: Int,
      val startIndex: Int,
      val containsCurrent: Boolean,
    )
  }

  @JvmInline
  value class SleepTimer(val viewState: SleepTimerViewState) : BookPlayDialogViewState
}

/**
 * 取流失败要弹给用户看的原文。
 *
 * 不同源的报错格式完全不一样（有的给 HTTP 状态码，有的给业务 msg，有的只说
 * 拿不到地址），所以 [detail] 一律照搬源脚本抛出的字符串，App 不替它们组织
 * 语言 —— 拼接、归类、改写成"这一章没有音频"都只会把真正的原因盖掉。
 * [kind] 只在源什么都没说（[detail] 为空）时用来选一句兜底文案。
 */
@Immutable
internal data class BookPlaySourceErrorViewState(
  val kind: OnlinePlaybackErrorKind,
  val detail: String? = null,
)
