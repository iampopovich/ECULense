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
import io.github.iampopovich.eculense.core.analytics.Severity
import io.github.iampopovich.eculense.core.navigation.DeepLinks
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
 * Notifications: channel «Анализ» (new findings) and «Обслуживание» (maintenance due). Tapping opens the right screen
 * via [DeepLinks]. Without the POST_NOTIFICATIONS permission (API 33+) we silently skip — the analysis is in the DB anyway.
 */
internal class AndroidNotifier(private val context: Context) : Notifier {
    override fun findings(carName: String, items: List<Finding>) {
        val title = if (items.size == 1) "Анализ: ${items.single().title}" else "Анализ: новых находок — ${items.size}"
        val lines = items.map { "${it.severity.label}: ${it.title} — ${it.message}" }
        post(CHANNEL_ANALYSIS, ID_ANALYSIS, title, "$carName · " + lines.first(), lines, DeepLinks.ANALYSIS)
    }

    override fun maintenance(carName: String, items: List<MaintenanceDue>) {
        val title = if (items.size == 1) "Пора ТО: ${items.single().item.title}" else "Пора ТО: работ — ${items.size}"
        val lines = items.map { "${it.item.title} — ${it.describe()}" }
        post(CHANNEL_MAINTENANCE, ID_MAINTENANCE, title, "$carName · " + lines.first(), lines, DeepLinks.MAINTENANCE)
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

        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ANALYSIS, "Анализ", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Новые находки после поездки или импорта (предупреждения и критичные)"
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_MAINTENANCE, "Обслуживание", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Напоминания о плановом ТО"
                },
            )
        }
    }
}

internal val Severity.label: String
    get() = when (this) {
        Severity.CRITICAL -> "Критично"
        Severity.WARNING -> "Внимание"
        Severity.INFO -> "Справка"
        Severity.NO_DATA -> "Нет данных"
        Severity.OK -> "В норме"
    }

/** «просрочено на 300 км», «через 9 дн.», «осталось 800 км» — whichever comes first. */
internal fun MaintenanceDue.describe(): String {
    val parts = buildList {
        kmLeft?.let { km -> val v = abs(km).roundToLong(); add(if (km <= 0) "просрочено на $v км" else "осталось $v км") }
        daysLeft?.let { d -> add(if (d <= 0) "срок прошёл ${abs(d)} дн. назад" else "через $d дн.") }
    }
    val prefix = if (status == MaintenanceStatus.OVERDUE) "просрочено" else "скоро"
    return if (parts.isEmpty()) prefix else parts.joinToString(", ")
}
