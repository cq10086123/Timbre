package voice.app.di

import voice.app.features.widget.BaseWidgetProvider
import voice.features.widget.WidgetGraph

interface AppGraph :
  WidgetGraph,
  voice.core.online.OnlineSourceServiceProvider {

  override val onlineSourceService: voice.core.online.OnlineSourceService

  fun inject(target: App)
  override fun inject(target: BaseWidgetProvider)
}
