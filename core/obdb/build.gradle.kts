plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// OBDb signalset v3 (CC BY-SA 4.0): скачивание и разбор по марке/модели/году. Опроса расширенных PID здесь нет.
dependencies {
    implementation(project(":core:datapacks"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
}
