package com.shinigami.client.feature.komik.data

import android.content.SharedPreferences
import com.shinigami.client.core.util.AppConfig
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.WebExtension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

class ConfigRepository(private val prefs: SharedPreferences) {

    companion object {
        private const val TAG = "ConfigRepository"
        private const val KEY_URL = "remote_url"
    }

    fun getCachedUrlIfPresent(): String? = prefs.getString(KEY_URL, null)

    fun saveCachedUrl(url: String) {
        prefs.edit().putString(KEY_URL, url).apply()
    }

    suspend fun fetchRemoteUrl(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(AppConfig.CONFIG_URL)
                .build()

            WebExtension.sharedHttpClient.newCall(request).execute().use { response ->
                response.body.string().trim().takeIf { it.startsWith("http") }
            }
        } catch (e: Exception) {
            Logger.w(TAG, "Network fetch failed: ${e.localizedMessage}")
            null
        }
    }

    suspend fun getUrl(): String = withContext(Dispatchers.IO) {
        val fetchedUrl = fetchRemoteUrl()
        if (fetchedUrl != null) {
            Logger.i(TAG, "Fetched remote url: $fetchedUrl")
            saveCachedUrl(fetchedUrl)
            fetchedUrl
        } else {
            Logger.w(TAG, "Empty or invalid response from config URL, falling back to cache")
            getCachedUrl()
        }
    }

    fun getCachedUrl(): String = prefs.getString(KEY_URL, AppConfig.BASE_URL) ?: AppConfig.BASE_URL
}
