package io.github.iampopovich.eculense.core.ai

import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import kotlinx.coroutines.flow.Flow

/**
 * On-device model (implemented by the optional :core:ailocal module; without it — cloud providers only).
 * If the on-device model is selected as the provider, [KoogLlmGateway] runs the chat through it, while the saved
 * cloud settings (and key) stay untouched — they can be restored in the wizard.
 */
interface OnDeviceLlm {
    /** Selected local model, ready for chat; null — the «На устройстве» provider is not selected. */
    suspend fun session(): OnDeviceSession?

    /** For the AI wizard summary: what is selected and how the model did in the test; null — nothing selected. */
    val summary: Flow<OnDeviceSummary?>

    /** The user picked a cloud provider: the local model is no longer active (files are kept). */
    suspend fun deactivate()
}

/** Summary of the selected local model: «Gemma 4 E2B» on «GPU», 13 tok/s, comfortable; the UI formats it. */
data class OnDeviceSummary(
    val model: String,
    val backend: String,
    val decodeTokensPerSecond: Double? = null,
    val rating: OnDeviceRating? = null,
)

/** Speed test verdict of the selected local model. */
enum class OnDeviceRating { COMFORT, SLOW, UNSUITABLE }

/**
 * Local model ready for chat. [executor] belongs to the model module (the engine takes seconds to load and holds
 * gigabytes); the gateway does not close it. [streaming] = false — the answer arrives whole (the Koog LiteRT client
 * has no streaming): the gateway shows «Модель думает…». [supportsTools] = false — vehicle data tools are not passed.
 */
class OnDeviceSession(
    val info: ProviderInfo,
    val model: LLModel,
    val executor: PromptExecutor,
    val streaming: Boolean,
    val supportsTools: Boolean,
)
