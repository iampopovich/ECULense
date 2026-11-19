plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:dtcdb"))
    implementation(project(":core:analyticstext"))
}
