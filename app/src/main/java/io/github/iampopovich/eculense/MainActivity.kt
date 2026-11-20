package io.github.iampopovich.eculense

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.mutableStateOf
import io.github.iampopovich.eculense.core.data.AutoConnect
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.ui.theme.ECULenseTheme
import org.koin.android.ext.android.inject

/** AppCompatActivity: needed for AppCompatDelegate.setApplicationLocales (in-app language on API < 33). */
class MainActivity : AppCompatActivity() {
    /** Screen from a notification (DeepLinks); reset after navigation. */
    private val deepLink = mutableStateOf<String?>(null)
    private val connection: ConnectionController by inject()
    private val autoConnect: AutoConnect by inject()

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

    // C2: with the app hidden or the screen off only a recording reads the PIDs, so slower tiers are read less often.
    override fun onStart() {
        super.onStart()
        connection.setBackground(false)
        // C5: once per process, the visible activity may start the foreground connection service.
        autoConnect.onAppStart()
    }

    override fun onStop() {
        connection.setBackground(true)
        super.onStop()
    }

    private fun Intent?.screen(): String? = this?.getStringExtra(DeepLinks.EXTRA_SCREEN)
}
