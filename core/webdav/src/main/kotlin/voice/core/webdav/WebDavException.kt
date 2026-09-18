package voice.core.webdav

public sealed class WebDavException(
  message: String,
  public val url: String,
) : Exception(message) {

  public class Auth(url: String) : WebDavException("Authentication failed for $url", url)

  public class NotFound(url: String) : WebDavException("Not found: $url", url)

  public class Http(
    public val code: Int,
    url: String,
  ) : WebDavException("Http $code for $url", url)
}
