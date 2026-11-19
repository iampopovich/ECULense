package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.ui.UiText
import io.github.iampopovich.eculense.core.ui.YearRange
import io.github.iampopovich.eculense.core.vehicledb.VehicleDbFormatException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

internal data class CarEditUiState(
    val loading: Boolean = true,
    val form: CarForm = CarForm(),
    val errors: Map<CarField, UiText> = emptyMap(),
    /** Deletion is allowed only if the car is not the only one. */
    val canDelete: Boolean = false,
    val isNew: Boolean = true,
    /** Saved/deleted — the screen closes. */
    val done: Boolean = false,
    /** Model catalog: null — the catalog module is not wired in. */
    val vehiclePack: PackState? = null,
    val catalogAvailable: Boolean = false,
    /** Step-by-step catalog picker is open. */
    val picker: PickerState? = null,
    /** Catalog over mobile data — ask before downloading. */
    val confirmMeteredDownload: Boolean = false,
)

internal class CarEditViewModel(
    private val carId: Long,
    private val cars: CarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val catalog: VehicleCatalog? = null,
    private val packs: DataPackService? = null,
    /** Localized default car name: shown in the form for a car the user never named; saved back as «unnamed» if unchanged. */
    private val defaultName: () -> String = { "" },
) : ViewModel() {
    private val _state = MutableStateFlow(CarEditUiState())
    val state: StateFlow<CarEditUiState> = _state.asStateFlow()
    private var original: CarProfile? = null
    private var pickerJob: Job? = null

    private val currentYear get() = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).year

    init {
        viewModelScope.launch {
            val all = cars.cars()
            original = all.firstOrNull { it.id == carId }
            val form = original?.let { car -> CarForm.from(car).let { if (car.hasDefaultName) it.copy(name = defaultName()) else it } } ?: CarForm()
            _state.update {
                it.copy(
                    loading = false,
                    form = form,
                    errors = form.errors(currentYear),
                    canDelete = original != null && all.size > 1,
                    isNew = original == null,
                    catalogAvailable = catalog?.isAvailable() == true,
                )
            }
        }
        packs?.let { p ->
            viewModelScope.launch {
                p.observe().collect { list ->
                    val pack = list.firstOrNull { it.manifest.id == PackCatalog.VEHICLES }
                    _state.update { it.copy(vehiclePack = pack, catalogAvailable = catalog?.isAvailable() == true || pack?.installed != null) }
                }
            }
        }
    }

    fun edit(transform: (CarForm) -> CarForm) {
        _state.update { s ->
            val form = transform(s.form)
            s.copy(form = form, errors = form.errors(currentYear))
        }
    }

    fun save() {
        val profile = _state.value.form.toProfile(original, clock(), currentYear)
            ?.let { p -> if (original?.hasDefaultName == true && p.name == defaultName()) p.copy(name = original!!.name) else p } ?: return
        viewModelScope.launch {
            val id = cars.save(profile)
            // a new car becomes active: the user creates it to drive it
            if (original == null) cars.setActive(id)
            _state.update { it.copy(done = true) }
        }
    }

    fun delete() {
        val car = original ?: return
        viewModelScope.launch {
            if (cars.delete(car.id)) _state.update { it.copy(done = true) }
        }
    }

    // --- model catalog ---

    fun downloadCatalog(confirmed: Boolean = false) {
        val p = packs ?: return
        if (!confirmed && p.isMeteredNetwork()) {
            _state.update { it.copy(confirmMeteredDownload = true) }
            return
        }
        _state.update { it.copy(confirmMeteredDownload = false) }
        p.download(PackCatalog.VEHICLES)
    }

    fun dismissDownload() = _state.update { it.copy(confirmMeteredDownload = false) }

    /** Open the picker: the make from the profile leads straight to its models, the model to its generations. */
    fun openPicker() = runPicker(PickerState(loading = true)) {
        val form = _state.value.form
        val make = form.brand.takeIf { it.isNotBlank() }?.let { catalog?.makeByName(it) }
        val model = make?.let { m -> form.model.takeIf { it.isNotBlank() }?.let { name -> catalog?.models(m.id, "")?.firstOrNull { it.name.equals(name.trim(), true) } } }
        when {
            model != null -> loadGenerations(PickerState(make = make, model = model))
            make != null -> loadModels(PickerState(make = make), "")
            else -> loadMakes("")
        }
    }

    fun closePicker() {
        pickerJob?.cancel()
        _state.update { it.copy(picker = null) }
    }

    fun search(query: String) {
        val p = _state.value.picker ?: return
        runPicker(p.copy(query = query)) {
            when (p.step) {
                PickerStep.MAKE -> loadMakes(query)
                PickerStep.MODEL -> loadModels(p, query)
                PickerStep.GENERATION -> loadGenerations(p, query)
                PickerStep.ENGINE -> loadEngines(p, query)
            }
        }
    }

    fun choose(option: PickerOption) {
        val p = _state.value.picker ?: return
        runPicker(p.copy(loading = true)) {
            when (p.step) {
                PickerStep.MAKE -> loadModels(PickerState(make = catalog?.makes("")?.firstOrNull { it.id == option.id }), "")
                PickerStep.MODEL -> loadGenerations(p.copy(model = catalog?.models(p.make!!.id, "")?.firstOrNull { it.id == option.id }))
                PickerStep.GENERATION -> loadEngines(p.copy(generation = catalog?.generations(p.model!!.id)?.firstOrNull { it.id == option.id }), "")
                PickerStep.ENGINE -> p.copy(engine = catalog?.engine(option.id), loading = false)
            }
        }
    }

    /** «Back» inside the picker: from specs to the trim list, from trims to generations, etc. */
    fun pickerBack() {
        val p = _state.value.picker ?: return
        if (p.engine != null) {
            _state.update { it.copy(picker = p.copy(engine = null)) }
            return
        }
        when (p.step) {
            PickerStep.MAKE -> closePicker()
            PickerStep.MODEL -> runPicker(p) { loadMakes("") }
            PickerStep.GENERATION -> runPicker(p) { loadModels(PickerState(make = p.make), "") }
            PickerStep.ENGINE -> runPicker(p) { loadGenerations(PickerState(make = p.make, model = p.model)) }
        }
    }

    /** «Fill in»: specs of the selected trim go into the form; the user saves it themselves. */
    fun fillFromPicker() {
        val p = _state.value.picker ?: return
        val e = p.engine ?: return
        edit { it.fillFrom(p.make ?: return@edit it, p.model ?: return@edit it, p.generation ?: return@edit it, e) }
        _state.update { it.copy(picker = null) }
    }

    private fun runPicker(start: PickerState, block: suspend () -> PickerState) {
        pickerJob?.cancel()
        _state.update { it.copy(picker = start.copy(loading = true, error = null)) }
        pickerJob = viewModelScope.launch {
            val next = runCatching { block() }.getOrElse { e -> start.copy(loading = false, error = pickerError(e)) }
            _state.update { it.copy(picker = next.copy(loading = false)) }
        }
    }

    private suspend fun loadMakes(query: String): PickerState {
        val makes = catalog?.makes(query) ?: return notDownloaded()
        return PickerState(PickerStep.MAKE, query, options = makes.map { PickerOption(it.id, it.name) })
    }

    private suspend fun loadModels(p: PickerState, query: String): PickerState {
        val make = p.make ?: return loadMakes("")
        val models = catalog?.models(make.id, query) ?: return notDownloaded()
        return PickerState(PickerStep.MODEL, query, make = make, options = models.map { PickerOption(it.id, it.name, YearRange.of(it.yearStart, it.yearEnd)) })
    }

    private suspend fun loadGenerations(p: PickerState, query: String = ""): PickerState {
        val model = p.model ?: return loadModels(p, "")
        val gens = catalog?.generations(model.id) ?: return notDownloaded()
        val q = query.trim()
        val year = _state.value.form.year.toIntOrNull()
        val options = gens.filter { g -> q.isEmpty() || g.name.contains(q, true) || listOfNotNull(g.yearStart, g.yearEnd).any { it.toString().contains(q) } }
            .map {
                val range = YearRange.of(it.yearStart, it.yearEnd)
                val yours = range != null && year != null && year in (it.yearStart ?: 0)..(it.yearEnd ?: 9999)
                PickerOption(it.id, it.name, if (yours) UiText.Res(R.string.car_your_year, listOf(range)) else range)
            }
        return PickerState(PickerStep.GENERATION, query, make = p.make, model = model, options = options)
    }

    private suspend fun loadEngines(p: PickerState, query: String): PickerState {
        val gen = p.generation ?: return loadGenerations(p)
        val engines = catalog?.engines(gen.id, query) ?: return notDownloaded()
        return PickerState(
            PickerStep.ENGINE, query, make = p.make, model = p.model, generation = gen,
            options = engines.map { e -> PickerOption(e.id, e.label, UiText.Raw(listOfNotNull(e.fuelType, e.transmission, e.drivetrain).joinToString(" · "))) },
        )
    }

    private fun pickerError(e: Throwable): UiText = when {
        e is VehicleDbFormatException -> UiText.Res(R.string.car_picker_format_changed, listOf(e.problems.joinToString()))
        e.message != null -> UiText.Raw(e.message!!)
        else -> UiText.Res(R.string.car_picker_error)
    }

    private fun notDownloaded() = PickerState(error = UiText.Res(R.string.car_catalog_not_downloaded))
}
