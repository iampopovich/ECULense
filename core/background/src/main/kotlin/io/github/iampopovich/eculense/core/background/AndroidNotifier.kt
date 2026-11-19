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
import io.github.iampopovich.eculense.core.analytics.Finding
import io.github.iampopovich.eculense.core.analytics.MaintenanceDue
import io.github.iampopovich.eculense.core.analytics.MaintenanceStatus
import io.github.iampopovich.eculense.core.analyticstext.AnalyticsText
import io.github.iampopovich.eculense.core.navigation.DeepLinks
import io.github.iampopovich.eculense.core.ui.CarNames
import kotlin.math.abs
import kotlin.math.roundToLong

/** Keys of shown notifications in SharedPreferences: a separate file per kind so jobs don't overwrite each other. */
internal class PrefsAlertLedger(context: Context, private val name: String) : AlertLedger {
    private val prefs = context.getSharedPreferences("alerts", Context.MODE_PRIVATE)
    override suspend fun load(): Set<String> = prefs.getStringSet(name, emptySet()).orEmpty().toSet()
    override suspend fun save(keys: Set<String>) {
        prefs.edit().putStringSet(name, keys).apply()
    }
}

/**
 * Notifications: channel «Analysis» (new findings) and «Maintenance» (maintenance due), texts in the app language. Tapping opens the right screen
 * via [DeepLinks]. Without the POST_NOTIFICATIONS permission (API 33+) we silently skip — the analysis is in the DB anyway.
 */
internal class AndroidNotifier(private val context: Context) : Notifier {
    override fun findings(carName: String, items: List<Finding>) {
        val title = if (items.size == 1) {
            context.getString(R.string.bg_findings_one, AnalyticsText.title(context, items.single()))
        } else {
            context.getString(R.string.bg_findings_many, items.size)
        }
        val lines = items.map { "${AnalyticsText.severity(context, it.severity)}: ${AnalyticsText.title(context, it)} — ${AnalyticsText.message(context, it)}" }
        post(CHANNEL_ANALYSIS, ID_ANALYSIS, title, CarNames.display(context, carName) + " · " + lines.first(), lines, DeepLinks.ANALYSIS)
    }

    override fun maintenance(carName: String, items: List<MaintenanceDue>) {
        val title = if (items.size == 1) {
            context.getString(R.string.bg_maintenance_one, AnalyticsText.maintenanceTitle(context, items.single().item))
        } else {
            context.getString(R.string.bg_maintenance_many, items.size)
        }
        val lines = items.map { "${AnalyticsText.maintenanceTitle(context, it.item)} — ${it.describe(context)}" }
        post(CHANNEL_MAINTENANCE, ID_MAINTENANCE, title, CarNames.display(context, carName) + " · " + lines.first(), lines, DeepLinks.MAINTENANCE)
    }

    private fun post(channel: String, id: Int, title: String, text: String, lines: List<String>, screen: String) {
        ensureChannels(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "notifications not permitted, skipped: $title")
            return
        }
        val style = NotificationCompat.InboxStyle().also { s -> lines.take(6).forEach { s.addLine(it) } }
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_ecu)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setAutoCancel(true)
            .setContentIntent(openScreen(screen, id))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, n) }
            .onSuccess { Log.i(TAG, "posted $channel: $title") }
            .onFailure { Log.w(TAG, "notify failed: ${it.message}") }
    }

    private fun openScreen(screen: String, requestCode: Int): PendingIntent? {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            putExtra(DeepLinks.EXTRA_SCREEN, screen)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        } ?: return null
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val TAG = "EcuBackground"
        const val CHANNEL_ANALYSIS = "analysis"
        const val CHANNEL_MAINTENANCE = "maintenance"
        const val ID_ANALYSIS = 4101
        const val ID_MAINTENANCE = 4102

        /** Called before every post: re-creating a channel with the same id updates its name after a language change. */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ANALYSIS, context.getString(R.string.bg_channel_analysis), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.bg_channel_analysis_description)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MAINTENANCE, context.getString(R.string.bg_channel_maintenance), NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = context.getString(R.string.bg_channel_maintenance_description)
                },
            )
        }
    }
}

/** «overdue by 300 km», «in 9 days», «800 km left» — whichever comes first. */
internal fun MaintenanceDue.describe(context: Context): String {
    val res = context.resources
    val parts = buildList {
        kmLeft?.let { km ->
            val v = abs(km).roundToLong().toInt()
            add(context.getString(if (km <= 0) R.string.bg_overdue_by_km else R.string.bg_km_left, v))
        }
        daysLeft?.let { d ->
            val n = abs(d).toInt()
            add(res.getQuantityString(if (d <= 0) R.plurals.bg_due_days_ago else R.plurals.bg_in_days, n, n))
        }
    }
    val prefix = context.getString(if (status == MaintenanceStatus.OVERDUE) R.string.bg_overdue else R.string.bg_soon)
    return if (parts.isEmpty()) prefix else parts.joinToString(", ")
}
