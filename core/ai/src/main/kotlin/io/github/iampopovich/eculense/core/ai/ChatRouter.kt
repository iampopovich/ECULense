package io.github.iampopovich.eculense.core.ai

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Dispatcher decision: question type, experts (lead first, 0–4) and period in days (null: question without a period). */
data class ChatRoute(
    val intent: ChatIntent,
    val roles: List<ExpertRole>,
    val days: Int?,
    /** Sections the question itself asks for (readiness, last health check), on top of the roles' ones. */
    val topicSections: List<String> = emptyList(),
) {
    /** Period for trends and trips: from the question, or half a year. */
    val periodDays: Int get() = days ?: ChatRouter.DEFAULT_DAYS

    /** Summary sections for the on-phone model: car profile first, then the question's topics, then by role; no duplicates. */
    val digestSections: List<String>
        get() = if (roles.isEmpty()) emptyList() else (listOf("carProfile") + topicSections + roles.flatMap { it.digestSections }).distinct()
}

/**
 * Dispatcher: deterministic, no model. Picks experts and period from the question's words (Russian + English). An unclear
 * question gets an overview (Operations Analyst + Diagnostician + Engine Specialist); greetings and non-car questions get no experts or data.
 */
object ChatRouter {
    const val DEFAULT_DAYS = 180
    const val MAX_ROLES = 4

    /**
     * Word boundaries via explicit lookarounds over Unicode letters: `\b` in JDK 19+ is ASCII-only (does not work on Cyrillic), and
     * the Unicode character class flag (U) that fixes this on the JVM is unknown to Android regex (ICU): PatternSyntaxException on the first question.
     */
    private const val START = "(?<![\\p{L}\\p{N}_])"
    private const val END = "(?![\\p{L}\\p{N}_])"

    internal fun portable(pattern: String) = pattern.replace("\\b", END)

    private fun rx(fragment: String) = Regex(START + "(?:" + portable(fragment) + ")", RegexOption.IGNORE_CASE)

    private val roleKeywords: Map<ExpertRole, List<Regex>> = ExpertRoles.ALL.associateWith { r -> r.keywords.map(::rx) }

    /** Question topics → digest section (tool name); every topic is a Diagnostician question. */
    private val topics: List<Pair<String, List<Regex>>> = listOf(
        "readinessHistory" to RouterKeywords.READINESS.map(::rx),
        "healthChecks" to RouterKeywords.HEALTH_CHECK.map(::rx),
    )

    private val greeting = rx("привет|здравств|добр(ый|ое|ой) (день|утро|вечер)|хай|hi\\b|hello|hey|спасибо|благодар|thanks|thank you|кто ты|как дела|who are you")

    /** Words like «что не так / почему» mean a question about a fault, not about condition. */
    private val problem = rx("почему|why|плава|глохн|троит|дёрга|дерга|стуч|проблем|неисправ|не заводит|сломал|сбо[ий]|problem|issue|wrong|stall")

    private val trend = rx("тренд|динамик|изменил|за последн|последни[ех]|за (полгода|год|месяц|недел|квартал)|с (весны|лета|осени|зимы)|over the (last|past)|trend|changed|lately|recently")

