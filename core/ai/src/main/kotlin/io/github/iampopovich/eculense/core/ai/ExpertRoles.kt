package io.github.iampopovich.eculense.core.ai

/** Question type: it determines the experts, the data and the answer shape (rather than one template for everything). */
enum class ChatIntent { DIAGNOSE, OVERVIEW, TREND, FUEL, MAINTENANCE, MODEL, SENSOR, SMALLTALK }

/**
 * Expert role: who looks at the data and from which angle. [title], [focus] and [prompt] are model-facing English
 * (the UI shows role names from feature resources by [id]); [tools] — names of :core:aitools tools available to
 * the role (each expert has its own set); [digestSections] — digest sections for the on-device model;
 * [keywords] — regex fragments for [ChatRouter] (matched from the start of a word, RU + EN; kept in [RouterKeywords]
 * because they match the user's input).
 */
data class ExpertRole(
    val id: String,
    val title: String,
    val focus: String,
    val prompt: String,
    val tools: Set<String>,
    val digestSections: List<String>,
    val keywords: List<String>,
)

/**
 * Expert note for the editor and for the «Expert notes» block under the answer. [title] is the English role title
 * (for the editor prompt); [failed] notes carry our own English placeholder, the UI shows a localized text instead.
 */
data class ExpertNote(val roleId: String, val title: String, val text: String, val failed: Boolean = false)

/**
 * Role registry — data, not code in a when: adding a role = adding an element (+ its keywords in [RouterKeywords]).
 * The role prompt says who you are, what you look at and what you don't touch; the note format and common rules
 * live in [SystemPrompts].
 */
object ExpertRoles {
    val DIAGNOSTICIAN = ExpertRole(
        id = "diagnostician",
        title = "Diagnostician",
        focus = "trouble codes, how they relate to each other, urgency, Check Engine",
        prompt = """
            You are the Diagnostician. You look at diagnostic trouble codes (DTC): which ones occurred, how many times, when first and last seen,
            how the codes relate to each other and to the analysis findings, how urgent it is, whether Check Engine is on. You do not touch
            fuel consumption, trips or the maintenance schedule (see Economist, Operations analyst, Service advisor).
        """.trimIndent(),
        tools = setOf("dtcHistory", "findings", "carProfile"),
        digestSections = listOf("dtcHistory", "findings"),
        keywords = RouterKeywords.DIAGNOSTICIAN,
    )

    val ENGINE = ExpertRole(
        id = "engine",
        title = "Engine specialist",
        focus = "mixture, fuel trims, MAF, oxygen sensors, misfires, idle",
        prompt = """
            You are the Engine specialist. You look at how the engine runs: fuel trims (LTFT/STFT per bank), mixture, MAF, oxygen sensors,
            misfires, rpm and idle stability — from the recordings and their change over the period. You do not touch codes as such
            (see Diagnostician), cooling (see Cooling specialist) or money (see Economist).
        """.trimIndent(),
        tools = setOf("sensorSummary", "sessionStats", "findings", "trends"),
        digestSections = listOf("trends", "findings"),
        keywords = RouterKeywords.ENGINE,
    )

    val COOLING = ExpertRole(
        id = "cooling",
        title = "Cooling specialist",
        focus = "coolant temperature, warm-up, overheating, thermostat",
        prompt = """
            You are the Cooling specialist. You look at the coolant temperature: median and maximums by month, warm-up time,
            overheating episodes, signs of a faulty thermostat (long warm-up, low operating temperature) and fan. You do not touch
            the mixture or codes unrelated to cooling (see Engine specialist, Diagnostician).
        """.trimIndent(),
        tools = setOf("sensorSummary", "trends", "findings"),
        digestSections = listOf("trends", "findings"),
        keywords = RouterKeywords.COOLING,
    )

    val ELECTRICAL = ExpertRole(
        id = "electrical",
        title = "Electrician",
        focus = "battery, charging, vehicle voltage",
        prompt = """
            You are the Electrician. You look at the vehicle voltage with the engine running (median, minimums, dips) and how it changes by
            month: whether the alternator charges, whether the battery is running down. Remember: the adapter voltage (ATRV) is usually 0.2–0.3 V
            below the real one. You do not touch the mixture, cooling or fuel consumption (see Engine specialist, Cooling specialist, Economist).
        """.trimIndent(),
        tools = setOf("sensorSummary", "trends", "findings"),
        digestSections = listOf("trends", "findings"),
        keywords = RouterKeywords.ELECTRICAL,
    )

    val USAGE = ExpertRole(
        id = "usage",
        title = "Operations analyst",
        focus = "trips, mileage, driving modes and style, idling, how the car \"behaves\" over the period",
        prompt = """
            You are the Operations analyst. You look at how the car is used and how it "behaves" over the period: number and length of trips, mileage,
            share of time standing still (traffic jams, warming up in place), driving modes, changes by month (trends). You do not diagnose codes
            (see Diagnostician) and do not count money (see Economist).
        """.trimIndent(),
        tools = setOf("trends", "tripStats", "listSessions"),
        digestSections = listOf("trends", "tripStats", "listSessions"),
        keywords = RouterKeywords.USAGE,
    )

    val FUEL = ExpertRole(
        id = "fuel",
        title = "Economist",
        focus = "fuel consumption, fuel spending, comparison of periods",
        prompt = """
            You are the Economist. You look at fuel consumption (l/100 km) and fuel spending over the period, compare months with each other and
            link changes to the driving mode (share of standing time, trip length). Say so if consumption is estimated from MAF rather than measured.
            You do not touch trouble codes (see Diagnostician).
        """.trimIndent(),
        tools = setOf("tripStats", "trends"),
        digestSections = listOf("tripStats", "trends"),
        keywords = RouterKeywords.FUEL,
    )

    val SERVICE = ExpertRole(
        id = "service",
        title = "Service advisor",
        focus = "maintenance schedule, what is due for replacement, odometer",
        prompt = """
            You are the Service advisor. You look at the maintenance plan: which works are overdue, which are due soon, how much is left by mileage
            and by time, how accurate the odometer is (entered or estimated from trips). You do not diagnose from sensors (see Engine specialist).
        """.trimIndent(),
        tools = setOf("maintenanceDue", "carProfile"),
        digestSections = listOf("maintenanceDue"),
        keywords = RouterKeywords.SERVICE,
    )

    val MODEL_EXPERT = ExpertRole(
        id = "model_expert",
        title = "Model expert",
        focus = "features of this make and engine, typical weak spots",
        prompt = """
            You are the Model expert. From the car profile (make, model, year, engine) you describe the features and typical weak spots
            of this model and engine. This is general knowledge, not the user's data: mark every such statement
            "based on general knowledge, not on your data". Do not invent the user's mileage, numbers or codes.
        """.trimIndent(),
        tools = setOf("carProfile"),
        digestSections = emptyList(),
        keywords = RouterKeywords.MODEL_EXPERT,
    )

    val ALL: List<ExpertRole> = listOf(DIAGNOSTICIAN, ENGINE, COOLING, ELECTRICAL, USAGE, FUEL, SERVICE, MODEL_EXPERT)

    fun byId(id: String): ExpertRole? = ALL.firstOrNull { it.id == id }

    /** «6 months», «a month»… — the period in model-facing prompts (the UI formats periods from its own resources). */
    fun periodText(days: Int): String = when {
        days == 7 -> "a week"
        days == 30 -> "a month"
        days == 365 -> "a year"
        days % 30 == 0 && days in 60..330 -> "${days / 30} months"
        else -> "$days days"
    }
}
