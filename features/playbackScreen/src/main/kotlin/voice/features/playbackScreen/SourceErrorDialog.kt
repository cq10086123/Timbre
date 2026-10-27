package voice.features.playbackScreen

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import voice.core.strings.R as StringsR

/**
 * 取流失败时把源自己给的原文摆出来。
 *
 * 不用 snackbar：源返回的常常是一整句业务话术（"同一IP每 30 秒仅可请求 1 次
 * 音频，请 26 秒后再试…"），底部一闪而过既读不完也没法复制。这里照搬
 * [BookPlaySourceErrorViewState.detail]，只有源什么都没说时才退回到按
 * [BookPlaySourceErrorViewState.kind] 选出的兜底文案。
 */
@Composable
internal fun SourceErrorDialog(
  state: BookPlaySourceErrorViewState,
  fallbackMessage: String,
  onDismiss: () -> Unit,
) {
  val frameworkRes = state.detail?.let(::frameworkErrorRes)
  val raw = state.detail?.let(::rawSourceErrorText)?.takeIf(String::isNotEmpty)
  AlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
      TextButton(onClick = onDismiss) {
        Text(text = stringResource(id = StringsR.string.playback_online_error_dismiss))
      }
    },
    title = {
      Text(text = stringResource(id = StringsR.string.playback_online_error_title))
    },
    text = {
      // 正文可选可复制：排查源的问题时，用户要能把源的原话原封不动贴出来。
      // 高度上限 + 滚动，免得某个源把整页 HTML 当异常抛出来撑爆弹窗。
      SelectionContainer {
        Text(
          text = frameworkRes?.let { stringResource(it) } ?: raw ?: fallbackMessage,
          modifier = Modifier
            .heightIn(max = MAX_SOURCE_ERROR_HEIGHT)
            .verticalScroll(rememberScrollState()),
        )
      }
    },
  )
}

/**
 * 网络库自己抛的异常 message 不是源说的话，原文上屏只会让人看不懂 ——
 * - `timeout`：OkHttp 的 callTimeout 到点（SandboxHttp 默认 30 秒），服务器没把
 *   响应回完。这是 OkHttp 源码里写死的单词，不是接口返回的内容。
 * - `canceled`：请求完成前被取消（切章、预加载让路等）。
 *
 * 这类单词映射成本地化文案；其余 message（源的业务话术、QuickJS 报错等）
 * 一律原样透传，保持"源说什么就显示什么"。
 */
internal fun frameworkErrorRes(detail: String): Int? = when (detail.trim()) {
  "timeout" -> StringsR.string.playback_online_error_timeout
  "canceled" -> StringsR.string.playback_online_error_canceled
  else -> null
}

/**
 * 只剥掉包装层，正文一个字不改（换行也保留）。
 *
 * 剥两类东西：
 * - Java / QuickJS 的异常类型前缀。`com.dokar.quickjs.QuickJsException: Error: 未找到第 1 集`
 *   剥两层才是源抛的那句话。
 * - JS 的调用栈行（`    at audio (l.js:154:37)`）。那是 QuickJS 把 `Error.stack`
 *   接在 message 后面带出来的，属于框架噪音 —— 用户要看的是源说了什么，不是它在哪一行抛的。
 */
internal fun rawSourceErrorText(detail: String): String {
  var text = detail.trim()
  // 每轮至少去掉一个前缀字符，所以一定会停；剥到不再匹配为止。
  while (true) {
    val stripped = text.replace(FRAMEWORK_PREFIX, "").trim()
    if (stripped == text || stripped.isEmpty()) break
    text = stripped
  }
  return text.lines()
    .filterNot { JS_STACK_FRAME.containsMatchIn(it) }
    .joinToString("\n")
    .trim()
}

/** `Error: ...` / `java.io.IOException: ...` / `QuickJsException: ...` */
private val FRAMEWORK_PREFIX = Regex("^([\\w.$]*(Exception|Error|Failure|Throwable)|Error):\\s*")

/** `    at audio (l.js:154:37)` / `at <anonymous> (l.js:12)` */
private val JS_STACK_FRAME = Regex("^\\s*at\\s+[^\\s].*$")

/** 正文最大高度。超出的长文本在弹窗内滚动，而不是被截断。 */
private val MAX_SOURCE_ERROR_HEIGHT = 280.dp
