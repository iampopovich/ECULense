package io.github.iampopovich.eculense.core.ailocal

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.benchmark
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * Тест скорости в отдельном процессе `:bench` (манифест): нативный LiteRT-LM может упасть (SIGSEGV в драйвере GPU/NPU)
 * или исчерпать память — тогда умирает только этот процесс, а приложение показывает «бэкенд не подходит».
 * Один запуск = один бэкенд в свежем процессе: пик памяти (VmHWM) относится только к нему, после ответа процесс
 * завершается сам. Внутри — `benchmark()` из LiteRT-LM 0.11 (рантайм клиента Koog): init, TTFT, prefill/decode ток/с.
 */
class BenchService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("bench").apply { start() }
        messenger = Messenger(Handler(worker.looper) { msg -> handle(msg); true })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    @OptIn(ExperimentalApi::class)
    private fun handle(msg: Message) {
        val data = msg.data
        val replyTo = msg.replyTo ?: return
        val backend = BackendKind.valueOf(data.getString(KEY_BACKEND) ?: BackendKind.CPU.name)
        val prefill = data.getInt(KEY_PREFILL, PREFILL_TOKENS)
        val decode = data.getInt(KEY_DECODE, DECODE_TOKENS)
        val reply = Bundle()
        val started = SystemClock.elapsedRealtime()
        try {
            val info = benchmark(
                data.getString(KEY_MODEL)!!,
                when (backend) {
                    BackendKind.CPU -> Backend.CPU()
                    BackendKind.GPU -> Backend.GPU()
                    BackendKind.NPU -> Backend.NPU(applicationInfo.nativeLibraryDir)
                },
                prefill,
                decode,
                data.getString(KEY_CACHE),
            )
            reply.putBoolean(KEY_OK, true)
            reply.putDouble(KEY_INIT, info.initTimeInSecond)
            reply.putDouble(KEY_TTFT, info.timeToFirstTokenInSecond)
            reply.putDouble(KEY_PREFILL_TPS, info.lastPrefillTokensPerSecond)
            reply.putDouble(KEY_DECODE_TPS, info.lastDecodeTokensPerSecond)
            reply.putInt(KEY_PREFILL, info.lastPrefillTokenCount)
            reply.putInt(KEY_DECODE, info.lastDecodeTokenCount)
        } catch (t: Throwable) {
            reply.putBoolean(KEY_OK, false)
            reply.putString(KEY_ERROR, (t.message ?: t::class.java.simpleName).take(300))
        }
        reply.putLong(KEY_PEAK, peakRss())
        Log.i(TAG, "bench $backend done in ${SystemClock.elapsedRealtime() - started} ms ok=${reply.getBoolean(KEY_OK)}")
        runCatching { replyTo.send(Message.obtain().apply { this.data = reply }) }
        // Свежий процесс на каждый бэкенд: память движка и VmHWM не переходят в следующий замер.
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    private fun peakRss(): Long = runCatching {
        File("/proc/self/status").readLines().first { it.startsWith("VmHWM:") }.filter { it.isDigit() }.toLong() * 1024
    }.getOrDefault(0L)

    companion object {
        const val TAG = "EcuLocalLlm"
        /** Промпт теста: короткий вопрос с системной подсказкой ≈ 256 токенов; ответ — 128 токенов. */
        const val PREFILL_TOKENS = 256
        const val DECODE_TOKENS = 128
        internal const val KEY_MODEL = "model"
        internal const val KEY_BACKEND = "backend"
        internal const val KEY_CACHE = "cache"
        internal const val KEY_OK = "ok"
        internal const val KEY_ERROR = "error"
        internal const val KEY_INIT = "init"
        internal const val KEY_TTFT = "ttft"
        internal const val KEY_PREFILL_TPS = "prefill_tps"
        internal const val KEY_DECODE_TPS = "decode_tps"
        internal const val KEY_PREFILL = "prefill"
        internal const val KEY_DECODE = "decode"
        internal const val KEY_PEAK = "peak"
    }
}

/** Запуск одного бэкенда в процессе `:bench` и ожидание ответа или гибели процесса. */
class BenchRunner(private val context: Context) {
    suspend fun run(model: File, backend: BackendKind, cacheDir: File, timeoutMs: Long = 8 * 60_000L): BackendRun {
        val result = withTimeoutOrNull(timeoutMs) { runOnce(model, backend, cacheDir) }
        return result ?: BackendRun(backend, ok = false, error = "тест не уложился в ${timeoutMs / 60_000} мин — слишком медленно")
    }

    private suspend fun runOnce(model: File, backend: BackendKind, cacheDir: File): BackendRun = suspendCancellableCoroutine { cont ->
        val app = context.applicationContext
        var connection: ServiceConnection? = null
        fun finish(run: BackendRun) {
            if (!cont.isActive) return
            connection?.let { runCatching { app.unbindService(it) } }
            cont.resume(run)
        }
        val replies = Messenger(Handler(Looper.getMainLooper()) { msg ->
            val d = msg.data
            val peak = d.getLong(BenchService.KEY_PEAK).takeIf { it > 0 }
            finish(
                if (d.getBoolean(BenchService.KEY_OK)) {
                    BackendRun(
                        backend, ok = true,
                        initSeconds = d.getDouble(BenchService.KEY_INIT),
                        ttftSeconds = d.getDouble(BenchService.KEY_TTFT),
                        prefillTokensPerSecond = d.getDouble(BenchService.KEY_PREFILL_TPS),
                        decodeTokensPerSecond = d.getDouble(BenchService.KEY_DECODE_TPS),
                        peakRssBytes = peak,
                        prefillTokens = d.getInt(BenchService.KEY_PREFILL),
                        decodeTokens = d.getInt(BenchService.KEY_DECODE),
                    )
                } else {
                    BackendRun(backend, ok = false, error = d.getString(BenchService.KEY_ERROR), peakRssBytes = peak)
                },
            )
            true
        })
        val crashed = "процесс теста завершился аварийно — нехватка памяти или сбой драйвера"
        connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
                runCatching {
                    binder.linkToDeath({ Handler(Looper.getMainLooper()).post { finish(BackendRun(backend, ok = false, error = crashed)) } }, 0)
                }
                val request = Message.obtain().apply {
                    data = Bundle().apply {
                        putString(BenchService.KEY_MODEL, model.absolutePath)
                        putString(BenchService.KEY_BACKEND, backend.name)
                        putString(BenchService.KEY_CACHE, cacheDir.absolutePath)
                        putInt(BenchService.KEY_PREFILL, BenchService.PREFILL_TOKENS)
                        putInt(BenchService.KEY_DECODE, BenchService.DECODE_TOKENS)
                    }
                    replyTo = replies
                }
                runCatching { Messenger(binder).send(request) }.onFailure { finish(BackendRun(backend, ok = false, error = crashed)) }
            }

            // Процесс умер до ответа (ответ, если успел, уже обработан — finish идемпотентен).
            override fun onServiceDisconnected(name: ComponentName?) {
                Handler(Looper.getMainLooper()).postDelayed({ finish(BackendRun(backend, ok = false, error = crashed)) }, 500)
            }

            override fun onBindingDied(name: ComponentName?) = finish(BackendRun(backend, ok = false, error = crashed))
        }
        cacheDir.mkdirs()
        val bound = app.bindService(Intent(app, BenchService::class.java), connection, Context.BIND_AUTO_CREATE)
        if (!bound) finish(BackendRun(backend, ok = false, error = "не удалось запустить процесс теста"))
        cont.invokeOnCancellation { connection?.let { runCatching { app.unbindService(it) } } }
    }
}

