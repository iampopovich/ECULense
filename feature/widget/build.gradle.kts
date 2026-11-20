plugins {
    id("ecu.android.feature")
}

android {
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obd"))
    implementation(project(":core:obdtext"))
    implementation(libs.glance.appwidget)
    implementation(libs.koin.android)
    testImplementation(testFixtures(project(":core:obd")))
    testImplementation(libs.glance.appwidget.testing)
    testImplementation(libs.robolectric)
}
