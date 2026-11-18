plugins {
    id("ecu.android.library")
}

dependencies {
    implementation(libs.koog.agents)
    implementation(libs.koog.openrouter)
    implementation(libs.koog.google)
    // ConnectionChecker: свой GET каталога (Ktor/OkHttp приходят с koog-agents) + разбор JSON.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
