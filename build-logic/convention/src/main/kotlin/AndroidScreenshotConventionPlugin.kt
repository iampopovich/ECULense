import com.android.build.api.dsl.LibraryExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

/**
 * C13: Roborazzi screenshot tests on the JVM (Robolectric native graphics) for a Compose feature module.
 * Golden images live in `src/test/snapshots` (synthetic data only). `check` (and so `build`) runs
 * `verifyRoborazziDebug`; re-record with `./gradlew <module>:recordRoborazziDebug`.
 */
class AndroidScreenshotConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            pluginManager.apply("io.github.takahirom.roborazzi")
            extensions.configure<LibraryExtension> {
                testOptions {
                    unitTests {
                        isIncludeAndroidResources = true
                        all { it.maxHeapSize = "2g" }
                    }
                }
            }
            dependencies {
                add("testImplementation", project(":core:screenshots"))
                add("testImplementation", lib("robolectric"))
            }
            tasks.named("check").configure { dependsOn("verifyRoborazziDebug") }
            // Goldens live outside Roborazzi's default dir: make them a test input, or a changed golden is UP-TO-DATE.
            val goldens = layout.projectDirectory.dir("src/test/snapshots")
            tasks.withType(Test::class.java).configureEach {
                inputs.files(fileTree(goldens)).withPropertyName("roborazziGoldens").withPathSensitivity(PathSensitivity.RELATIVE)
            }
        }
    }
}
