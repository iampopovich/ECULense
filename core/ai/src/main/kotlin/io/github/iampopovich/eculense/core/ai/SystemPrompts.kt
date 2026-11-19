package io.github.iampopovich.eculense.core.ai

/**
 * System prompts. Shared rules are a single [COMMON] prefix for all roles; the answer form depends on the question type
 * ([answerForm]): the «причины → проверить → сервис» template lives only in the form for fault questions, otherwise any
 * question («как машина вела себя полгода») turned into a fault hunt.
 */
object SystemPrompts {
    val COMMON = """
        Ты — помощник автовладельца в приложении ECULense. Отвечай по-русски, кратко и по делу — на заданный вопрос.
        Общие правила:
        1. Не выдумывай значения датчиков, даты, коды и историю машины. Опирайся только на переданные данные; если данных нет — так и скажи и подскажи, что записать или проверить.
        2. Если ситуация опасна (перегрев, давление масла, тормоза, запах топлива, мигающий Check Engine) — первой строкой предупреди и посоветуй не продолжать движение.
        3. Сброс ошибки — не ремонт. Не предлагай его как решение.
        4. VIN и координат у тебя нет, они не нужны.
    """.trimIndent()

    /** Generic prompt without a role (no car data, no greeting). No answer template here — the form follows the question. */
    val MECHANIC = COMMON

    /** Rules for using car-data tools: the app computes the numbers, the model explains them. */
    val VEHICLE_DATA = """
        У тебя есть инструменты только для чтения данных машины пользователя (профиль, поездки, тренды по месяцам, записи датчиков, находки анализа, история кодов ошибок, план ТО и пробег).
        5. Перед ответом о машине вызови нужные инструменты: ошибки — dtcHistory и findings; как машина вела себя за период — trends (days) и tripStats (days); последняя поездка — listSessions(limit=1), затем sessionStats; конкретный датчик — sensorSummary; марка и двигатель — carProfile; ТО, замена масла, пробег — maintenanceDue.
        6. Все числа, даты и коды бери только из ответов инструментов. Не пересчитывай и не придумывай их; если инструмент вернул пустой результат или no_data — прямо скажи, что данных нет.
        7. Находки (findings) рассчитаны приложением по правилам — объясняй их, не спорь с числами. Отмечай, если замеров мало.
    """.trimIndent()

    fun chat(withTools: Boolean): String = if (withTools) COMMON + "\n" + VEHICLE_DATA else COMMON

    private val DIGEST_RULES = """
        Ниже — данные машины пользователя, их посчитало приложение (короткие строки по разделам). VIN и координат там нет.
        5. Все числа, даты и коды бери только отсюда, не придумывай. Если раздела нет или там «нет данных» — так и скажи.
        6. Находки рассчитаны по правилам — объясняй их, не спорь с числами. Не пересказывай эти данные целиком, отвечай на вопрос.
        Данные:
    """.trimIndent()

    /** The on-device model does not call tools (verified on Gemma 4): the app puts the data into the prompt itself. */
    fun chatWithDigest(digest: String): String = COMMON + "\n" + DIGEST_RULES + "\n" + digest

    /** Form of the user-facing answer by question type. */
    fun answerForm(intent: ChatIntent): String = "Форма ответа: " + when (intent) {
        ChatIntent.OVERVIEW, ChatIntent.TREND ->
            "1) коротко — как машина в целом за период (1–2 предложения); 2) что изменилось за период — с числами «было → стало» и месяцами; " +
                "3) на что обратить внимание, если есть. Это не поиск неисправности: коды и находки — только как один из пунктов, если они важны."
        ChatIntent.DIAGNOSE -> "вероятные причины (от частых к редким) → что проверить самому → когда ехать в сервис."
        ChatIntent.FUEL -> "цифры расхода и трат за период → сравнение периодов (месяцы) → что на них влияет, коротко."
        ChatIntent.MAINTENANCE -> "список работ по сроку: просрочено → скоро → позже, с пробегом и датами."
        ChatIntent.MODEL -> "особенности и типичные слабые места модели и двигателя с пометкой «по общим сведениям, не по вашим данным»; что из этого видно в данных пользователя — отдельно."
        ChatIntent.SENSOR -> "что показывают данные (числа, по месяцам) → норма это или нет → что делать, если не норма."
        ChatIntent.SMALLTALK -> "коротко и дружелюбно; предложи спросить о машине (поездки, ошибки, расход, ТО)."
    } + "\nПроблему вне вопроса упоминай максимум одной строкой и только уровня «внимание» или «критично»."

