package io.github.iampopovich.eculense.core.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.iampopovich.eculense.core.obd.StandardPids
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * Dashboard layout (C1/C6). The PID selection is per car (another car supports other PIDs), stored under the active
 * car id (0 = no car chosen yet); the display options are global.
 */
interface DashboardPrefs {
    /** Chosen PIDs in display order; null = never edited, the dashboard uses [StandardPids.dashboardDefaults]. */
    val selection: Flow<List<Int>?>
    suspend fun setSelection(codes: List<Int>)

    /** Keep the screen on while an adapter is connected (default on). */
    val keepScreenOn: Flow<Boolean>
    suspend fun setKeepScreenOn(on: Boolean)

    /** Driving mode: mirrored picture for a windshield reflection (HUD). */
    val hudMirror: Flow<Boolean>
    suspend fun setHudMirror(on: Boolean)

    /** Driving mode: how many of the first selected PIDs are shown (2..6). */
    val drivingTiles: Flow<Int>
    suspend fun setDrivingTiles(count: Int)
}

/** Pure edits of the dashboard selection (no text input: star toggles, up/down moves). */
object DashboardSelection {
    const val MIN_DRIVING_TILES = 2
    const val MAX_DRIVING_TILES = 6
    const val DEFAULT_DRIVING_TILES = 4

    /** Fuel rate can be shown as an estimate from MAF when the car does not report it (C3 '~'). */
    const val FUEL_RATE = 0x5E
    const val MAF = 0x10

    /** What the dashboard shows for a car: the saved order (or defaults), limited to what the car can deliver. */
    fun resolve(saved: List<Int>?, supported: Set<Int>): List<Int> =
        (saved ?: StandardPids.dashboardDefaults).distinct().filter { canShow(it, supported) }

    fun canShow(code: Int, supported: Set<Int>): Boolean =
        StandardPids.byCode(code) != null && (code in supported || (code == FUEL_RATE && MAF in supported))

    fun toggle(current: List<Int>, code: Int): List<Int> = if (code in current) current - code else current + code

    fun moveUp(current: List<Int>, code: Int): List<Int> {
        val i = current.indexOf(code)
        if (i <= 0) return current
        return current.toMutableList().apply { add(i - 1, removeAt(i)) }
    }

    fun moveDown(current: List<Int>, code: Int): List<Int> {
        val i = current.indexOf(code)
        if (i < 0 || i == current.lastIndex) return current
        return current.toMutableList().apply { add(i + 1, removeAt(i)) }
    }

    fun encode(codes: List<Int>): String = codes.joinToString(",") { "%02X".format(it) }

    fun decode(text: String): List<Int> = text.split(',').mapNotNull { it.trim().toIntOrNull(16) }
}

@OptIn(ExperimentalCoroutinesApi::class)
internal class DataStoreDashboardPrefs(
    private val store: DataStore<Preferences>,
    private val activeCarId: Flow<Long?>,
) : DashboardPrefs {
    override val selection: Flow<List<Int>?> = activeCarId.flatMapLatest { id ->
        store.data.map { p -> p[selectionKey(id)]?.let(DashboardSelection::decode) }
    }

    override suspend fun setSelection(codes: List<Int>) {
        val key = selectionKey(activeCarId.first())
        store.edit { it[key] = DashboardSelection.encode(codes) }
    }

    override val keepScreenOn: Flow<Boolean> = store.data.map { it[KEEP_SCREEN_ON] ?: true }
    override suspend fun setKeepScreenOn(on: Boolean) {
        store.edit { it[KEEP_SCREEN_ON] = on }
    }

    override val hudMirror: Flow<Boolean> = store.data.map { it[HUD_MIRROR] ?: false }
    override suspend fun setHudMirror(on: Boolean) {
        store.edit { it[HUD_MIRROR] = on }
    }

    override val drivingTiles: Flow<Int> = store.data.map {
        (it[DRIVING_TILES] ?: DashboardSelection.DEFAULT_DRIVING_TILES)
            .coerceIn(DashboardSelection.MIN_DRIVING_TILES, DashboardSelection.MAX_DRIVING_TILES)
    }
    override suspend fun setDrivingTiles(count: Int) {
        store.edit {
            it[DRIVING_TILES] = count.coerceIn(DashboardSelection.MIN_DRIVING_TILES, DashboardSelection.MAX_DRIVING_TILES)
        }
    }

    private fun selectionKey(carId: Long?) = stringPreferencesKey("pids|car=${carId ?: 0}")

    private companion object {
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
        val HUD_MIRROR = booleanPreferencesKey("hud_mirror")
        val DRIVING_TILES = intPreferencesKey("driving_tiles")
    }
}

/** In-memory prefs for tests and previews. */
class InMemoryDashboardPrefs(
    selection: List<Int>? = null,
    keepScreenOn: Boolean = true,
    hudMirror: Boolean = false,
    drivingTiles: Int = DashboardSelection.DEFAULT_DRIVING_TILES,
) : DashboardPrefs {
    override val selection = MutableStateFlow(selection)
    override val keepScreenOn = MutableStateFlow(keepScreenOn)
    override val hudMirror = MutableStateFlow(hudMirror)
    override val drivingTiles = MutableStateFlow(drivingTiles)
    override suspend fun setSelection(codes: List<Int>) { selection.value = codes }
    override suspend fun setKeepScreenOn(on: Boolean) { keepScreenOn.value = on }
    override suspend fun setHudMirror(on: Boolean) { hudMirror.value = on }
    override suspend fun setDrivingTiles(count: Int) {
        drivingTiles.value = count.coerceIn(DashboardSelection.MIN_DRIVING_TILES, DashboardSelection.MAX_DRIVING_TILES)
    }
}
