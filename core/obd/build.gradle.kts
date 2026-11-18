plugins {
    id("ecu.jvm.library")
    `java-test-fixtures`
}

dependencies {
    api(project(":core:model"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    testFixturesImplementation(libs.kotlinx.coroutines.core)
}
