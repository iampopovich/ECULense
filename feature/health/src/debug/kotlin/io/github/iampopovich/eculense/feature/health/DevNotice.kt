package io.github.iampopovich.eculense.feature.health

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** R-I: only simulated links (debug-only demo adapters) skip saving; the notice text ships in debug builds only. */
@Composable
internal fun NotStoredNotice() {
    Text(stringResource(R.string.health_not_stored), style = MaterialTheme.typography.bodySmall)
}
