import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("ecu.android.compose")
            pluginManager.apply("org.jetbrains.kotlin.plugin.serialization")
            dependencies {
                add("implementation", project(":core:ui"))
                add("implementation", project(":core:navigation"))
                add("implementation", project(":core:model"))
                add("implementation", platform(lib("koin-bom")))
                add("implementation", lib("koin-androidx-compose"))
                add("implementation", lib("koin-compose-navigation3"))
                add("implementation", lib("androidx-activity-compose"))
                add("implementation", lib("androidx-lifecycle-viewmodel-compose"))
                add("implementation", lib("androidx-lifecycle-runtime-compose"))
                add("testImplementation", lib("turbine"))
            }
        }
    }
}
