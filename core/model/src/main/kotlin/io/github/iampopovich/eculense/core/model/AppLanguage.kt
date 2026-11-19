package io.github.iampopovich.eculense.core.model

/**
 * Current app language as a BCP-47 tag («en», «ru»…). Read it at the moment of use, not once: the user may switch
 * the in-app language between two questions. The Android implementation lives in :app (per-app locales).
 */
fun interface AppLanguage {
    fun tag(): String

    companion object {
        /** Fallback for code paths and tests without the app's implementation. */
        val ENGLISH: AppLanguage = AppLanguage { "en" }
    }
}
