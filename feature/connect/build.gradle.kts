plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    testImplementation(testFixtures(project(":core:obd")))
}
