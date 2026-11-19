import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** AGP 9: Kotlin is built in, the kotlin-android plugin need not be applied. */
class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("com.android.library")
            extensions.configure<LibraryExtension> {
                namespace = namespaceFromPath()
                compileSdk = COMPILE_SDK
                defaultConfig {
                    minSdk = MIN_SDK
                    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
                }
                compileOptions {
                    sourceCompatibility = JavaVersion.VERSION_17
                    targetCompatibility = JavaVersion.VERSION_17
                }
                // Every values-ru string needs an English default and vice versa (plurals included).
                lint {
                    error += setOf("MissingTranslation", "MissingQuantity", "ExtraTranslation")
                }
            }
            dependencies {
                add("implementation", lib("kotlinx-coroutines-core"))
                add("testImplementation", lib("junit"))
                add("testImplementation", lib("kotlinx-coroutines-test"))
                add("androidTestImplementation", lib("androidx-junit"))
                // androidx-junit does not pull in AndroidJUnitRunner itself — without it the test APK crashes on start.
                add("androidTestImplementation", lib("androidx-test-runner"))
                add("androidTestImplementation", lib("kotlinx-coroutines-test"))
            }
        }
    }
}