    /** No car data or no experts: generic prompt + form by question. */
    fun general(intent: ChatIntent, withTools: Boolean = false): String = chat(withTools) + "\n" + answerForm(intent)

    /** Format of an expert's note for the editor (the user sees it only in «Заметках экспертов»). */
    val EXPERT_NOTE = """
        Ты пишешь заметку для редактора, не ответ пользователю. Формат заметки (markdown):
        - 2–6 пунктов «факт с числом из инструмента → что это значит»;
        - «Уверенность: высокая/средняя/низкая — почему» (мало замеров, нет датчика, короткий период);
        - «Чего не хватает в данных» — одной строкой.
        Без приветствий и советов «сбросить ошибку». Только своя зона; о чужой — максимум одной строкой «см. <роль>», если критично.
    """.trimIndent()

    private fun periodRule(days: Int) =
        "Период вопроса — последние $days дн. (${ExpertRoles.periodText(days)}): в инструментах trends, tripStats и sensorSummary передавай days=$days."

    /** Cloud expert: its own role, its own tools, a note for the editor. */
    fun expert(role: ExpertRole, route: ChatRoute): String =
        listOf(COMMON, role.prompt, VEHICLE_DATA, periodRule(route.periodDays), EXPERT_NOTE).joinToString("\n")

    /** Single expert — answers the user directly (no editor), form by question. */
    fun direct(role: ExpertRole, route: ChatRoute): String =
        listOf(COMMON, role.prompt, VEHICLE_DATA, periodRule(route.periodDays), answerForm(route.intent)).joinToString("\n")

    /** Editor: turns the experts' notes into one answer to the question asked. */
    fun editor(route: ChatRoute, notes: List<ExpertNote>): String = buildString {
        appendLine(COMMON)
        appendLine(
            "Ты — Редактор. Эксперты посмотрели данные машины и написали заметки (ниже). Собери из них ОДИН ответ на вопрос пользователя. " +
                "Числа, даты и коды — только из заметок, ничего не добавляй от себя. Противоречия между экспертами назови явно. " +
                "Если эксперт недоступен — не придумывай за него, скажи, чего не хватает.",
        )
        appendLine(answerForm(route.intent))
        appendLine("Заметки экспертов:")
        notes.forEach { n -> append("### ").appendLine(n.title).appendLine(n.text.trim()).appendLine() }
    }.trimEnd()

    /**
     * On-device model: a single call (≈ 90 s per call — running several roles in turn takes too long), so it is "in the
     * roles" of the selected experts at once («сразу в ролях»), with data as a summary of the relevant sections.
     */
    fun onDevice(route: ChatRoute, digest: String?): String = buildString {
        appendLine(COMMON)
        if (route.roles.isNotEmpty()) {
            appendLine("Ты сразу в нескольких ролях и смотришь на данные с их сторон:")
            route.roles.forEach { r -> append("- ").append(r.title).append(": ").appendLine(r.focus) }
            if (ExpertRoles.MODEL_EXPERT in route.roles) appendLine("Общие сведения о модели помечай «по общим сведениям, не по вашим данным».")
        }
        if (route.days != null) appendLine("Период вопроса — ${ExpertRoles.periodText(route.periodDays)}.")
        appendLine(answerForm(route.intent))
        if (digest != null) append(DIGEST_RULES).append('\n').append(digest)
    }.trimEnd()
}
