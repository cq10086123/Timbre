package voice.navigation

import android.content.Intent
import android.net.Uri
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import voice.core.common.serialization.UriSerializer
import voice.core.data.BookId

sealed interface Destination {

  @Serializable
  data class Playback(val bookId: BookId) : Compose {
    override val trackingName: String get() = "Playback"
  }

  @Serializable
  data class Bookmarks(val bookId: BookId) : Compose {
    override val trackingName: String get() = "Bookmarks"
  }

  @Serializable
  data class CoverFromInternet(val bookId: BookId) : Compose {
    override val trackingName: String get() = "CoverFromInternet"
  }

  data class Website(val url: String) : Destination

  @Serializable
  data class EditCover(
    val bookId: BookId,
    val cover:
    @Serializable(with = UriSerializer::class)
    Uri,
  ) : Compose {
    override val trackingName: String get() = "EditCover"
  }

  data class Activity(val intent: Intent) : Destination

  @Serializable
  sealed interface Compose :
    Destination,
    NavKey {
    val trackingName: String
  }

  @Serializable
  data object Settings : Compose {
    override val trackingName: String get() = "Settings"
  }

  @Serializable
  data object SupportVoice : Compose {
    override val trackingName: String get() = "SupportVoice"
  }

  @Serializable
  data object DeveloperSettings : Compose {
    override val trackingName: String get() = "DeveloperSettings"
  }

  @Serializable
  data object PlayerSettings : Compose {
    override val trackingName: String get() = "PlayerSettings"
  }

  @Serializable
  data object BookOverview : Compose {
    override val trackingName: String get() = "BookOverview"
  }

  @Serializable
  data object FolderPicker : Compose {
    override val trackingName: String get() = "FolderPicker"
  }

  @Serializable
  data object OnboardingWelcome : Compose {
    override val trackingName: String get() = "OnboardingWelcome"
  }

  @Serializable
  data object OnboardingCompletion : Compose {
    override val trackingName: String get() = "OnboardingCompletion"
  }

  @Serializable
  data object OnboardingExplanation : Compose {
    override val trackingName: String get() = "OnboardingExplanation"
  }

  data object BatteryOptimization : Destination

  @Serializable
  data class AddContent(val origin: Origin) : Compose {
    override val trackingName: String = "AddContent"
  }

  @Serializable
  data class WebDavSettings(val origin: Origin) : Compose {
    override val trackingName: String = "WebDavSettings"
  }

  /** Book folders and the playback cache: what the shelf imports from. */
  @Serializable
  data class WebDavImport(val origin: Origin) : Compose {
    override val trackingName: String = "WebDavImport"
  }

  /** Backup folder, backing the shelf up and restoring it. */
  @Serializable
  data class WebDavBackup(val origin: Origin) : Compose {
    override val trackingName: String = "WebDavBackup"
  }

  @Serializable
  data class WebDavServers(val origin: Origin) : Compose {
    override val trackingName: String get() = "WebDavServers"
  }

  @Serializable
  data class WebDavBrowse(
    val serverId: String,
    val origin: Origin,
  ) : Compose {
    override val trackingName: String = "WebDavBrowse"
  }

  /** Picks the folder the shelf backup lives in. */
  @Serializable
  data class WebDavBackupFolder(val origin: Origin) : Compose {
    override val trackingName: String = "WebDavBackupFolder"
  }

  @Serializable
  data object WebDavCache : Compose {
    override val trackingName: String get() = "WebDavCache"
  }

  @Serializable
  data object ExtensionSources : Compose {
    override val trackingName: String get() = "ExtensionSources"
  }
}
