package io.github.iampopovich.eculense.feature.health

import androidx.compose.runtime.Composable

/** R-I: release builds have no simulated links, so a shown check is always stored; no notice (debug copy in src/debug). */
@Composable
internal fun NotStoredNotice() = Unit
