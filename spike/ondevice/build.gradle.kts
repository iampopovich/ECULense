plugins {
    id("ecu.android.library")
}

// Спайк 3.6: модель на устройстве. Подключается только с -Pecu.spike.ondevice=true (settings.gradle.kts), в приложение
// не входит. Модель кладётся в /data/local/tmp/llm. По умолчанию — Koog prompt-executor-litert-client (LiteRT-LM 0.11);
// -Pecu.spike.litertlm=0.19.1 добавляет прямой тест на свежем LiteRT-LM (Koog-клиент с ним бинарно не совместим).
val litertlm = providers.gradleProperty("ecu.spike.litertlm").orNull ?: "0.11.0"

android {
    if (litertlm != "0.11.0") sourceSets["androidTest"].kotlin.directories.add("src/androidTestDirect/kotlin")
}

dependencies {
    androidTestImplementation(libs.koog.litert)
    // В POM Koog LiteRT-LM — runtime-зависимость, а Backend нужен в конфиге клиента.
    androidTestImplementation("com.google.ai.edge.litertlm:litertlm-android:$litertlm")
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
