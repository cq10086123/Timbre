plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

kotlin {
  explicitApi()
}

dependencies {
  implementation(projects.core.common)
  implementation(projects.core.data.api)
  implementation(projects.core.documentfile)

  implementation(libs.okhttp)
  implementation(libs.androidxCore)
  implementation(libs.serialization.json)
  implementation(libs.datastore)
  implementation(libs.media3.datasource)

  testImplementation(libs.mockwebserver)
}
