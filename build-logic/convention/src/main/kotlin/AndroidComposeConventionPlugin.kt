import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("ecu.android.library")
            pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
            extensions.configure<LibraryExtension> {
                buildFeatures { compose = true }
            }
            dependencies {
                val bom = lib("androidx-compose-bom")
                add("implementation", platform(bom))
                add("implementation", lib("androidx-compose-ui"))
                add("implementation", lib("androidx-compose-material3"))
                add("implementation", lib("androidx-compose-ui-tooling-preview"))
                add("debugImplementation", lib("androidx-compose-ui-tooling"))
                add("androidTestImplementation", platform(bom))
                add("androidTestImplementation", lib("androidx-compose-ui-test-junit4"))
                add("debugImplementation", lib("androidx-compose-ui-test-manifest"))
            }
        }
    }
}
