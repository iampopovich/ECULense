package io.github.iampopovich.eculense

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.navigation.FullScreenDestination
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

    // Notification tap: screen on top of «Пит-стоп» if that feature is wired in (otherwise just open the app).
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

    // Full-screen destinations (driving mode) hide the navigation bar/rail.
    val fullScreen = backStack.lastOrNull() is FullScreenDestination
    NavigationSuiteScaffold(
        layoutType = if (fullScreen) NavigationSuiteType.None else NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            entries.forEach { entry ->
                item(
                    icon = { Icon(painterResource(entry.icon), contentDescription = stringResource(entry.labelRes)) },
                    label = { Text(stringResource(entry.labelRes)) },
                    // Tab = stack root: a sub-screen (e.g. «Адаптер» from settings) does not reset the highlight.
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