    fun route(question: String, today: LocalDate = LocalDate.now()): ChatRoute {
        val q = question.lowercase().replace('ё', 'е').trim()
        val topicSections = topics.filter { (_, list) -> list.any { it.containsMatchIn(q) } }.map { it.first }
        val scores = roleKeywords.mapValues { (role, list) ->
            list.count { it.containsMatchIn(q) } + if (role == ExpertRoles.DIAGNOSTICIAN) topicSections.size * TOPIC_WEIGHT else 0
        }.filterValues { it > 0 }
        val days = days(q, today)
        if (scores.isEmpty() && greeting.containsMatchIn(q)) return ChatRoute(ChatIntent.SMALLTALK, emptyList(), null)
        // role order in the registry breaks ties on equal scores
        val hit = scores.entries.sortedWith(compareByDescending<Map.Entry<ExpertRole, Int>> { it.value }.thenBy { ExpertRoles.ALL.indexOf(it.key) })
            .map { it.key }
        val lead = hit.firstOrNull()
        val intent = when (lead) {
            null -> ChatIntent.OVERVIEW
            ExpertRoles.USAGE -> if (days != null || trend.containsMatchIn(q)) ChatIntent.TREND else ChatIntent.OVERVIEW
            ExpertRoles.DIAGNOSTICIAN -> ChatIntent.DIAGNOSE
            ExpertRoles.FUEL -> ChatIntent.FUEL
            ExpertRoles.SERVICE -> ChatIntent.MAINTENANCE
            ExpertRoles.MODEL_EXPERT -> ChatIntent.MODEL
            else -> if (problem.containsMatchIn(q)) ChatIntent.DIAGNOSE else ChatIntent.SENSOR
        }
        val roles = when (intent) {
            // overview and trends: operations leads, codes and engine always alongside (otherwise «как машина» = errors only)
            ChatIntent.OVERVIEW, ChatIntent.TREND -> listOf(ExpertRoles.USAGE) + hit + listOf(ExpertRoles.DIAGNOSTICIAN, ExpertRoles.ENGINE)
            // «почему плавают обороты»: lead by topic, Diagnostician second (codes often explain the symptom)
            ChatIntent.DIAGNOSE -> hit + if (lead != ExpertRoles.DIAGNOSTICIAN) listOf(ExpertRoles.DIAGNOSTICIAN) else emptyList()
            else -> hit
        }.distinct().take(MAX_ROLES)
        val periodDays = days ?: if (intent == ChatIntent.OVERVIEW || intent == ChatIntent.TREND) DEFAULT_DAYS else null
        return ChatRoute(intent, roles, periodDays, topicSections)
    }

    /** A readiness / health-check topic outweighs incidental keywords («техосмотр» is not a maintenance question). */
    private const val TOPIC_WEIGHT = 2

    private val halfYear = rx("полгода|пол года|полугод|half a year|half-year|six months|шесть месяц")
    private val nUnits = Regex(portable("(?<!\\d)(\\d{1,3})\\s*-?\\s*(дн|день|дня|дней|сут|days?|недел|нед\\b|weeks?|месяц|мес\\b|months?|год|года|лет|years?)"), RegexOption.IGNORE_CASE)
    private val month = rx("(за|последний|прошлый|этот) месяц|month")
    private val week = rx("(за|последнюю|прошлую|эту) неделю|week")
    private val quarter = rx("квартал|quarter|три месяц")
    private val year = rx("(за|последний|прошлый|этот) год|year|годовой")
    private val season = Regex("с (весны|лета|осени|зимы)", RegexOption.IGNORE_CASE)

    /** Period in days from the question; null if no period is named. */
    fun days(q: String, today: LocalDate = LocalDate.now()): Int? {
        val text = q.lowercase().replace('ё', 'е')
        if (halfYear.containsMatchIn(text)) return 180
        nUnits.find(text)?.let { m ->
            val n = m.groupValues[1].toInt()
            val unit = m.groupValues[2]
            val d = when {
                unit.startsWith("дн") || unit.startsWith("ден") || unit.startsWith("сут") || unit.startsWith("day") -> n
                unit.startsWith("нед") || unit.startsWith("week") -> n * 7
                unit.startsWith("мес") || unit.startsWith("month") -> n * 30
                else -> n * 365
            }
            return d.coerceIn(1, 3_650)
        }
        season.find(text)?.let { m ->
            val startMonth = when (m.groupValues[1]) {
                "весны" -> 3
                "лета" -> 6
                "осени" -> 9
                else -> 12
            }
            var start = LocalDate.of(today.year, startMonth, 1)
            if (start.isAfter(today)) start = start.minusYears(1)
            return ChronoUnit.DAYS.between(start, today).toInt().coerceAtLeast(1)
        }
        if (quarter.containsMatchIn(text)) return 90
        if (week.containsMatchIn(text)) return 7
        if (month.containsMatchIn(text)) return 30
        if (year.containsMatchIn(text)) return 365
        return null
    }
}

