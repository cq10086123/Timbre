plugins {
  id("voice.library")
  alias(libs.plugins.metro)
  alias(libs.plugins.kotlin.serialization)
}

kotlin {
  explicitApi()
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)

  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.datastore)
  implementation(libs.media3.datasource)

  testImplementation(libs.mockwebserver)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.mockk)
}
