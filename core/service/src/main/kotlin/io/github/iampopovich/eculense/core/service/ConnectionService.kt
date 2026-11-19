package io.github.iampopovich.eculense.core.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.iampopovich.eculense.core.obd.AppScope
import io.github.iampopovich.eculense.core.obd.ConnectionController
import io.github.iampopovich.eculense.core.obd.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

class ConnectionService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-created on every start with the same id: after a language change only the name and description update.
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.service_channel_connection), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.service_channel_connection_description)
            },
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.service_connected_title))
            .setContentText(getString(R.string.service_connected_text))
            .setOngoing(true)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL_ID = "connection"
        const val NOTIFICATION_ID = 1
    }
}

/** Connected → start the service, disconnected → stop it. */
internal class ConnectionServiceStarter(context: Context, controller: ConnectionController, scope: CoroutineScope) {
    init {
        scope.launch {
            controller.state.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { connected ->
                val intent = Intent(context, ConnectionService::class.java)
                if (connected) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
            }
        }
    }
}

val serviceModule = module {
    single(createdAtStart = true) { ConnectionServiceStarter(androidContext(), get(), get(AppScope)) }
}
