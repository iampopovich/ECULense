package io.github.iampopovich.eculense

import kotlin.reflect.KClass

/**
 * Constructor parameter types that are not Koin definitions (see [KoinGraphTest]): Android framework objects,
 * DataStores created inline, lambdas, primitives, files, navigation keys/classes passed as values, and value lists
 * built inside the definition.
 */
object KoinExtraTypes {
    val all: List<KClass<*>> = listOf(
        android.content.Context::class,
        android.app.Application::class,
        Class.forName("androidx.datastore.core.DataStore").kotlin,
        androidx.navigation3.runtime.NavKey::class,
        KClass::class,
        Function0::class,
        Function1::class,
        Function2::class,
        Function3::class,
        Function4::class,
        Function5::class,
        Long::class,
        Int::class,
        Boolean::class,
        Double::class,
        String::class,
        Map::class,
        java.io.File::class,
        io.github.iampopovich.eculense.core.backup.DbSource::class,
        // R-E: ExtendedSensors gets the active-car flow and the debug-only simulated signalsets (getAll, empty in release).
        kotlinx.coroutines.flow.Flow::class,
        io.github.iampopovich.eculense.core.obdb.SimulatedSignalset::class,
    )
}
