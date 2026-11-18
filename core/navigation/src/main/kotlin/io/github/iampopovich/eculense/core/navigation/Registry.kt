package io.github.iampopovich.eculense.core.navigation

import androidx.annotation.DrawableRes
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavKey
import kotlin.reflect.KClass

/**
 * Пункт нижнего меню. Фича регистрирует его сама:
 * single(named("dashboard")) { TopLevelEntry(DashboardDestination, "Панель", R.drawable.ic_dashboard, 10) }
 * Квалификатор named(...) обязателен: без него Koin оставит только последнюю регистрацию.
 */
data class TopLevelEntry(
    val destination: NavKey,
    val label: String,
    @param:DrawableRes val icon: Int,
    val order: Int,
)

/** Фича объявляет: «я умею показывать экраны этого типа». Другие фичи по наличию решают, рисовать ли ссылку. */
data class RouteHandler(val routeClass: KClass<out NavKey>)

/**
 * Возможности конкретной сборки (флейвора `ai` в :app): общий код узнаёт о них без ссылок на необязательные модули.
 * Нет регистрации — сборка без ИИ-модулей, ничего не обещаем.
 */
data class BuildCapabilities(
    /** true — сборка «Локально» (модель на телефоне); false — облачная сборка, где пункт «На устройстве» недоступен. */
    val onDeviceModels: Boolean,
)

/** Вкладки-хабы: экран вкладки — список пунктов, которые регистрируют фичи. */
enum class Hub { PITSTOP, SETTINGS }

/**
 * Пункт хаба. Фича регистрирует его сама, хаб ничего о ней не знает:
 * single(named("connect-hub")) { HubEntry(Hub.SETTINGS, ConnectDestination, "Адаптер", "…", R.drawable.ic_adapter, 10) }
 * Нажатие кладёт destination поверх вкладки; «Назад» возвращает в хаб.
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
