package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.ToolRegistry

/**
 * Инструменты данных машины для агента (реализует :core:aitools; без него чат работает без данных).
 * Только чтение, ответы — компактный JSON без VIN и координат.
 */
interface VehicleToolbox {
    /** Новый набор инструментов на один ход диалога (активная машина берётся в момент вызова). */
    fun registry(): ToolRegistry

    /** Только инструменты с именами из [only] — у каждого эксперта свой набор. */
    fun registry(only: Set<String>): ToolRegistry = ToolRegistry { tools(registry().tools.filter { it.name in only }) }

    /** Подпись прогресса для пользователя: «ИИ смотрит ошибки…». */
    fun progressLabel(toolName: String): String

    /**
     * Сводка данных машины одним текстом (ответы инструментов профиля, ошибок, находок, ТО и последней записи) — для
     * моделей на телефоне, которые инструменты не вызывают: числа всё равно считает приложение. null — нет данных.
     */
    suspend fun digest(): String? = null

    /**
     * Сводка только из разделов [sections] (имена инструментов, в этом порядке): данные под вопрос, а не всё подряд.
     * [days] — период трендов и поездок. По умолчанию — полная [digest].
     */
    suspend fun digest(sections: List<String>, days: Int): String? = digest()
}
