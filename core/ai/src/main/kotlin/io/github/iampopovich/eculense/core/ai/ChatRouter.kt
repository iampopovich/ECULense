package io.github.iampopovich.eculense.core.ai

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Dispatcher decision: question type, experts (lead first, 0–4) and period in days (null: question without a period). */
data class ChatRoute(val intent: ChatIntent, val roles: List<ExpertRole>, val days: Int?) {
    /** Period for trends and trips: from the question, or half a year. */
    val periodDays: Int get() = days ?: ChatRouter.DEFAULT_DAYS

    /** Summary sections for the on-phone model: by role, no duplicates; car profile first. */
    val digestSections: List<String>
        get() = if (roles.isEmpty()) emptyList() else (listOf("carProfile") + roles.flatMap { it.digestSections }).distinct()
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

    private val greeting = rx("привет|здравств|добр(ый|ое|ой) (день|утро|вечер)|хай|hi\\b|hello|hey|спасибо|благодар|thanks|thank you|кто ты|как дела|who are you")

    /** Words like «что не так / почему» mean a question about a fault, not about condition. */
    private val problem = rx("почему|why|плава|глохн|троит|дёрга|дерга|стуч|проблем|неисправ|не заводит|сломал|сбо[ий]|problem|issue|wrong|stall")

    private val trend = rx("тренд|динамик|изменил|за последн|последни[ех]|за (полгода|год|месяц|недел|квартал)|с (весны|лета|осени|зимы)|over the (last|past)|trend|changed|lately|recently")

    fun route(question: String, today: LocalDate = LocalDate.now()): ChatRoute {
        val q = question.lowercase().replace('ё', 'е').trim()
        val scores = roleKeywords.mapValues { (_, list) -> list.count { it.containsMatchIn(q) } }.filterValues { it > 0 }
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
        return ChatRoute(intent, roles, periodDays)
    }

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
