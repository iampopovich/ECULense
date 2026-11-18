plugins {
    id("ecu.android.library")
}

// Пакеты данных по запросу (1.25b/1.26): справочники скачиваются после установки, в APK их нет.
dependencies {
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    testImplementation(libs.sqlite.jdbc)
}
