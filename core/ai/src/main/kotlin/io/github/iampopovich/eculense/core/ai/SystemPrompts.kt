package io.github.iampopovich.eculense.core.ai

import java.util.Locale

/**
 * System prompts. All model-facing text is English; the answer language is a parameter ([lang], a BCP-47 tag of the
 * current app language) and every prompt that produces user-visible text starts with [languageLine].
 * Shared rules are one [common] prefix for all roles; the answer form depends on the question type ([answerForm]):
 * the «causes → check → service» template lives only in the form for fault questions, otherwise any question
 * («how did my car behave over half a year») turned into a fault hunt.
 */
object SystemPrompts {
    /** English name of the language: «Russian», «English»… (the tag itself if the JDK does not know it). */
    fun displayLanguage(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    /** First line of every user-facing prompt. */
    fun languageLine(tag: String): String =
        "Always answer in ${displayLanguage(tag)} (language tag: $tag), regardless of the language of the question or the data."

    private val RULES = """
        You are a car owner's assistant in the ECULense app. Answer briefly and to the point — answer the question that was asked.
        Write numbers (decimal separator) and unit abbreviations the way the answer language does; keep the values and units of the data (km, l/100 km, °C, V) and do not convert them.
        General rules:
        1. Do not invent sensor values, dates, codes or the car's history. Rely only on the data you are given; if there is no data, say so and suggest what to record or check.
        2. If the situation is dangerous (overheating, oil pressure, brakes, fuel smell, flashing Check Engine), warn about it in the first line and advise not to keep driving.
        3. Clearing a trouble code is not a repair. Do not offer it as a solution.
        4. You have no VIN and no coordinates, and you do not need them.
    """.trimIndent()

    /** Language line + shared rules: the prefix of every prompt. */
    fun common(lang: String): String = languageLine(lang) + "\n" + RULES

    /** Generic prompt without a role (no car data, no greeting). No answer template here — the form follows the question. */
    fun mechanic(lang: String): String = common(lang)

    /** Rules for using car-data tools: the app computes the numbers, the model explains them. */
    val VEHICLE_DATA = """
        You have read-only tools for the user's car data (profile, trips, monthly trends, sensor recordings, analysis findings, trouble code history, maintenance plan and odometer).
        5. Before answering about the car, call the tools you need: trouble codes — dtcHistory and findings; how the car behaved over a period — trends (days) and tripStats (days); the last trip — listSessions(limit=1), then sessionStats; a specific sensor — sensorSummary; make and engine — carProfile; maintenance, oil change, odometer — maintenanceDue.
        6. Take every number, date and code only from the tool results. Do not recalculate or invent them; if a tool returned an empty result or no_data, say plainly that there is no data.
        7. Findings are computed by the app's rules — explain them, do not argue with the numbers. Mention it when there are few readings.
    """.trimIndent()

    fun chat(withTools: Boolean, lang: String): String = if (withTools) common(lang) + "\n" + VEHICLE_DATA else common(lang)

    private val DIGEST_RULES = """
        Below is the user's car data computed by the app (short lines by section). There is no VIN and no coordinates in it.
        5. Take every number, date and code only from here, do not invent anything. If a section is missing or says "no data", say so.
        6. Findings are computed by rules — explain them, do not argue with the numbers. Do not retell this data in full, answer the question.
        Data:
    """.trimIndent()

    /** The on-device model does not call tools (verified on Gemma 4): the app puts the data into the prompt itself. */
    fun chatWithDigest(digest: String, lang: String): String = common(lang) + "\n" + DIGEST_RULES + "\n" + digest

    /** Form of the user-facing answer by question type. */
    fun answerForm(intent: ChatIntent): String = "Answer form: " + when (intent) {
        ChatIntent.OVERVIEW, ChatIntent.TREND ->
            "1) briefly — how the car is overall over the period (1–2 sentences); 2) what changed over the period — with \"was → now\" numbers and months; " +
                "3) what to keep an eye on, if anything. This is not a fault hunt: codes and findings are only one of the points, if they matter."
        ChatIntent.DIAGNOSE -> "likely causes (from common to rare) → what to check yourself → when to go to a service shop."
        ChatIntent.FUEL -> "fuel consumption and spending figures for the period → comparison of periods (months) → what affects them, briefly."
        ChatIntent.MAINTENANCE -> "list of works by due date: overdue → soon → later, with odometer readings and dates."
        ChatIntent.MODEL -> "features and typical weak spots of the model and engine, marked \"based on general knowledge, not on your data\"; what of this shows in the user's data — separately."
        ChatIntent.SENSOR -> "what the data shows (numbers, by month) → whether it is normal → what to do if it is not."
        ChatIntent.SMALLTALK -> "brief and friendly; suggest asking about the car (trips, trouble codes, fuel consumption, maintenance)."
    } + "\nMention a problem outside the question in at most one line, and only if it is at the \"warning\" or \"critical\" level."

    /** No car data or no experts: generic prompt + form by question. */
    fun general(intent: ChatIntent, lang: String, withTools: Boolean = false): String = chat(withTools, lang) + "\n" + answerForm(intent)

    /** Format of an expert's note for the editor (the user also sees it under «Expert notes»). */
    val EXPERT_NOTE = """
        You are writing a note for the editor, not an answer to the user. Note format (markdown):
        - 2–6 bullets "fact with a number from a tool → what it means";
        - "Confidence: high/medium/low — why" (few readings, missing sensor, short period);
        - "What is missing in the data" — one line.
        No greetings and no advice to "clear the code". Only your own area; about another area at most one line "see <role>", if critical.
    """.trimIndent()

    private fun periodRule(days: Int) =
        "The question's period is the last $days days (${ExpertRoles.periodText(days)}): pass days=$days to the trends, tripStats and sensorSummary tools."

    /** Cloud expert: its own role, its own tools, a note for the editor. */
    fun expert(role: ExpertRole, route: ChatRoute, lang: String): String =
        listOf(common(lang), role.prompt, VEHICLE_DATA, periodRule(route.periodDays), EXPERT_NOTE).joinToString("\n")

    /** Single expert — answers the user directly (no editor), form by question. */
    fun direct(role: ExpertRole, route: ChatRoute, lang: String): String =
        listOf(common(lang), role.prompt, VEHICLE_DATA, periodRule(route.periodDays), answerForm(route.intent)).joinToString("\n")

    /** Editor: turns the experts' notes into one answer to the question asked. */
    fun editor(route: ChatRoute, notes: List<ExpertNote>, lang: String): String = buildString {
        appendLine(common(lang))
        appendLine(
            "You are the Editor. Experts looked at the car data and wrote notes (below). Combine them into ONE answer to the user's question. " +
                "Numbers, dates and codes come only from the notes, add nothing of your own. Name contradictions between experts explicitly. " +
                "If an expert is unavailable, do not make things up for them — say what is missing.",
        )
        appendLine(answerForm(route.intent))
        appendLine("Expert notes:")
        notes.forEach { n -> append("### ").appendLine(n.title).appendLine(n.text.trim()).appendLine() }
    }.trimEnd()

    /**
     * On-device model: a single call (≈ 90 s per call — running several roles in turn takes too long), so it is "in the
     * roles" of the selected experts at once, with data as a digest of the relevant sections.
     */
    fun onDevice(route: ChatRoute, digest: String?, lang: String): String = buildString {
        appendLine(common(lang))
        if (route.roles.isNotEmpty()) {
            appendLine("You play several roles at once and look at the data from each of their angles:")
            route.roles.forEach { r -> append("- ").append(r.title).append(": ").appendLine(r.focus) }
            if (ExpertRoles.MODEL_EXPERT in route.roles) appendLine("Mark general facts about the model as \"based on general knowledge, not on your data\".")
        }
        if (route.days != null) appendLine("The question's period: ${ExpertRoles.periodText(route.periodDays)} back from today.")
        appendLine(answerForm(route.intent))
        if (digest != null) append(DIGEST_RULES).append('\n').append(digest)
    }.trimEnd()
}
