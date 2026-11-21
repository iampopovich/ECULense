package io.github.iampopovich.eculense.core.obdb

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * OBDb model-year filter: a command applies if the year is ≤ to, or ≥ from, or is in years (union).
 * No filter means it applies to all model years.
 */
data class YearFilter(val from: Int? = null, val to: Int? = null, val years: Set<Int> = emptySet()) {
    fun matches(year: Int): Boolean = (to != null && year <= to) || (from != null && year >= from) || year in years
}

/** Signal formula (signalset v3 `fmt`): value = (raw · mul / div + add), raw is len bits starting at bix. */
data class SignalFormat(
    val bix: Int = 0,
    val len: Int,
    val mul: Double = 1.0,
    val div: Double = 1.0,
    val add: Double = 0.0,
    val signed: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val unit: String? = null,
    /** Multi-byte raw value with the least significant byte first. */
    val blsb: Boolean = false,
    /** Scaled values ≤ [nullMin] or ≥ [nullMax] mean «no value». */
    val nullMin: Double? = null,
    val nullMax: Double? = null,
    /** Raw value → label (enumerations). */
    val map: Map<Long, String> = emptyMap(),
)

data class Signal(
    val id: String,
    val name: String,
    val path: String?,
    val fmt: SignalFormat?,
    val description: String? = null,
    val hidden: Boolean = false,
    /** OBDb `suggestedMetric` («stateOfCharge», «tractionBatteryCurrent», ...). */
    val suggestedMetric: String? = null,
)

/** K-Line fast-init timing (`init`), ms. */
data class InitTiming(val lo: Int, val hi: Int)

/** Command: ECU header (hdr), response address (rax), service → PID (`{"22": "F010"}`), polling frequency, year filter. */
data class SignalCommand(
    val hdr: String,
    val rax: String?,
    val service: String,
    val pid: String,
    val freq: Double?,
    val filter: YearFilter?,
    val protocol: String?,
    val signals: List<Signal>,
    val eax: String? = null,
    val pri: String? = null,
    val tst: String? = null,
    val tmo: String? = null,
    val fcm1: Boolean = false,
    val dbg: Boolean = false,
    val dbgFilter: YearFilter? = null,
    val din: String? = null,
    val dout: String? = null,
    val baud: Int? = null,
    val init: InitTiming? = null,
) {
    fun appliesTo(year: Int): Boolean = filter?.matches(year) ?: true

    /**
     * OBDb marks commands still being verified for a model year as debug (`dbg`, or a year matched by `dbgfilter`).
     * Without a known year only `dbg` counts.
     */
    fun isDebug(year: Int?): Boolean = dbg || (year != null && dbgFilter?.matches(year) == true)

    /** «22 F010 → 7E0». */
    val label: String get() = "$service $pid → $hdr"

    /** OBDb test-case style id: «7E4.7EC.220101» (without rax: «7E0.0101»). */
    val id: String get() = listOfNotNull(hdr, rax).joinToString(".") + "." + service + pid
}

data class Ecu(val hdr: String, val type: String)

data class Signalset(val ecus: List<Ecu>, val commands: List<SignalCommand>) {
    fun forYear(year: Int): List<SignalCommand> = commands.filter { it.appliesTo(year) }
}

/** Parses `signalsets/v3/default.json` as is (OBDb format, CC BY-SA 4.0). Unknown fields are skipped. */
object SignalsetParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(text: String): Signalset {
        val root = json.parseToJsonElement(text).jsonObject
        val ecus = (root["ecu"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            Ecu(o.str("hdr") ?: return@mapNotNull null, o.str("type").orEmpty())
        }
        val commands = (root["commands"] as? JsonArray).orEmpty().mapNotNull { c -> command(c as? JsonObject ?: return@mapNotNull null) }
        return Signalset(ecus, commands)
    }

