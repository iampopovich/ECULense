plugins {
    id("ecu.android.feature")
}

// 1.27: «Настройки → Импорт и резервные копии» — Car Scanner backup import (file or folder), import history,
// ECULense backup. Import/backup logic lives in :core:backup.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:backup"))
    testImplementation(libs.kotlinx.coroutines.test)
}
