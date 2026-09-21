package voice.core.online

/**
 * Accessor interface for the app graph: lets feature modules reach the
 * online source service through the root graph without depending on the app
 * module (avoiding a circular dependency).
 */
public interface OnlineSourceServiceProvider {
  public val onlineSourceService: OnlineSourceService
}
