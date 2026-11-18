plugins {
    id("ecu.android.feature")
}

// 1.27: «Настройки → Импорт и резервные копии» — импорт бэкапов Car Scanner (файл или папка), история импортов,
// резервная копия ECULense. Логика импорта/копии — в :core:backup.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:backup"))
    testImplementation(libs.kotlinx.coroutines.test)
}
