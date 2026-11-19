plugins {
    id("ecu.android.library")
}

// Read-only reference of models and specs (gor3a/vehicle-makes-models, ODbL) from a data pack. CarProfile does not depend on it.
dependencies {
    api(project(":core:model"))
    implementation(project(":core:datapacks"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
