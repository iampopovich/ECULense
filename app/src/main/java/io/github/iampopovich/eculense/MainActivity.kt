package io.github.iampopovich.eculense

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.ui.theme.ECULenseTheme

class MainActivity : ComponentActivity() {
    /** Screen from a notification (DeepLinks); reset after navigation. */
    private val deepLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) deepLink.value = intent.screen()
        setContent {
            ECULenseTheme { EcuLenseApp(deepLink = deepLink.value, onDeepLinkHandled = { deepLink.value = null }) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.screen()?.let { deepLink.value = it }
    }

    private fun Intent?.screen(): String? = this?.getStringExtra(DeepLinks.EXTRA_SCREEN)
}
