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
  implementation(projects.core.initializer)
  implementation(projects.core.logging.api)

  implementation(libs.okhttp)
  implementation(libs.serialization.json)
  implementation(libs.quickjs.kt)

  testImplementation(libs.junit)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.coroutines.test)
}
