package com.shinigami.client.core.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Logger {

    private const val TAG = "Logger"
    private const val LOG_DIR = "log"

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private val logChannel = Channel<String>(capacity = 1000, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var file: File? = null
    private var writer: BufferedWriter? = null

    @Volatile private var isReady = false

    fun init(context: Context) {
        if (!AppConfig.ENABLE_LOGGER || isReady) return

        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, LOG_DIR).apply { if (!exists()) mkdirs() }

            val dateStr = dateFormat.format(Date())
            file = File(dir, "shngm-log_$dateStr.txt")
            cleanOldLogs(dir)

            writer = BufferedWriter(FileWriter(file, true))

            if (file?.length() == 0L) {
                writeDirectly("=== Shinigami v${AppConfig.VERSION_NAME} ===\n")
            }

            isReady = true
            Log.i(TAG, "Logger initialized at: ${file?.absolutePath}")

            startLogConsumer()
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
        }
    }

    private fun startLogConsumer() {
        scope.launch {
            for (msg in logChannel) {
                writeDirectly(msg)
                writer?.flush()
                checkLogRotation()
            }
        }
    }

    private fun cleanOldLogs(dir: File) {
        try {
            dir.listFiles()
                ?.sortedByDescending { it.lastModified() }
                ?.drop(AppConfig.MAX_LOG_FILES)
                ?.forEach { it.delete() }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clean old logs", e)
        }
    }

    fun v(tag: String, msg: String) = log("V", tag, msg)
    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun i(tag: String, msg: String) = log("I", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String, err: Throwable? = null) {
        log("E", tag, err?.let { "$msg: ${it.message}" } ?: msg)
        err?.let { logErrorTrace(it) }
    }

    private fun log(level: String, tag: String, msg: String) {
        if (!AppConfig.ENABLE_LOGGER) return

        when (level) {
            "V" -> Log.v(tag, msg)
            "D" -> Log.d(tag, msg)
            "I" -> Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
        }

        if (isReady) {
            val time = timeFormat.format(Date())
            logChannel.trySend("$time [$level] $tag: $msg\n")
        }
    }

    private fun logErrorTrace(err: Throwable) {
        if (!isReady) return
        val builder = StringBuilder().apply {
            append("  ↳ ${err.javaClass.simpleName}: ${err.message}\n")
            err.stackTrace.take(5).forEach { append("  at $it\n") }
        }
        logChannel.trySend(builder.toString())
    }

    fun logCrash(err: Throwable) {
        if (!AppConfig.ENABLE_CRASH_LOG) return
        val crash = buildString {
            append("\n╔═══ CRASH ═══════════════════════════════════════════════╗\n")
            append("║ ${err.javaClass.simpleName}: ${err.message}\n")
            err.stackTrace.take(15).forEach { append("║   $it\n") }
            append("╚═════════════════════════════════════════════════════════╝\n")
        }
        logChannel.trySend(crash)
    }

    fun logNetwork(method: String, url: String, code: Int, timeMs: Long) {
        if (!AppConfig.ENABLE_NETWORK_LOG) return
        d("Network", "$method $url → $code (${timeMs}ms)")
    }

    private fun writeDirectly(text: String) {
        try {
            writer?.write(text)
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
        }
    }

    private fun checkLogRotation() {
        val f = file ?: return
        if (f.length() > AppConfig.MAX_LOG_FILE_SIZE) {
            val backup = File(f.parent, "${f.nameWithoutExtension}_${System.currentTimeMillis()}.txt")
            try {
                writer?.close()
            } catch (_: Exception) {}
            if (f.renameTo(backup)) {
                file = File(f.parent, f.name)
            }
            try {
                writer = BufferedWriter(FileWriter(file ?: f, true))
                writeDirectly("=== Rotated from ${backup.name} ===\n")
            } catch (e: Exception) {
                Log.e(TAG, "Rotation failed", e)
            }
        }
    }

    fun shutdown() {
        isReady = false
        logChannel.close()
        try {
            writer?.flush()
            writer?.close()
            writer = null
        } catch (e: Exception) {
            Log.e(TAG, "Shutdown error", e)
        }
    }
}
