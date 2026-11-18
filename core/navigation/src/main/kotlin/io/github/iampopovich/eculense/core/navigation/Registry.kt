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

fun interface Navigator {
    fun navigate(destination: NavKey)
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("Navigator is not provided") }
