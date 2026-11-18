plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// Расшифровки DTC: общие — OBDex (CC0) из пакета, офлайн — встроенный CSV (lennykean, MIT); заводские — Wal33D (MIT) по марке.
dependencies {
    implementation(project(":core:datapacks"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
