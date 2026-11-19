package io.github.iampopovich.eculense.feature.car

import io.github.iampopovich.eculense.core.vehicledb.VehicleEngine
import io.github.iampopovich.eculense.core.vehicledb.VehicleGeneration
import io.github.iampopovich.eculense.core.vehicledb.VehicleMake
import io.github.iampopovich.eculense.core.vehicledb.VehicleModel
import io.github.iampopovich.eculense.core.vehicledb.VehicleRepository

/** Model reference database for the editor; null from methods means it isn't downloaded. An interface for tests without SQLite. */
internal interface VehicleCatalog {
    fun isAvailable(): Boolean
    suspend fun makes(query: String): List<VehicleMake>?
    suspend fun makeByName(name: String): VehicleMake?
    suspend fun models(makeId: Long, query: String): List<VehicleModel>?
    suspend fun generations(modelId: Long): List<VehicleGeneration>?
    suspend fun engines(generationId: Long, query: String): List<VehicleEngine>?
    suspend fun engine(id: Long): VehicleEngine?
}

internal class RepositoryVehicleCatalog(private val repo: VehicleRepository) : VehicleCatalog {
    override fun isAvailable() = repo.isAvailable()
    override suspend fun makes(query: String) = repo.query { it.makes(query) }
    override suspend fun makeByName(name: String) = repo.query { it.makeByName(name) }
    override suspend fun models(makeId: Long, query: String) = repo.query { it.models(makeId, query) }
    override suspend fun generations(modelId: Long) = repo.query { it.generations(modelId) }
    override suspend fun engines(generationId: Long, query: String) = repo.query { it.engines(generationId, query) }
    override suspend fun engine(id: Long) = repo.query { it.engine(id) }
}

internal enum class PickerStep(val title: String) {
    MAKE("Марка"), MODEL("Модель"), GENERATION("Поколение"), ENGINE("Модификация"),
}

internal data class PickerOption(val id: Long, val title: String, val subtitle: String? = null)

/** Step-by-step «марка → модель → поколение → модификация» picker with search; the last step shows a specs card. */
internal data class PickerState(
    val step: PickerStep = PickerStep.MAKE,
    val query: String = "",
    val make: VehicleMake? = null,
    val model: VehicleModel? = null,
    val generation: VehicleGeneration? = null,
    val options: List<PickerOption> = emptyList(),
    val engine: VehicleEngine? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    /** «Kia › Sorento › 2002–2006». */
    val path: String get() = listOfNotNull(make?.name, model?.name, generation?.let { "${it.name} (${it.years})" }).joinToString(" › ")
}

/**
 * Filling the form from the reference database: engine specs are overwritten (the user tapped «Заполнить» themselves), while name, make
 * and year, if already present, are kept since this is the user's profile. All fields can be edited afterwards.
 */
internal fun CarForm.fillFrom(make: VehicleMake, model: VehicleModel, generation: VehicleGeneration, engine: VehicleEngine): CarForm = copy(
    brand = brand.ifBlank { make.name },
    model = this.model.ifBlank { model.name },
    generation = this.generation.ifBlank { generation.name },
    engineVolumeL = engine.engineVolumeL?.let(CarForm::plain) ?: engineVolumeL,
    cylinders = engine.cylinders?.toString() ?: cylinders,
    fuelType = engine.fuel ?: fuelType,
    gearbox = engine.gearbox ?: gearbox,
    drive = engine.drive ?: drive,
    tankVolumeL = engine.tankL?.let(CarForm::plain) ?: tankVolumeL,
    curbWeightKg = engine.curbWeightKg?.toString() ?: curbWeightKg,
)
