import org.gradle.api.Project
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.getByType

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun Project.lib(alias: String): Provider<MinimalExternalModuleDependency> =
    libs.findLibrary(alias).get()

/** ":core:obd" -> "io.github.iampopovich.eculense.core.obd". Имена модулей — без дефисов. */
internal fun Project.namespaceFromPath(): String =
    "io.github.iampopovich.eculense." + path.removePrefix(":").replace(':', '.')

internal const val COMPILE_SDK = 37
internal const val MIN_SDK = 26
