plugins {
    id("ecu.android.feature")
}

dependencies {
    implementation(project(":core:ai"))
    implementation(libs.markdown.renderer.m3)
}
