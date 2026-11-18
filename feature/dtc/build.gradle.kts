plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:obd"))
    implementation(project(":core:data"))
    implementation(project(":core:dtcdb"))
    testImplementation(testFixtures(project(":core:obd")))
}
