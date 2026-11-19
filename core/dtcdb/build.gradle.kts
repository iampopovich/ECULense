plugins {
    id("ecu.android.library")
    alias(libs.plugins.kotlin.serialization)
}

// DTC descriptions: generic from OBDex (CC0) in the pack, offline from a bundled CSV (lennykean, MIT); manufacturer-specific from Wal33D (MIT) per make.
dependencies {
    implementation(project(":core:datapacks"))
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
