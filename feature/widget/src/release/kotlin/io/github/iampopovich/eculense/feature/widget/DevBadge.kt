package io.github.iampopovich.eculense.feature.widget

import androidx.compose.runtime.Composable

/** R-I: release builds have no simulated links, so no badge (debug copy in src/debug). */
@Composable
internal fun SimulatedBadge() = Unit
