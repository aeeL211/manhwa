package com.shinigami.client.manager

import android.content.SharedPreferences
import com.shinigami.client.extension.WebExtension
import com.shinigami.client.util.AppConfig
import com.shinigami.client.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class ConfigManager(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "ConfigManager"
        private const val KEY_URL = "remote_url"
    }

    suspend fun getUrl(): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(AppConfig.CONFIG_URL)
                .build()

            val fetchedUrl = WebExtension.sharedHttpClient.newCall(request).execute().use { response ->
                response.body?.string()?.trim()?.takeIf { it.startsWith("http") }
            }

            if (fetchedUrl != null) {
                Logger.i(TAG, "Fetched remote url: $fetchedUrl")
                prefs.edit().putString(KEY_URL, fetchedUrl).apply()
                fetchedUrl
            } else {
                Logger.w(TAG, "Empty or invalid response from config URL, falling back to cache")
                getCachedUrl()
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Network fetch failed: ${e.localizedMessage}")
            getCachedUrl()
        }
    }

    private fun getCachedUrl(): String {
        return prefs.getString(KEY_URL, AppConfig.BASE_URL) ?: AppConfig.BASE_URL
    }
}
