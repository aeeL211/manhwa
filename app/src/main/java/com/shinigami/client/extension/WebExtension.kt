package com.shinigami.client.extension

import android.util.LruCache
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.shinigami.client.core.util.AppConfig
import com.shinigami.client.core.util.Logger
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebExtension {

    private val isDead = AtomicBoolean(false)

    private val memoryCache = object : LruCache<String, CachedResource>(calculateCacheSize()) {
        override fun sizeOf(key: String, value: CachedResource): Int = value.data.size
    }

    private val skippedHeaders = setOf("host", "content-length", "accept-encoding", "user-agent", "connection")
    private val allowedHosts = setOf("shinigami.asia", "shngm.io")

    private val defaultResponseHeaders = mapOf(
        "Access-Control-Allow-Origin" to "*",
        "Cache-Control" to "max-age=300"
    )

    @Volatile private var languageHeader = "en-US,en;q=0.9"
    @Volatile private var userAgentHeader: String? = null

    fun setLanguage(language: String) {
        languageHeader = language
        if (AppConfig.ENABLE_NETWORK_LOG) Logger.d(TAG, "WebExtension Language set to: $language")
    }

    fun setUserAgent(agent: String) {
        userAgentHeader = agent.replace("; wv", "")
        if (AppConfig.ENABLE_NETWORK_LOG) Logger.d(TAG, "WebExtension User-Agent updated")
    }

    fun shouldIntercept(url: String, request: WebResourceRequest): Boolean {
        if (isDead.get()) return false

        val host = request.url.host ?: return false
        if (allowedHosts.none { host == it || host.endsWith(".$it") }) return false

        val acceptHeader = request.requestHeaders["Accept"] ?: return false
        return acceptHeader.contains("text/html")
    }

    fun intercept(request: WebResourceRequest): WebResourceResponse? {
        if (isDead.get()) return null
        val urlString = request.url.toString()

        memoryCache.get(urlString)?.let {
            if (AppConfig.ENABLE_NETWORK_LOG) Logger.v(TAG, "Memory Cache Hit (HTML): $urlString")
            return it.toWebResourceResponse()
        }

        val startTime = if (AppConfig.ENABLE_NETWORK_LOG) System.currentTimeMillis() else 0L

        return try {
            fetchNetworkResource(urlString, request)?.also { resource ->
                if (AppConfig.ENABLE_NETWORK_LOG) {
                    val duration = System.currentTimeMillis() - startTime
                    Logger.logNetwork(request.method, urlString, resource.statusCode, duration)
                }
            }?.toWebResourceResponse()
        } catch (e: Exception) {
            Logger.e(TAG, "Interceptor failed to process: $urlString", e)
            null
        }
    }

    private fun fetchNetworkResource(url: String, request: WebResourceRequest): CachedResource? {
        val method = request.method.uppercase()
        if (method != "GET" && method != "HEAD") return null

        val requestBuilder = Request.Builder()
            .url(url)
            .method(method, null)
            .header("Accept-Language", languageHeader)

        userAgentHeader?.let { requestBuilder.header("User-Agent", it) }

        val cookieManager = CookieManager.getInstance()
        cookieManager.getCookie(url)?.let { requestBuilder.header("Cookie", it) }

        request.requestHeaders.forEach { (key, value) ->
            if (key.lowercase() !in skippedHeaders) {
                requestBuilder.header(key, value)
            }
        }

        return try {
            sharedHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null

                syncCookies(url, response.headers, cookieManager)

                val contentType = response.header("Content-Type")
                if (contentType?.contains("html", ignoreCase = true) != true) return null

                val htmlContent = response.body?.string() ?: return null

                // Menerapkan patch premium seperti kode asli
                val patchedContent = htmlContent.replace("is_premium:false", "is_premium:true")

                val cachedResource = CachedResource(
                    data = patchedContent.toByteArray(StandardCharsets.UTF_8),
                    statusCode = response.code,
                    contentType = contentType
                )

                memoryCache.put(url, cachedResource)

                if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "Resource patched and cached: $url")

                cachedResource
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Network request error during interception", e)
            null
        }
    }

    private fun syncCookies(url: String, headers: Headers, cookieManager: CookieManager) {
        val cookies = headers.values("Set-Cookie")
        if (cookies.isNotEmpty()) {
            cookies.forEach { cookieStr ->
                cookieManager.setCookie(url, cookieStr)
            }
        }
    }

    fun destroy() {
        if (isDead.getAndSet(true)) return
        memoryCache.evictAll()
        if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "WebExtension instance destroyed and cache cleared")
    }

    private inner class CachedResource(
        val data: ByteArray,
        val statusCode: Int,
        contentType: String?
    ) {
        private val mimeType = contentType?.substringBefore(';')?.trim() ?: "text/html"

        fun toWebResourceResponse(): WebResourceResponse {
            return WebResourceResponse(
                mimeType,
                "UTF-8",
                statusCode,
                "OK",
                defaultResponseHeaders,
                ByteArrayInputStream(data)
            )
        }
    }

    companion object {
        private const val TAG = "WebExtension"

        private fun calculateCacheSize(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            val defaultCacheSize = (maxMemory / 8).toInt()
            val maxLimit = 16 * 1024 * 1024
            val finalSize = defaultCacheSize.coerceAtMost(maxLimit)

            if (AppConfig.ENABLE_NETWORK_LOG) {
                val sizeMb = finalSize / (1024 * 1024)
                val maxMb = maxMemory / (1024 * 1024)
                Logger.i(TAG, "Allocated LruCache size: ${sizeMb}MB (Max Heap: ${maxMb}MB)")
            }
            return finalSize
        }

        val sharedHttpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .retryOnConnectionFailure(true)
                .followRedirects(true)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.SECONDS)
                .writeTimeout(5, TimeUnit.SECONDS)
                .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
                .cache(null)
                .build()
        }
    }
}