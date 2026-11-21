package io.github.iampopovich.eculense.feature.fuel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.iampopovich.eculense.core.analytics.CostSummary
import io.github.iampopovich.eculense.core.analytics.FullToFull
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.OdometerEstimate
import io.github.iampopovich.eculense.core.data.CarRepository
import io.github.iampopovich.eculense.core.data.ExpenseRepository
import io.github.iampopovich.eculense.core.data.FuelCsv
import io.github.iampopovich.eculense.core.data.FuelCsvFormat
import io.github.iampopovich.eculense.core.data.FuelInsights
import io.github.iampopovich.eculense.core.data.FuelPrefs
import io.github.iampopovich.eculense.core.data.FuelRepository
import io.github.iampopovich.eculense.core.data.MaintenanceRepository
import io.github.iampopovich.eculense.core.data.TripRepository
import io.github.iampopovich.eculense.core.data.observeFuelInsights
import io.github.iampopovich.eculense.core.model.CarProfile
import io.github.iampopovich.eculense.core.model.Expense
import io.github.iampopovich.eculense.core.model.FuelFillUp
import io.github.iampopovich.eculense.core.model.MaintenanceItem
import io.github.iampopovich.eculense.core.model.StatsPeriod
import io.github.iampopovich.eculense.core.ui.UiText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

internal data class FuelLogUiState(
    val loading: Boolean = true,
    val car: CarProfile? = null,
    val insights: FuelInsights? = null,
    val odometer: OdometerEstimate? = null,
    val nowMs: Long = 0,
    /** Import outcome or failure, shown once. */
    val message: UiText? = null,
) {
    val fillUps: List<FuelFillUp> get() = insights?.fillUps.orEmpty()

    /** The full-to-full interval each fill-up closed (by fill-up time), for the consumption on its row. */
    val closedBy: Map<Long, FullToFull> get() = insights?.intervals.orEmpty().associateBy { it.toMs }
}

/** D1 fuel log of the active car. Data only through :core:data. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class FuelLogViewModel(
    private val cars: CarRepository,
    private val fuel: FuelRepository,
    trips: TripRepository,
    private val prefs: FuelPrefs,
    private val maintenance: MaintenanceRepository?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val message = MutableStateFlow<UiText?>(null)
    private val car = cars.observeActiveCar().distinctUntilChanged()

    val state: StateFlow<FuelLogUiState> = combine(
        car,
        car.map { it?.id }.distinctUntilChanged().flatMapLatest { id -> id?.let { observeFuelInsights(it, fuel, trips, prefs) } ?: flowOf(null) },
        car.map { it?.id }.distinctUntilChanged().flatMapLatest { id -> id?.let { maintenance?.observeEstimate(it) } ?: flowOf(null) },
        message,
    ) { c, i, odo, m -> FuelLogUiState(false, c, i, odo, clock(), m) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FuelLogUiState())

    fun newForm(): FillUpForm = FillUpForm.new(clock(), zone(), state.value.odometer?.km, state.value.car?.fuelPrice)

    fun formOf(f: FuelFillUp): FillUpForm = FillUpForm.of(f, zone())

    /** true when the form was valid and saved. */
    fun save(form: FillUpForm): Boolean {
        val carId = state.value.car?.id ?: return false
        val f = form.toFillUp(carId, zone(), clock()) ?: return false
        viewModelScope.launch { fuel.save(f) }
        return true
    }

    fun delete(id: Long) {
        viewModelScope.launch { fuel.delete(id) }
    }

    fun setCorrection(on: Boolean) {
        val carId = state.value.car?.id ?: return
        viewModelScope.launch { prefs.setMafCorrection(carId, on) }
    }

    /** CSV text from the file picker (Fuelio export or a generic CSV with named columns). */
    fun importCsv(text: String?) {
        viewModelScope.launch {
            val carId = cars.activeCarId()
            if (text == null || carId == null) {
                message.value = UiText.Res(R.string.fuel_import_failed)
                return@launch
            }
            val parsed = FuelCsv.parse(text, carId, zone())
            if (parsed.format == FuelCsvFormat.UNKNOWN) {
                message.value = UiText.Res(R.string.fuel_import_unknown)
                return@launch
            }
            val r = fuel.import(parsed.rows)
            message.value = UiText.Res(R.string.fuel_import_done, listOf(r.added, r.skipped, parsed.badLines))
        }
    }

    /** The picked file is over [CsvImportLimit.MAX_MB] MB: not read, clear message instead. */
    fun importTooLarge() {
        message.value = UiText.Res(R.string.fuel_import_too_large, listOf(CsvImportLimit.MAX_MB))
    }

    fun messageShown() {
        message.value = null
    }
}

