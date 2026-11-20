package io.github.iampopovich.eculense.core.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.iampopovich.eculense.core.analytics.LiveAlertKind
import io.github.iampopovich.eculense.core.data.TripAlertNotifier
import io.github.iampopovich.eculense.core.navigation.DeepLinks

/**
 * C4 channel «Trip warnings» (high importance: heads-up with sound/vibration). Threshold alerts use one id per kind
 * and are cancelled when the alert clears; a new code is posted silently and opens the DTC screen.
 */
internal class AndroidTripAlertNotifier(private val context: Context) : TripAlertNotifier {
    override fun liveAlert(kind: LiveAlertKind, value: Double) {
        val title = when (kind) {
            LiveAlertKind.OVERHEAT -> context.getString(R.string.bg_alert_overheat, value)
            LiveAlertKind.NOT_CHARGING -> context.getString(R.string.bg_alert_not_charging, value)
            LiveAlertKind.OVERCHARGE -> context.getString(R.string.bg_alert_overcharge, value)
        }
        val text = context.getString(
            when (kind) {
                LiveAlertKind.OVERHEAT -> R.string.bg_alert_overheat_text
                LiveAlertKind.NOT_CHARGING -> R.string.bg_alert_not_charging_text
                LiveAlertKind.OVERCHARGE -> R.string.bg_alert_overcharge_text
            },
        )
        post(ID_LIVE + kind.ordinal, title, text, silent = false, screen = null)
    }

    override fun clearLiveAlert(kind: LiveAlertKind) {
        runCatching { NotificationManagerCompat.from(context).cancel(ID_LIVE + kind.ordinal) }
    }

    override fun newCode(code: String) {
        post(ID_CODE, context.getString(R.string.bg_alert_new_code, code), context.getString(R.string.bg_alert_new_code_text), silent = true, screen = DeepLinks.DTC)
    }

    private fun post(id: Int, title: String, text: String, silent: Boolean, screen: String?) {
        ensureChannel(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(AndroidNotifier.TAG, "notifications not permitted, skipped: $title")
            return
        }
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            screen?.let { putExtra(DeepLinks.EXTRA_SCREEN, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_ecu)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(if (silent) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setSilent(silent)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .apply { intent?.let { setContentIntent(PendingIntent.getActivity(context, id, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) } }
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
            .onSuccess { Log.i(AndroidNotifier.TAG, "posted $CHANNEL: $title") }
            .onFailure { Log.w(AndroidNotifier.TAG, "notify failed: ${it.message}") }
    }

    companion object {
        const val CHANNEL = "trip_alerts"
        const val ID_LIVE = 4110
        const val ID_CODE = 4120

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.bg_channel_trip_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
                    description = context.getString(R.string.bg_channel_trip_alerts_description)
                },
            )
        }
    }
}
