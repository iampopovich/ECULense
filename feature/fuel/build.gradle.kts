plugins {
    id("ecu.android.feature")
    id("ecu.android.screenshots")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:analyticstext")) // maintenance work names for the expense link
}
