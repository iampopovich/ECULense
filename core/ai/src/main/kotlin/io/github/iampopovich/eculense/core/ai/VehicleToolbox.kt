package io.github.iampopovich.eculense.core.ai

import ai.koog.agents.core.tools.ToolRegistry

/**
 * Инструменты данных машины для агента (реализует :core:aitools; без него чат работает без данных).
 * Только чтение, ответы — компактный JSON без VIN и координат.
 */
interface VehicleToolbox {
    /** Новый набор инструментов на один ход диалога (активная машина берётся в момент вызова). */
    fun registry(): ToolRegistry

    /** Подпись прогресса для пользователя: «ИИ смотрит ошибки…». */
    fun progressLabel(toolName: String): String

    /**
     * Сводка данных машины одним текстом (ответы инструментов профиля, ошибок, находок, ТО и последней записи) — для
     * моделей на телефоне, которые инструменты не вызывают: числа всё равно считает приложение. null — нет данных.
     */
    suspend fun digest(): String? = null
}
