plugins {
    id("ecu.android.feature")
}

// R-F: optional MQTT / Home Assistant publishing (Settings hub «Smart home (MQTT)»). Remove this module and :core:mqtt
// from settings.gradle.kts, the app dependency and appModules → the app builds and works without it.
dependencies {
    implementation(project(":core:mqtt"))
    implementation(project(":core:obd"))
    implementation(project(":core:data"))
    // Broker password encrypted with an own Android Keystore key (same cipher as the LLM API keys).
    implementation(project(":core:secrets"))
    implementation(libs.androidx.datastore.preferences)
    testImplementation(testFixtures(project(":core:obd")))
}
