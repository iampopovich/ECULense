plugins {
    id("ecu.android.library")
}

// Фоновый анализ после поездки/импорта и ежедневная проверка ТО (3.9 п.3): WorkManager + уведомления.
// Без сети и без ИИ. Модуль необязательный: без него слой данных сам пересчитывает находки.
dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:navigation"))
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    androidTestImplementation(libs.androidx.work.testing)
}