internal data class ExpensesUiState(
    val loading: Boolean = true,
    val car: CarProfile? = null,
    val period: StatsPeriod = StatsPeriod.ALL,
    val expenses: List<Expense> = emptyList(),
    val summary: CostSummary? = null,
    /** Works an expense can close (service/repair), with their status. */
    val works: List<MaintenanceDue> = emptyList(),
    val odometer: OdometerEstimate? = null,
    val nowMs: Long = 0,
)

/** D2 expenses and the cost of ownership of the active car; fuel money comes from the fuel log. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ExpensesViewModel(
    cars: CarRepository,
    private val expenses: ExpenseRepository,
    fuel: FuelRepository,
    trips: TripRepository,
    private val maintenance: MaintenanceRepository?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) : ViewModel() {
    private val period = MutableStateFlow(StatsPeriod.ALL)
    private val car = cars.observeActiveCar().distinctUntilChanged()
    private val carId = car.map { it?.id }.distinctUntilChanged()

    private data class Inputs(val e: List<Expense>, val f: List<FuelFillUp>, val km: List<Pair<Long, Double>>, val items: List<MaintenanceItem>)

    private val inputs = carId.flatMapLatest { id ->
        if (id == null) flowOf(Inputs(emptyList(), emptyList(), emptyList(), emptyList()))
        else combine(
            expenses.observeExpenses(id), fuel.observeFillUps(id), trips.observeTrips(id).map { l -> l.map { it.startedAtMs to it.distanceKm } },
            maintenance?.observeItems(id) ?: flowOf(emptyList()),
        ) { e, f, km, items -> Inputs(e, f, km, items) }
    }

    val state: StateFlow<ExpensesUiState> = combine(car, period, inputs) { c, p, i ->
        val now = clock()
        val from = p.days?.let { now - it * DAY_MS } ?: Long.MIN_VALUE
        val inPeriod = i.e.filter { it.atMs in from..now }
        // Distance in the period: odometer of fill-ups when two readings frame it, else the trips' distance.
        val readings = i.f.filter { it.atMs in from..now }.mapNotNull { it.odometerKm }
        val odoKm = if (readings.size >= 2) (readings.max() - readings.min()).toDouble() else null
        val tripKm = i.km.filter { it.first in from..now }.sumOf { it.second }.takeIf { it > 0 }
        val summary = CostSummary.of(i.e, i.f, from, now, odoKm ?: tripKm, zone())
        val due = c?.id?.let { runCatching { maintenance?.evaluate(it) }.getOrNull() }.orEmpty()
        ExpensesUiState(false, c, p, inPeriod, summary, due, c?.id?.let { maintenance?.estimate(it) }, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExpensesUiState())

    fun selectPeriod(p: StatsPeriod) {
        period.value = p
    }

    fun newForm(): ExpenseForm = ExpenseForm.new(clock(), zone())

    fun formOf(e: Expense): ExpenseForm = ExpenseForm.of(e, zone())

    fun save(form: ExpenseForm): Boolean {
        val id = state.value.car?.id ?: return false
        val e = form.toExpense(id, zone(), clock()) ?: return false
        viewModelScope.launch { expenses.save(e) }
        return true
    }

    fun delete(id: Long) {
        viewModelScope.launch { expenses.delete(id) }
    }

    private companion object {
        const val DAY_MS = 86_400_000L
    }
}

