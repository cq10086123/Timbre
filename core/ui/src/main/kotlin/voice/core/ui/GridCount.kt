package voice.core.ui

import dev.zacsweers.metro.Inject

@Inject
class GridCount {

  // bookshelf mode always defaults to the grid (book covers), even on phones;
  // the user can still switch to the list view in settings
  fun useGridAsDefault(): Boolean = true
}
