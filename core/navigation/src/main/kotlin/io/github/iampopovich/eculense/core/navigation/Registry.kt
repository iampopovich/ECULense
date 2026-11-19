package io.github.iampopovich.eculense.core.navigation

import androidx.annotation.DrawableRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlin.reflect.KClass

/**
 * Bottom navigation item. A feature registers it itself:
 * single(named("dashboard")) { TopLevelEntry(DashboardDestination, "Панель", R.drawable.ic_dashboard, 10) }
 * The named(...) qualifier is required: without it Koin keeps only the last registration.
 */
data class TopLevelEntry(
    val destination: NavKey,
    val label: String,
    @param:DrawableRes val icon: Int,
    val order: Int,
)

/** A feature declares: "I can show screens of this type". Other features decide by its presence whether to render a link. */
data class RouteHandler(val routeClass: KClass<out NavKey>)

/**
 * Capabilities of the specific build (`ai` flavor in :app): shared code learns about them without referencing optional modules.
 * No registration means a build without AI modules; nothing is promised.
 */
data class BuildCapabilities(
    /** true: the «Локально» build (on-phone model); false: the cloud build, where the «На устройстве» option is unavailable. */
    val onDeviceModels: Boolean,
)

/** Hub tabs: the tab screen is a list of items registered by features. */
enum class Hub { PITSTOP, SETTINGS }

/**
 * Hub item. A feature registers it itself; the hub knows nothing about it:
 * single(named("connect-hub")) { HubEntry(Hub.SETTINGS, ConnectDestination, "Адаптер", "…", R.drawable.ic_adapter, 10) }
 * A tap pushes the destination on top of the tab; «Назад» returns to the hub.
 */
data class HubEntry(
    val hub: Hub,
    val destination: NavKey,
    val title: String,
    val subtitle: String,
    @param:DrawableRes val icon: Int,
    val order: Int,
)

fun Iterable<HubEntry>.forHub(hub: Hub): List<HubEntry> = filter { it.hub == hub }.sortedBy { it.order }

fun interface Navigator {
    fun navigate(destination: NavKey)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator is not provided") }
