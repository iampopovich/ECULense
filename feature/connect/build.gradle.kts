plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    testImplementation(testFixtures(project(":core:obd")))
}
