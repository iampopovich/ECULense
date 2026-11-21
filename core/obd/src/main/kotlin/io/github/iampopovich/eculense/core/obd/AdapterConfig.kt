package io.github.iampopovich.eculense.core.obd

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf

/** ELM327 protocol numbers (ATSP/ATDPN). Names are rendered by :core:obdtext. */
object ObdProtocols {
    const val ALL = "123456789ABC"
    private const val CAN = "6789ABC"
    private const val K_LINE = "345"

    fun isKnown(p: Char): Boolean = p in ALL
    fun isCan(p: Char): Boolean = p in CAN

    /** ISO 9141-2 and ISO 14230-4 (KWP2000): the bus falls asleep without traffic. */
    fun isKLine(p: Char): Boolean = p in K_LINE

    /** ISO 14230-4 KWP2000 (5-baud or fast init): tester present (3E) may keep the bus awake. */
    fun isKwp(p: Char): Boolean = p == '4' || p == '5'
}

/** ELM327 adaptive timing (ATAT0/1/2). AUTO = ATAT1 on CAN, ATAT2 on K-Line. */
enum class AdaptiveTiming { AUTO, OFF, NORMAL, AGGRESSIVE }

/**
 * User adapter settings (A11), applied on the next connect. [protocol] null = automatic search;
 * [extraInit] = validated AT/ST commands sent after the standard init.
 */
data class AdapterSettings(
    val protocol: Char? = null,
    val extraInit: List<String> = emptyList(),
    val adaptiveTiming: AdaptiveTiming = AdaptiveTiming.AUTO,
)

/**
 * What the app learned about one adapter (+ car): the detected protocol (A5), commands the adapter answered with
 * '?' (A6, never sent again) and whether the response-count suffix works (A7; null = not tried yet).
 */
data class AdapterProfile(
    val protocol: Char? = null,
    val unsupported: Set<String> = emptySet(),
    val countSuffix: Boolean? = null,
)

/** Persistence of [AdapterSettings] and per-adapter [AdapterProfile]s. Android: DataStore in :core:data. */
interface AdapterPrefs {
    val settings: Flow<AdapterSettings>
    suspend fun setSettings(settings: AdapterSettings)
    suspend fun profile(adapterKey: String): AdapterProfile?
    suspend fun saveProfile(adapterKey: String, profile: AdapterProfile)

    /** R2.7: «host:port» of the Wi-Fi adapter found in an imported Car Scanner backup; null = none. */
    val suggestedWifi: Flow<String?> get() = flowOf(null)
    suspend fun setSuggestedWifi(address: String?) {}
}

/** In-memory prefs: the default without Android storage and in tests. */
class InMemoryAdapterPrefs(initial: AdapterSettings = AdapterSettings()) : AdapterPrefs {
    private val state = MutableStateFlow(initial)
    val profiles = mutableMapOf<String, AdapterProfile>()
    override val settings: Flow<AdapterSettings> = state.asStateFlow()
    override suspend fun setSettings(settings: AdapterSettings) {
        state.value = settings
    }
    override suspend fun profile(adapterKey: String): AdapterProfile? = profiles[adapterKey]
    override suspend fun saveProfile(adapterKey: String, profile: AdapterProfile) {
        profiles[adapterKey] = profile
    }

    private val wifi = MutableStateFlow<String?>(null)
    override val suggestedWifi: Flow<String?> = wifi.asStateFlow()
    override suspend fun setSuggestedWifi(address: String?) {
        wifi.value = address
    }
}

/** Why a user init command was rejected. */
sealed interface InitCommandProblem {
    val line: String

    /** Not an AT/ST command (OBD requests such as 04 are never allowed). */
    data class NotAtCommand(override val line: String) : InitCommandProblem

    /** Resets or reconfigures the adapter in a way the session cannot work with (ATZ, ATWS, ATD, ATH1, ATE1, ...). */
    data class Forbidden(override val line: String) : InitCommandProblem
}

/** Validation of the extra init commands (A11): only AT/ST commands that do not reset or break the session. */
object InitCommands {
    const val MAX_COMMANDS = 10
    private val shape = Regex("^(AT|ST)[A-Z0-9]{1,16}$")
    private val forbiddenExact = setOf("ATZ", "ATWS", "ATD", "ATH1", "ATE1", "ATLP", "ATMA", "ATPPS", "ATFE", "STSLP")
    private val forbiddenPrefixes = listOf("ATBRD", "ATBRT", "ATPP", "ATMR", "ATMT", "STBR", "STSBR", "STMA", "STM", "ATSP", "ATTP")

    fun normalize(line: String): String = line.replace(" ", "").trim().uppercase()

    /** Non-blank lines, normalized. */
    fun lines(text: String): List<String> = text.lines().map(::normalize).filter { it.isNotEmpty() }

    fun problems(text: String): List<InitCommandProblem> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { problem(it) }

    fun problem(command: String): InitCommandProblem? {
        val c = normalize(command)
        if (!c.matches(shape)) return InitCommandProblem.NotAtCommand(command.trim())
        if (c in forbiddenExact || forbiddenPrefixes.any { c.startsWith(it) }) return InitCommandProblem.Forbidden(command.trim())
        return null
    }

    /** Only the valid commands of [text], at most [MAX_COMMANDS]. */
    fun valid(text: String): List<String> = lines(text).filter { problem(it) == null }.take(MAX_COMMANDS)
}
