package io.github.iampopovich.eculense.feature.connect

import androidx.compose.foundation.lazy.LazyListScope
import io.github.iampopovich.eculense.core.obd.AdapterEndpoint

/** R-I: release builds register no simulated endpoints, so there is no «For development» section (debug copy in src/debug). */
@Suppress("UnusedReceiverParameter", "UNUSED_PARAMETER")
internal fun LazyListScope.devEndpoints(endpoints: List<AdapterEndpoint>, enabled: Boolean, onConnect: (AdapterEndpoint) -> Unit) = Unit
