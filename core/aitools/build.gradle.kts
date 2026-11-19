plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Koog tools for AI over local data (stage 3): depends on :core:ai and :core:data, not the other way around.
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:data"))
    implementation(project(":core:dtcdb"))
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
