plugins {
    id("ecu.android.library")
}

dependencies {
    api(project(":core:model"))
    implementation(project(":core:database"))
    implementation(project(":core:obd"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(testFixtures(project(":core:obd")))
}
