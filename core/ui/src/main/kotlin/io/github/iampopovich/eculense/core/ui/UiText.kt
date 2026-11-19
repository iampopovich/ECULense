package io.github.iampopovich.eculense.core.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * Text that a ViewModel can hold without a Context; resolved in the UI with the current app locale.
 * Arguments may be UiText themselves (resolved first), numbers are formatted by the resource's format string.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Server messages, user input, names: shown as is. */
    data class Raw(val value: String) : UiText

    @Composable
    fun asString(): String = when (this) {
        is Res -> stringResource(id, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
        is Plural -> pluralStringResource(id, count, *args.map { if (it is UiText) it.asString() else it }.toTypedArray())
        is Raw -> value
    }

    fun asString(context: Context): String = when (this) {
        is Res -> context.getString(id, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
        is Plural -> context.resources.getQuantityString(id, count, *args.map { if (it is UiText) it.asString(context) else it }.toTypedArray())
        is Raw -> value
    }
}
