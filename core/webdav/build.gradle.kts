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

  testImplementation(libs.junit)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.coroutines.test)
  testImplementation(libs.kotlin.testJunit)
  testImplementation(libs.androidX.test.runner)
  testImplementation(libs.androidX.test.core)
  testImplementation(libs.androidX.test.junit)
  testImplementation(libs.robolectric)
}