    private fun command(o: JsonObject): SignalCommand? {
        val cmd = o["cmd"] ?: return null
        val (service, pid) = when (cmd) {
            is JsonObject -> cmd.entries.firstOrNull()?.let { it.key to (it.value as? JsonPrimitive)?.content.orEmpty() } ?: return null
            is JsonPrimitive -> cmd.content.take(2) to cmd.content.drop(2)
            else -> return null
        }
        return SignalCommand(
            hdr = o.str("hdr") ?: "7DF",
            rax = o.str("rax"),
            service = service,
            pid = pid,
            freq = (o["freq"] as? JsonPrimitive)?.doubleOrNull,
            filter = (o["filter"] as? JsonObject)?.let(::filter),
            protocol = o.str("proto"),
            signals = (o["signals"] as? JsonArray).orEmpty().mapNotNull { s -> signal(s as? JsonObject ?: return@mapNotNull null) },
            eax = o.str("eax"),
            pri = o.str("pri"),
            tst = o.str("tst"),
            tmo = o.str("tmo"),
            fcm1 = (o["fcm1"] as? JsonPrimitive)?.booleanOrNull ?: false,
            dbg = (o["dbg"] as? JsonPrimitive)?.booleanOrNull ?: false,
            dbgFilter = (o["dbgfilter"] as? JsonObject)?.let(::filter),
            din = o.str("din"),
            dout = o.str("dout"),
            baud = (o["baud"] as? JsonPrimitive)?.intOrNull,
            init = (o["init"] as? JsonObject)?.let { i ->
                val lo = (i["lo"] as? JsonPrimitive)?.intOrNull
                val hi = (i["hi"] as? JsonPrimitive)?.intOrNull
                if (lo != null && hi != null) InitTiming(lo, hi) else null
            },
        )
    }

    private fun filter(o: JsonObject) = YearFilter(
        from = (o["from"] as? JsonPrimitive)?.intOrNull,
        to = (o["to"] as? JsonPrimitive)?.intOrNull,
        years = (o["years"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.intOrNull }.toSet(),
    )

    private fun signal(o: JsonObject): Signal? {
        val id = o.str("id") ?: return null
        val fmt = (o["fmt"] as? JsonObject)?.let { f ->
            fun d(k: String) = (f[k] as? JsonPrimitive)?.doubleOrNull
            SignalFormat(
                bix = (f["bix"] as? JsonPrimitive)?.intOrNull ?: 0,
                len = (f["len"] as? JsonPrimitive)?.intOrNull ?: return@let null,
                mul = d("mul") ?: 1.0,
                div = d("div") ?: 1.0,
                add = d("add") ?: 0.0,
                signed = (f["sign"] as? JsonPrimitive)?.booleanOrNull ?: false,
                min = d("min"),
                max = d("max"),
                unit = f.str("unit"),
                blsb = (f["blsb"] as? JsonPrimitive)?.booleanOrNull ?: false,
                nullMin = d("nullmin"),
                nullMax = d("nullmax"),
                map = (f["map"] as? JsonObject)?.entries?.mapNotNull { (k, v) ->
                    val key = k.toLongOrNull() ?: return@mapNotNull null
                    val label = when (v) {
                        is JsonPrimitive -> v.content
                        is JsonObject -> (v["description"] as? JsonPrimitive)?.content ?: (v["value"] as? JsonPrimitive)?.content
                        else -> null
                    } ?: return@mapNotNull null
                    key to label
                }?.toMap().orEmpty(),
            )
        }
        return Signal(
            id, o.str("name") ?: id, o.str("path"), fmt, o.str("description"),
            hidden = (o["hidden"] as? JsonPrimitive)?.booleanOrNull ?: false,
            suggestedMetric = o.str("suggestedMetric"),
        )
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}

/** Generation from `generations.yaml`: years inclusive, end = null means still in production. */
data class Generation(val name: String, val startYear: Int, val endYear: Int?) {
    fun contains(year: Int) = year >= startYear && (endYear == null || year <= endYear)
}

/** Minimal parser for OBDb `generations.yaml` (flat list of name/start_year/end_year), without a YAML library. */
object GenerationsParser {
    private val item = Regex("""^\s*-\s*name:\s*"?(.*?)"?\s*$""")
    private val field = Regex("""^\s*(start_year|end_year):\s*(\S+)\s*$""")

    fun parse(yaml: String): List<Generation> {
        val out = mutableListOf<Generation>()
        var name: String? = null
        var start: Int? = null
        var end: Int? = null
        fun flush() {
            val n = name
            val s = start
            if (n != null && s != null) out += Generation(n, s, end)
        }
        yaml.lineSequence().forEach { line ->
            item.find(line)?.let {
                flush()
                name = it.groupValues[1]; start = null; end = null
                return@forEach
            }
            field.find(line)?.let {
                val v = it.groupValues[2].trim('"').toIntOrNull()
                if (it.groupValues[1] == "start_year") start = v else end = v
            }
        }
        flush()
        return out
    }
}
