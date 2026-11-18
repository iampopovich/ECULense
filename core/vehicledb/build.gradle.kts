plugins {
    id("ecu.android.library")
}

// Read-only справочник моделей и ТТХ (gor3a/vehicle-makes-models, ODbL) из пакета данных. CarProfile от него не зависит.
dependencies {
    api(project(":core:model"))
    implementation(project(":core:datapacks"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
