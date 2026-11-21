package io.github.iampopovich.eculense.core.ai

/**
 * The cipher moved to :core:secrets (shared with the MQTT broker password); these aliases keep the AI code and its
 * tests unchanged. The LLM key alias stays «eculense_llm_keys», so stored keys still decrypt.
 */
typealias SecretCipher = io.github.iampopovich.eculense.core.secrets.SecretCipher
typealias KeystoreCipher = io.github.iampopovich.eculense.core.secrets.KeystoreCipher
