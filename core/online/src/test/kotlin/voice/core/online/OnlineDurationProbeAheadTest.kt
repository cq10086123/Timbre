package voice.core.online

import android.app.Application
import android.net.ConnectivityManager
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import voice.core.common.DispatcherProvider
import voice.core.data.BookId
import voice.core.data.ChapterId
import kotlin.test.Test

class OnlineDurationProbeAheadTest {

  // plain mpeg1 layer III 128 kbps frame, no Xing: duration comes from the
  // CBR fallback over the served total, so each chapter gets its own value
  private val head = byteArrayOf(
    0xFF.toByte(),
    0xFB.toByte(),
    0x90.toByte(),
    0x00,
    0,
    0,
    0,
    0,
  )
  private val totals = mapOf(
    "c2" to 1_000_000L,
    "c3" to 2_000_000L,
    "c4" to 3_000_000L,
    "c5" to 4_000_000L,
    "c6" to 5_000_000L,
    "c7" to 6_000_000L,
    "c8" to 7_000_000L,
  )

  private val httpClient = OkHttpClient.Builder()
    .addInterceptor { chain ->
      val name = chain.request().url.pathSegments.lastOrNull().orEmpty().removeSuffix(".mp3")
      val total = totals[name]
      if (total == null) {
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(404)
          .message("Not Found")
          .body(ByteArray(0).toResponseBody())
          .build()
      } else {
        Response.Builder()
          .request(chain.request())
          .protocol(Protocol.HTTP_1_1)
          .code(206)
          .message("Partial Content")
          .header("Content-Range", "bytes 0-7/$total")
          .body(head.toResponseBody("audio/mpeg".toMediaType()))
          .build()
      }
    }
    .build()

  private fun book() = OnlineBook(
    source = "A",
    bookId = "b",
    title = "t",
    chapters = (1..8).map { i ->
      OnlineChapter(id = "c$i", title = "第${i}集", durationSeconds = 0, order = i)
    },
  )

  private fun TestScope.probeAhead(
    catalog: OnlinePlaybackCatalog,
    metered: Boolean,
  ): OnlineDurationProbeAhead {
    val manager = mockk<ConnectivityManager> {
      every { isActiveNetworkMetered } returns metered
    }
    val context = mockk<Application> {
      every { getSystemService(ConnectivityManager::class.java) } returns manager
    }
    // all dispatchers explicit: DispatcherProvider defaults touch
    // Dispatchers.Main, which does not exist in plain JVM unit tests
    val dispatcherProvider = DispatcherProvider(
      io = UnconfinedTestDispatcher(testScheduler),
      main = UnconfinedTestDispatcher(testScheduler),
      mainImmediate = UnconfinedTestDispatcher(testScheduler),
    )
    return OnlineDurationProbeAhead(
      service = mockk {
        coEvery { resolveDirectUrl("A", "b", any()) } answers {
          "http://cdn/x/${thirdArg<String>()}.mp3"
        }
      },
      catalog = catalog,
      context = context,
      httpClient = httpClient,
      dispatcherProvider = dispatcherProvider,
    )
  }

  private fun bookId() = BookId(OnlineUri.buildBookUri("A", "b"))

  private fun chapterId(id: String) = ChapterId(OnlineUri.build("A", "b", id))

  @Test
  fun `measures the next unknown chapters after the current one`() = runTest {
    val catalog = mockk<OnlinePlaybackCatalog> {
      coEvery { lookupOnlineBook("A", "b") } returns book()
      every { measuredDurationMs(any(), any(), any()) } returns null
      every { recordMeasuredDuration(any(), any(), any(), any()) } just Runs
    }

    probeAhead(catalog, metered = false).probeUpcomingInternal(bookId(), chapterId("c1"))

    // (1_000_000 - 128) * 8 / 128 and (2_000_000 - 128) * 8 / 128
    verify { catalog.recordMeasuredDuration("A", "b", "c2", 62_492L) }
    verify { catalog.recordMeasuredDuration("A", "b", "c3", 124_992L) }
    verify { catalog.recordMeasuredDuration("A", "b", "c6", any()) }
    verify(exactly = 0) { catalog.recordMeasuredDuration("A", "b", "c1", any()) }
    verify(exactly = 0) { catalog.recordMeasuredDuration("A", "b", "c7", any()) }
    verify(exactly = 0) { catalog.recordMeasuredDuration("A", "b", "c8", any()) }
  }

  @Test
  fun `skips everything on metered networks`() = runTest {
    val catalog = mockk<OnlinePlaybackCatalog>()

    probeAhead(catalog, metered = true).probeUpcomingInternal(bookId(), chapterId("c1"))

    verify(exactly = 0) { catalog.recordMeasuredDuration(any(), any(), any(), any()) }
  }

  @Test
  fun `skips the main catalog`() = runTest {
    val catalog = mockk<OnlinePlaybackCatalog>()

    probeAhead(catalog, metered = false).probeUpcomingInternal(
      BookId(OnlineUri.buildBookUri("main", "b")),
      ChapterId(OnlineUri.build("main", "b", "c1")),
    )

    verify(exactly = 0) { catalog.recordMeasuredDuration(any(), any(), any(), any()) }
  }
}
