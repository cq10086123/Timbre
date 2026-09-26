package voice.core.source

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the currently installed and enabled [BookSource]s. The package
 * manager writes into it when packages are installed, toggled or removed;
 * the online layer reads from it to route `jdr:` sources.
 */
public class SourceRegistry {

  private val sourcesFlow = MutableStateFlow<List<BookSource>>(emptyList())

  public val sources: StateFlow<List<BookSource>> = sourcesFlow.asStateFlow()

  public fun current(): List<BookSource> = sourcesFlow.value

  public fun get(id: String): BookSource? = sourcesFlow.value.firstOrNull { it.id == id }

  /** Replaces the sources of one package, keeping the others. */
  public fun replacePackage(packageId: String, sources: List<BookSource>) {
    val others = sourcesFlow.value.filterNot { it.packageId == packageId }
    sourcesFlow.value = others + sources
  }

  public fun removePackage(packageId: String) {
    sourcesFlow.value = sourcesFlow.value.filterNot { it.packageId == packageId }
  }
}
