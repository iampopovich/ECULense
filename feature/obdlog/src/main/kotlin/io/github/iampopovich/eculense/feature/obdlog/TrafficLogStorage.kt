package io.github.iampopovich.eculense.feature.obdlog

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import io.github.iampopovich.eculense.core.obd.ObdTrafficLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Whether the traffic log records. Default ON: the log lives in memory only and leaves the phone only on «Share». */
internal interface TrafficLogPrefs {
    val recording: Flow<Boolean>
    suspend fun setRecording(enabled: Boolean)
}

internal class DataStoreTrafficLogPrefs(private val store: DataStore<Preferences>) : TrafficLogPrefs {
    override val recording: Flow<Boolean> = store.data.map { it[RECORDING] ?: true }

    override suspend fun setRecording(enabled: Boolean) {
        store.edit { it[RECORDING] = enabled }
    }

    private companion object {
        val RECORDING = booleanPreferencesKey("record_traffic")
    }
}

/** Applies the saved switch to the in-memory log at app start and on every change. */
internal class TrafficLogPrefsApplier(prefs: TrafficLogPrefs, log: ObdTrafficLog, scope: CoroutineScope) {
    init {
        scope.launch { prefs.recording.collect { log.setEnabled(it) } }
    }
}

/** Writes the export to cache/obdlog and returns a content:// Uri for ACTION_SEND. */
internal class TrafficLogFiles(private val context: Context) {
    suspend fun write(text: String): Uri = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // keep only the latest export
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "ecu-traffic-$stamp.txt")
        file.writeText(text)
        FileProvider.getUriForFile(context, "${context.packageName}.obdlog.files", file)
    }

    private companion object {
        const val DIR = "obdlog"
    }
}

/** Own provider class so this feature's authority never clashes with another feature's FileProvider. */
class ObdLogFileProvider : FileProvider()
