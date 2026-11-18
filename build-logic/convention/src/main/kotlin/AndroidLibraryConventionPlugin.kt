import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

/** AGP 9: Kotlin встроен, плагин kotlin-android применять не нужно. */
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
            }
            dependencies {
                add("implementation", lib("kotlinx-coroutines-core"))
                add("testImplementation", lib("junit"))
                add("testImplementation", lib("kotlinx-coroutines-test"))
                add("androidTestImplementation", lib("androidx-junit"))
                add("androidTestImplementation", lib("kotlinx-coroutines-test"))
            }
        }
    }
}
