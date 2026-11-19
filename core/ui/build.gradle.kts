plugins {
    id("ecu.android.compose")
}

dependencies {
    // Car names and unit enums are labeled here (pure-JVM model, no UI text).
    api(project(":core:model"))
}
