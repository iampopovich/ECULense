plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.iampopovich.eculense"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.iampopovich.eculense"
        minSdk = 26
        targetSdk = 37
        // CI релиза передаёт версию из тега: -Pecu.versionName=0.1.0 -Pecu.versionCode=100 (.github/workflows/release.yml).
        versionCode = providers.gradleProperty("ecu.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("ecu.versionName").getOrElse("0.1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Ключ релиза — только из окружения (секреты CI); без него release подписывается отладочным ключом,
    // чтобы APK ставился, а CI помечает такие файлы суффиксом -debugkey.
    val releaseKeystore = providers.environmentVariable("ECU_KEYSTORE_FILE").orNull
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ECU_KEYSTORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("ECU_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("ECU_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // 3.10 п.11: две сборки одного приложения (один applicationId, разные APK). cloud — только облачные провайдеры
    // (≈ 10 МБ), local — плюс модель на телефоне (:core:ailocal, LiteRT-LM, ≈ 35 МБ). Различие — только в source set
    // флейвора (OnDeviceModules.kt) и зависимости localImplementation; общий код типов :core:ailocal не видит.
    flavorDimensions += "ai"
    productFlavors {
        create("cloud") {
            dimension = "ai"
        }
        create("local") {
            dimension = "ai"
            versionNameSuffix = "-local"
        }
    }
}

androidComponents {
    // LiteRT-LM (только в local) — ~25 МБ нативных библиотек на ABI. Рантайм работает только на arm64 (телефоны);
    // x86_64-копии (эмулятор) в APK не кладём.
    onVariants(selector().withFlavor("ai" to "local")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86_64/libLiteRt.so", "lib/x86_64/libLiteRtClGlAccelerator.so", "lib/x86_64/liblitertlm_jni.so")
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:pitstop"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:dtc"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:car"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:analysis"))
    implementation(project(":feature:maintenance"))
    // 1.27 импорт бэкапов Car Scanner в фоне и резервная копия ECULense
    implementation(project(":core:backup"))
    implementation(project(":feature:backup"))
    implementation(project(":core:obd"))
    implementation(project(":core:bluetooth"))
    implementation(project(":core:usb"))
    implementation(project(":core:service"))
    implementation(project(":core:database"))
    implementation(project(":core:data"))
    implementation(project(":core:demo"))
    implementation(project(":core:ai"))
    implementation(project(":core:aitools"))
    implementation(project(":core:datapacks"))
    implementation(project(":core:vehicledb"))
    implementation(project(":core:dtcdb"))
    implementation(project(":core:background"))
    implementation(project(":feature:datapacks"))
    implementation(project(":feature:aisettings"))
    implementation(project(":feature:aichat"))
    // 3.10 модель на телефоне (LiteRT-LM через Koog) — только в сборке local; подключение — app/src/local/.../OnDeviceModules.kt.
    "localImplementation"(project(":core:ailocal"))
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.androidx.compose)
    implementation(libs.koin.compose.navigation3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}