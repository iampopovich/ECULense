package io.github.iampopovich.eculense.feature.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.datapacks.DataPackService
import io.github.iampopovich.eculense.core.datapacks.PackCatalog
import io.github.iampopovich.eculense.core.datapacks.PackState
import io.github.iampopovich.eculense.core.model.CarProfile
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
    val errors: Map<CarField, String> = emptyMap(),
    /** Удалять можно, только если машина не единственная. */
    val canDelete: Boolean = false,
    val isNew: Boolean = true,
    /** Сохранено/удалено — экран закрывается. */
    val done: Boolean = false,
    /** Справочник моделей: null — модуль справочника не подключён. */
    val vehiclePack: PackState? = null,
    val catalogAvailable: Boolean = false,
    /** Открыт пошаговый выбор из справочника. */
    val picker: PickerState? = null,
    /** Справочник по мобильной сети — спросить перед скачиванием. */
    val confirmMeteredDownload: Boolean = false,
)

internal class CarEditViewModel(
    private val carId: Long,
    private val cars: CarRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val catalog: VehicleCatalog? = null,
    private val packs: DataPackService? = null,
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
            val form = original?.let(CarForm::from) ?: CarForm()
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
        val profile = _state.value.form.toProfile(original, clock(), currentYear) ?: return
        viewModelScope.launch {
            val id = cars.save(profile)
            // новая машина становится активной: пользователь создаёт её, чтобы ездить на ней
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

    // --- справочник моделей ---

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

    /** Открыть выбор: марка из профиля сразу ведёт к её моделям, модель — к поколениям. */
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

    /** «Назад» внутри выбора: от ТТХ к списку модификаций, от модификаций к поколениям и т. д. */
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

    /** «Заполнить»: ТТХ выбранной модификации — в форму; сохраняет пользователь сам. */
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
            val next = runCatching { block() }.getOrElse { e -> start.copy(loading = false, error = e.message ?: "Ошибка справочника") }
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
        return PickerState(PickerStep.MODEL, query, make = make, options = models.map { PickerOption(it.id, it.name, years(it.yearStart, it.yearEnd)) })
    }

    private suspend fun loadGenerations(p: PickerState, query: String = ""): PickerState {
        val model = p.model ?: return loadModels(p, "")
        val gens = catalog?.generations(model.id) ?: return notDownloaded()
        val q = query.trim()
        val year = _state.value.form.year.toIntOrNull()
        val options = gens.filter { q.isEmpty() || it.name.contains(q, true) || it.years.contains(q) }
            .map { PickerOption(it.id, it.name, it.years + if (year != null && year in (it.yearStart ?: 0)..(it.yearEnd ?: 9999)) " · ваш год" else "") }
        return PickerState(PickerStep.GENERATION, query, make = p.make, model = model, options = options)
    }

    private suspend fun loadEngines(p: PickerState, query: String): PickerState {
        val gen = p.generation ?: return loadGenerations(p)
        val engines = catalog?.engines(gen.id, query) ?: return notDownloaded()
        return PickerState(
            PickerStep.ENGINE, query, make = p.make, model = p.model, generation = gen,
            options = engines.map { e -> PickerOption(e.id, e.label, listOfNotNull(e.fuelType, e.transmission, e.drivetrain).joinToString(" · ")) },
        )
    }

    private fun notDownloaded() = PickerState(error = "Справочник моделей не скачан")

    private fun years(from: Int?, to: Int?) = if (from == null) null else "$from–${to ?: "н. в."}"
}
