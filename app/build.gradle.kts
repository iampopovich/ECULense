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
        // Release CI passes the version from the tag: -Pecu.versionName=0.1.0 -Pecu.versionCode=100 (.github/workflows/release.yml).
        versionCode = providers.gradleProperty("ecu.versionCode").map(String::toInt).getOrElse(1)
        versionName = providers.gradleProperty("ecu.versionName").getOrElse("0.1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release key comes only from the environment (CI secrets); without it, release is signed with the debug key
    // so the APK still installs, and CI marks such files with the -debugkey suffix.
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
    // Per-app language: AGP generates the locale config from the values-* folders
    // (the default locale comes from src/main/res/resources.properties) and links it in the manifest.
    androidResources {
        generateLocaleConfig = true
    }
    lint {
        error += setOf("MissingTranslation", "MissingQuantity", "ExtraTranslation")
    }
    // 3.10 item 11: two builds of one app (same applicationId, different APKs). cloud — cloud providers only
    // (≈ 10 MB), local — plus the on-device model (:core:ailocal, LiteRT-LM, ≈ 35 MB). The only difference is the flavor
    // source set (OnDeviceModules.kt) and the localImplementation dependency; shared code never sees :core:ailocal types.
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

// NoCyrillicLiteralsTest scans every module's main Kotlin sources: declare them as inputs so the test reruns on changes.
tasks.withType<Test>().configureEach {
    inputs.files(
        fileTree(rootDir) {
            include("*/*/src/main/**/*.kt", "app/src/main/**/*.kt")
            exclude("**/build/**")
        },
    ).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("mainKotlinSources")
}

androidComponents {
    // LiteRT-LM (local only) — ~25 MB of native libraries per ABI. The runtime works only on arm64 (phones);
    // x86_64 copies (emulator) are not packed into the APK.
    onVariants(selector().withFlavor("ai" to "local")) { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/x86_64/libLiteRt.so", "lib/x86_64/libLiteRtClGlAccelerator.so", "lib/x86_64/liblitertlm_jni.so")
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:navigation"))
    implementation(project(":feature:dashboard"))
    implementation(project(":feature:connect"))
    implementation(project(":feature:obdlog"))
    implementation(project(":feature:cardata"))
    implementation(project(":feature:pitstop"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:dtc"))
    implementation(project(":feature:sessions"))
    implementation(project(":feature:car"))
    implementation(project(":feature:stats"))
    implementation(project(":feature:analysis"))
    implementation(project(":feature:maintenance"))
    // 1.27 background import of Car Scanner backups and ECULense backup
    implementation(project(":core:backup"))
    implementation(project(":feature:backup"))
    implementation(project(":core:obd"))
    implementation(project(":core:model"))
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
    // 3.10 on-device model (LiteRT-LM via Koog) — local build only; wired in app/src/local/.../OnDeviceModules.kt.
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}