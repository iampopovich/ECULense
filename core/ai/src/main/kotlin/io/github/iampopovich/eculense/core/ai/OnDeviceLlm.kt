package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.flow.Flow

/**
 * Модель на телефоне (реализует необязательный модуль :core:ailocal; без него — только облачные провайдеры).
 * Если модель на устройстве выбрана провайдером, [KoogLlmGateway] ведёт чат через неё, а сохранённые облачные
 * настройки (и ключ) остаются нетронутыми — к ним можно вернуться в мастере.
 */
interface OnDeviceLlm {
    /** Выбранная локальная модель, готовая к чату; null — провайдер «На устройстве» не выбран. */
    suspend fun session(): OnDeviceSession?

    /** Для сводки мастера ИИ: что выбрано и как модель прошла тест; null — не выбрано. */
    val summary: Flow<OnDeviceSummary?>

    /** Пользователь выбрал облачного провайдера: локальная модель перестаёт быть активной (файлы остаются). */
    suspend fun deactivate()
}

/** Сводка выбранной локальной модели: «Gemma 4 E2B», «GPU · 13 ток/с · Комфортно». */
data class OnDeviceSummary(val model: String, val detail: String)

/**
 * Готовая к чату локальная модель. [executor] принадлежит модулю модели (движок грузится секунды и держит гигабайты),
 * шлюз его не закрывает. [streaming] = false — ответ приходит целиком (клиент Koog LiteRT без стриминга): шлюз
 * показывает «Модель думает…». [supportsTools] = false — инструменты данных машины не передаются.
 */
class OnDeviceSession(
    val info: ProviderInfo,
    val model: LLModel,
    val executor: PromptExecutor,
    val streaming: Boolean,
    val supportsTools: Boolean,
)
