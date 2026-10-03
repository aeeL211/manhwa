package com.shinigami.client

import android.app.Application
import android.content.Intent
import android.util.Log
import com.google.android.material.color.DynamicColors
import com.shinigami.client.core.util.Logger
import com.shinigami.client.ui.DebugActivity
import kotlin.system.exitProcess

class ShinigamiApp : Application() {

    override fun onCreate() {
        super.onCreate()

        initializeDependencies()
        setupGlobalCrashHandler()
    }

    private fun initializeDependencies() {
        Logger.init(this)
        DynamicColors.applyToActivitiesIfAvailable(this)
    }

    private fun setupGlobalCrashHandler() {
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                Logger.logCrash(throwable)
                Logger.e(TAG, "Fatal crash occurred in thread: ${thread.name}", throwable)
                Logger.shutdown() // Pastikan IO flush sblm process die

                val crashIntent = Intent(this, DebugActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra(DebugActivity.EXTRA_ERROR_MESSAGE, Log.getStackTraceString(throwable))
                }

                startActivity(crashIntent)
                Thread.sleep(300)
            } catch (e: Exception) {
                Log.e(TAG, "Crash handler failed to execute safely", e)
            } finally {
                exitProcess(10)
            }
        }
    }

    companion object {
        private const val TAG = "AppApplication"
    }
}
