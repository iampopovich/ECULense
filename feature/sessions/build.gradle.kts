plugins {
    id("ecu.android.feature")
}

android {
    // Robolectric: SessionDetailViewModel.exportCsv resolves the module's FileProvider (res/xml/file_paths)
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:obdtext")) // PID names for labels
    implementation(libs.vico.compose.m3)
    testImplementation(libs.robolectric)
}
