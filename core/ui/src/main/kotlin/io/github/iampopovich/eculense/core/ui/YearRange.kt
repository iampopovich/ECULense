package io.github.iampopovich.eculense.core.ui

/** Production years of a model/generation: «2002–2006», «2020–present»; null without a start year. */
object YearRange {
    fun of(from: Int?, to: Int?): UiText? = when {
        from == null -> null
        to == null -> UiText.Res(R.string.common_years_open, listOf(from))
        else -> UiText.Res(R.string.common_years_range, listOf(from, to))
    }
}
