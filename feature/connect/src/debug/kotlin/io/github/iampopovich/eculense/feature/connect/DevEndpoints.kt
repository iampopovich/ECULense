package io.github.iampopovich.eculense.feature.connect

import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint

/**
 * Expanded state of the «For development» section: process lifetime only (collapsed again after the app restarts),
 * so a test pass that opened it once doesn't have to reopen it on every visit.
 */
private var devExpanded by mutableStateOf(false)

/**
 * R-I (debug builds only): simulated endpoints (demo adapters, PC emulator, replay) in a collapsed section at the
 * bottom of the adapter list, never mixed with real adapters. Release builds have none (src/release copy is empty).
 */
internal fun LazyListScope.devEndpoints(endpoints: List<AdapterEndpoint>, enabled: Boolean, onConnect: (AdapterEndpoint) -> Unit) {
    if (endpoints.isEmpty()) return
    item(key = "dev-header") {
        val state = stringResource(if (devExpanded) R.string.connect_dev_expanded else R.string.connect_dev_collapsed)
        ListItem(
            headlineContent = { Text(stringResource(R.string.connect_dev_section), style = MaterialTheme.typography.titleSmall) },
            trailingContent = {
                Icon(
                    painterResource(R.drawable.connect_ic_expand_more),
                    contentDescription = null,
                    modifier = Modifier.rotate(if (devExpanded) 180f else 0f),
                )
            },
            modifier = Modifier
                .clickable(role = Role.Button) { devExpanded = !devExpanded }
                .semantics { stateDescription = state },
        )
    }
    if (devExpanded) {
        items(endpoints, key = { "dev-" + it.kind + it.address }) { endpoint -> EndpointRow(endpoint, enabled, onConnect) }
    }
}
