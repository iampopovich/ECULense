package io.github.iampopovich.eculense

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.navigation.HubEntry
import io.github.iampopovich.eculense.core.navigation.LocalNavigator
import io.github.iampopovich.eculense.core.navigation.Navigator
import io.github.iampopovich.eculense.core.navigation.PitstopDestination
import io.github.iampopovich.eculense.core.navigation.TopLevelEntry
import org.koin.compose.getKoin
import org.koin.compose.navigation3.koinEntryProvider
import org.koin.core.annotation.KoinExperimentalAPI

@OptIn(KoinExperimentalAPI::class)
@Composable
fun EcuLenseApp(deepLink: String? = null, onDeepLinkHandled: () -> Unit = {}) {
    val koin = getKoin()
    val entries = remember { koin.getAll<TopLevelEntry>().sortedBy { it.order } }
    val backStack = rememberNavBackStack(entries.first().destination)
    val navigator = remember(backStack) { Navigator { backStack.add(it) } }

    // Нажатие на уведомление: экран поверх «Пит-стопа», если фича с ним подключена (иначе просто открываем приложение).
    LaunchedEffect(deepLink) {
        val target = DeepLinks.destination(deepLink) ?: return@LaunchedEffect
        val hubs = koin.getAll<HubEntry>()
        val pitstop = entries.firstOrNull { it.destination == PitstopDestination }
        if (pitstop != null && hubs.any { it.destination == target }) {
            backStack.clear()
            backStack.add(pitstop.destination)
            backStack.add(target)
        }
        onDeepLinkHandled()
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            entries.forEach { entry ->
                item(
                    icon = { Icon(painterResource(entry.icon), contentDescription = entry.label) },
                    label = { Text(entry.label) },
                    // Вкладка = корень стека: подэкран (например, «Адаптер» из настроек) не сбрасывает подсветку.
                    selected = backStack.firstOrNull() == entry.destination,
                    onClick = {
                        backStack.clear()
                        backStack.add(entry.destination)
                    },
                )
            }
        },
    ) {
        CompositionLocalProvider(LocalNavigator provides navigator) {
            Scaffold { padding ->
                NavDisplay(
                    backStack = backStack,
                    modifier = Modifier.padding(padding),
                    onBack = { backStack.removeLastOrNull() },
                    entryDecorators = listOf(
                        rememberSaveableStateHolderNavEntryDecorator(),
                        rememberViewModelStoreNavEntryDecorator(),
                    ),
                    entryProvider = koinEntryProvider(),
                )
            }
        }
    }
}
