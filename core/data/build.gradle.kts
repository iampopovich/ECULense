plugins {
    id("ecu.android.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:analytics"))
    implementation(project(":core:database"))
    implementation(project(":core:obd"))
    // R-E: OBDb signalsets → extended sensors (planner, decoder) of the active car.
    api(project(":core:obdb"))
    implementation(project(":core:carscanner"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(testFixtures(project(":core:obd")))
    // R-E: OBDb test-case signalsets (CC BY-SA 4.0) from the debug-only demo module.
    testImplementation(project(":core:demo"))
    testImplementation(testFixtures(project(":core:carscanner")))
    testImplementation(libs.sqlite.jdbc)
}
