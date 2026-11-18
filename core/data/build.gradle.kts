plugins {
    id("ecu.android.library")
}

dependencies {
    api(project(":core:model"))
    api(project(":core:analytics"))
    implementation(project(":core:database"))
    implementation(project(":core:obd"))
    implementation(project(":core:carscanner"))
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(testFixtures(project(":core:obd")))
    testImplementation(testFixtures(project(":core:carscanner")))
    testImplementation(libs.sqlite.jdbc)
}
