package voice.core.webdav

import okhttp3.Authenticator
import okhttp3.Response
import okhttp3.Route
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small RFC 7616/RFC 2617 Digest authenticator for WebDAV servers.
 *
 * OkHttp deliberately does not ship a Digest authenticator. The first request
 * is still sent with the existing pre-emptive Basic header; when a server
 * answers with a Digest challenge this authenticator replaces it with the
 * challenge response and retries the same request. The password is held only
 * by this short-lived client and is obtained from [WebDavCredentialResolver].
 */
internal class WebDavDigestAuthenticator(
  private val username: String,
  private val password: String,
) : Authenticator {

  private val random = SecureRandom()
  private val nonceCount = AtomicInteger()

  override fun authenticate(route: Route?, response: Response): okhttp3.Request? {
    if (responseCount(response) >= MAX_ATTEMPTS) return null

    val challenge = response.headers.values("WWW-Authenticate")
      .asSequence()
      .map(::parseChallenge)
      .firstOrNull { it["scheme"]?.equals("digest", ignoreCase = true) == true }
      ?: return null

    val realm = challenge["realm"] ?: return null
    val nonce = challenge["nonce"] ?: return null
    val algorithm = challenge["algorithm"]?.uppercase(Locale.US) ?: "MD5"
    if (algorithm !in setOf("MD5", "MD5-SESS", "SHA-256", "SHA-256-SESS")) return null
    val hashAlgorithm = if (algorithm.endsWith("-SESS")) {
      algorithm.removeSuffix("-SESS")
    } else {
      algorithm
    }

    val qop = challenge["qop"]
      ?.split(',')
      ?.map { it.trim().lowercase(Locale.US) }
      ?.firstOrNull { it == "auth" || it == "auth-int" }
    val cnonce = randomCnonce()
    val nc = "%08x".format(Locale.US, nonceCount.incrementAndGet())
    val digestUri = buildString {
      append(response.request.url.encodedPath)
      response.request.url.encodedQuery?.let { append('?').append(it) }
    }
    val ha1Base = hashHex("$username:$realm:$password", hashAlgorithm)
    val ha1 = if (algorithm.endsWith("-SESS")) {
      hashHex("$ha1Base:$nonce:$cnonce", hashAlgorithm)
    } else {
      ha1Base
    }
    val ha2 = if (qop == "auth-int") {
      // WebDAV requests made by Timbre have no request body. An empty body is
      // therefore the correct entity hash for PROPFIND and ranged GET.
      val emptyEntityHash = hashHex("", hashAlgorithm)
      hashHex("${response.request.method}:$digestUri:$emptyEntityHash", hashAlgorithm)
    } else {
      hashHex("${response.request.method}:$digestUri", hashAlgorithm)
    }
    val result = if (qop == null) {
      hashHex("$ha1:$nonce:$ha2", hashAlgorithm)
    } else {
      hashHex("$ha1:$nonce:$nc:$cnonce:$qop:$ha2", hashAlgorithm)
    }

    val header = buildString {
      append("Digest ")
      append(parameter("username", username)).append(", ")
      append(parameter("realm", realm)).append(", ")
      append(parameter("nonce", nonce)).append(", ")
      append(parameter("uri", digestUri)).append(", ")
      append(parameter("response", result))
      if (algorithm.isNotEmpty()) append(", algorithm=$algorithm")
      challenge["opaque"]?.let { append(", ").append(parameter("opaque", it)) }
      qop?.let {
        append(", qop=$it")
        append(", nc=$nc")
        append(", ").append(parameter("cnonce", cnonce))
      }
    }

    return response.request.newBuilder()
      .header("Authorization", header)
      .build()
  }

  private fun responseCount(response: Response): Int {
    var count = 1
    var prior = response.priorResponse
    while (prior != null) {
      count++
      prior = prior.priorResponse
    }
    return count
  }

  private fun randomCnonce(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun hashHex(value: String, algorithm: String): String {
    return MessageDigest.getInstance(algorithm)
      .digest(value.toByteArray(Charsets.UTF_8))
      .joinToString(separator = "") { "%02x".format(it.toInt() and 0xff) }
  }

  private fun parameter(name: String, value: String): String {
    return "$name=\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
  }

  private fun parseChallenge(header: String): Map<String, String> {
    val firstSpace = header.indexOf(' ')
    if (firstSpace <= 0) return emptyMap()
    val scheme = header.substring(0, firstSpace).trim()
    if (!scheme.equals("digest", ignoreCase = true)) return emptyMap()
    val values = linkedMapOf("scheme" to scheme)
    val input = header.substring(firstSpace + 1)
    var index = 0
    while (index < input.length) {
      while (index < input.length && (input[index].isWhitespace() || input[index] == ',')) index++
      val nameStart = index
      while (index < input.length && input[index] != '=' && input[index] != ',') index++
      if (index >= input.length || input[index] != '=') break
      val name = input.substring(nameStart, index).trim().lowercase(Locale.US)
      index++
      while (index < input.length && input[index].isWhitespace()) index++
      val value = if (index < input.length && input[index] == '"') {
        index++
        buildString {
          while (index < input.length) {
            val char = input[index++]
            if (char == '\\' && index < input.length) append(input[index++])
            else if (char == '"') break
            else append(char)
          }
        }
      } else {
        val valueStart = index
        while (index < input.length && input[index] != ',') index++
        input.substring(valueStart, index).trim()
      }
      values[name] = value
      while (index < input.length && input[index] != ',') index++
      if (index < input.length) index++
    }
    return values
  }

  private companion object {
    const val MAX_ATTEMPTS = 3
  }
}
