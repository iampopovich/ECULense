plugins {
    id("ecu.android.feature")
}

// 3.10: on-device model: the «На устройстве» provider for LlmGateway (:core:ai), Koog LiteRT catalog, phone
// check, download via :core:datapacks, speed test in a separate :bench process. The module is removable: drop the
// include + app dependency + the line in AppModules, and the app and cloud AI work without it.
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:datapacks"))
    implementation(libs.koog.agents)
    implementation(libs.koog.litert)
    implementation(libs.litertlm.android)
    implementation(libs.mlkit.genai.prompt)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.koin.android)
    testImplementation(libs.androidx.datastore.preferences)
}
