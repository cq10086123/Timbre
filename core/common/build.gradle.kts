plugins {
  id("voice.library")
  alias(libs.plugins.metro)
}

dependencies {
  implementation(libs.serialization.json)
  implementation(libs.androidxCore)

  testImplementation(libs.bundles.testing.jvm)
}