/**
 * Router keywords per expert role (regex fragments, RU + EN). Input data matched against the user's question, never
 * shown or sent to the model — this is why they stay Russian here, in the permanently allowlisted router file.
 */
internal object RouterKeywords {
    val DIAGNOSTICIAN = listOf(
        "ошибк", "код", "dtc", "check engine", "чек", "[pubc][0-3][0-9a-f]{3}", "лампа", "горит", "диагност", "неисправн",
        "error", "trouble code", "fault", "warning light", "codes?\\b",
    )

    val ENGINE = listOf(
        "смес", "коррекц", "ltft", "stft", "fuel trim", "maf", "дмрв", "лямбд", "кислород", "o2\\b", "пропуск", "троит", "misfire",
        "холост", "хх\\b", "idle", "оборот", "rpm", "плава", "глохн", "stall", "детонац", "зажиган", "rough",
    )

    val COOLING = listOf(
        "охлажд", "температур", "ож\\b", "антифриз", "тосол", "перегр", "грее", "грет", "прогрев", "термостат", "радиатор", "вентилятор",
        "coolant", "overheat", "temperature", "thermostat", "hot\\b",
    )

    val ELECTRICAL = listOf(
        "аккумулятор", "акб", "батаре", "заряд", "напряжен", "генератор", "вольт", "стартер", "не заводит",
        "battery", "voltage", "alternator", "charging", "starter",
    )

    val USAGE = listOf(
        "ве(дет|ла|л|ли|дут) себя", "как (моя |наша )?(машина|машинка|авто)", "поездк", "пробег", "езжу", "езди", "езда", "стиль", "режим",
        "в целом", "обзор", "итог", "статистик", "тренд", "динамик", "изменил", "простаива", "пробк",
        "behave", "trips?\\b", "driving", "mileage", "overall", "how (is|did|does|has) my car", "summary", "trend", "usage",
    )

    val FUEL = listOf(
        "расход", "топлив", "бензин", "дизел", "солярк", "трач", "затрат", "деньг", "стоимост", "литр", "л/100", "заправ",
        "fuel", "petrol", "gas\\b", "consumption", "mpg", "spend", "cost", "economy",
    )

    val SERVICE = listOf(
        "(пройти|пройду|следующее|очередное|плановое|ближайшее) то\\b", "то-?\\d", "техобслуж", "обслуж", "масл", "замен", "менять",
        "фильтр", "свеч", "ремен", "грм", "регламент", "интервал", "пора ",
        "maintenance", "service", "oil", "replace", "filter", "spark plug", "timing belt",
    )

    /** «Готова ли к техосмотру», «стирали ли ошибки», «сколько прогревов после сброса». */
    val READINESS = listOf(
        "техосмотр", "готов(а|о|ы)? (ли )?к (то|осмотр)\\b", "мониторы? готовност", "готовност", "readiness", "монитор",
        "стира", "стёр", "стер(ли|ла|л)\\b", "сбрасыва", "сброс(или|ила|ил)? (ли )?(ошибк|код)", "удаля(ли|л) (ли )?(ошибк|код)", "после сброса",
        "inspection", "emissions? test", "smog", "mot\\b", "monitors?\\b", "cleared", "erased", "reset (the )?codes", "since (the )?clear",
    )

    /** «Что показала проверка», «результат последней проверки». */
    val HEALTH_CHECK = listOf(
        "проверк", "что показал", "health check", "quick check", "last check", "check result", "(the )?check show",
    )

    val MODEL_EXPERT = listOf(
        "болячк", "слаб(ые|ое) мест", "типичн", "особенност", "надежн", "ресурс", "известн", "sorento", "соренто", "kia\\b", "киа\\b",
        "hyundai", "хендай", "хёндай", "g6cu", "common problems", "known (issues|problems)", "weak (spots|points)", "reliab",
    )
}
