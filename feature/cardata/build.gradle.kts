plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(project(":core:data"))
    testImplementation(testFixtures(project(":core:obd")))
    // R-E: OBDb test-case signalset (CC BY-SA 4.0) from the debug-only demo module.
    testImplementation(project(":core:demo"))
}
