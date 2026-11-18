plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Инструменты Koog для ИИ на локальных данных (этап 3): зависит от :core:ai и :core:data, не наоборот.
dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:data"))
    implementation(libs.koog.agents)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
