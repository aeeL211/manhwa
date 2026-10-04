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

    @Volatile private var file: File? = null
    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var isReady = false
    @Volatile private var isInitializing = false

    fun init(context: Context) {
        if (isReady || isInitializing) return
        isInitializing = true

        val appContext = context.applicationContext
        scope.launch {
            initInternal(appContext)
        }
    }

    private fun initInternal(context: Context) {
        try {
            val root = context.getExternalFilesDir(null) ?: context.filesDir
            val dir = File(root, LOG_DIR).apply { if (!exists()) mkdirs() }

            val dateStr = dateFormat.format(Date())
            val targetFile = File(dir, "shngm-log_$dateStr.txt")
            file = targetFile
            cleanOldLogs(dir)

            val isNewFile = !targetFile.exists() || targetFile.length() == 0L
            writer = BufferedWriter(FileWriter(targetFile, true))

            if (isNewFile) {
                writeDirectlyInternal("=== Shinigami v${AppConfig.VERSION_NAME} ===\n")
            }

            isReady = true
            isInitializing = false
            if (AppConfig.DEBUG) {
                Log.i(TAG, "Logger initialized at: ${targetFile.absolutePath}")
            }

            startLogConsumer()
        } catch (e: Exception) {
            isInitializing = false
            Log.e(TAG, "Initialization failed", e)
        }
    }

    private fun startLogConsumer() {
        scope.launch {
            for (msg in logChannel) {
                writeDirectly(msg)
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
        val isErrorOrWarning = level == "E" || level == "W"

        when (level) {
            "V" -> if (AppConfig.ENABLE_LOGGER) Log.v(tag, msg)
            "D" -> if (AppConfig.ENABLE_LOGGER) Log.d(tag, msg)
            "I" -> if (AppConfig.ENABLE_LOGGER) Log.i(tag, msg)
            "W" -> Log.w(tag, msg)
            "E" -> Log.e(tag, msg)
        }

        if (AppConfig.ENABLE_LOGGER || isErrorOrWarning) {
            val time = timeFormat.format(Date())
            logChannel.trySend("$time [$level] $tag: $msg\n")
        }
    }

    private fun logErrorTrace(err: Throwable) {
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
        writeDirectly(crash)
    }

    fun logNetwork(method: String, url: String, code: Int, timeMs: Long) {
        if (!AppConfig.ENABLE_NETWORK_LOG) return
        d("Network", "$method $url → $code (${timeMs}ms)")
    }

    @Synchronized
    private fun writeDirectly(text: String) {
        writeDirectlyInternal(text)
    }

    private fun writeDirectlyInternal(text: String) {
        try {
            if (writer == null && file != null) {
                writer = BufferedWriter(FileWriter(file!!, true))
            }
            writer?.write(text)
            writer?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Write failed", e)
            try {
                writer?.close()
            } catch (_: Exception) {}
            writer = null
            try {
                if (file != null) {
                    writer = BufferedWriter(FileWriter(file!!, true))
                    writer?.write(text)
                    writer?.flush()
                }
            } catch (e2: Exception) {
                Log.e(TAG, "Reopen writer failed", e2)
                writer = null
            }
        }
    }

    @Synchronized
    private fun checkLogRotation() {
        val f = file ?: return
        if (f.length() > AppConfig.MAX_LOG_FILE_SIZE) {
            val backup = File(f.parent, "${f.nameWithoutExtension}_${System.currentTimeMillis()}.txt")
            try {
                writer?.flush()
                writer?.close()
            } catch (_: Exception) {}
            writer = null
            if (f.renameTo(backup)) {
                file = File(f.parent, f.name)
            }
            try {
                writer = BufferedWriter(FileWriter(file ?: f, true))
                writeDirectlyInternal("=== Rotated from ${backup.name} ===\n")
                f.parentFile?.let { cleanOldLogs(it) }
            } catch (e: Exception) {
                Log.e(TAG, "Rotation failed", e)
                writer = null
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
