plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:analyticstext")) // maintenance work names for the expense link
}
