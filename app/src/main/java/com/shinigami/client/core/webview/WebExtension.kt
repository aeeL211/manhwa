package com.shinigami.client.core.webview

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.shinigami.client.core.util.AppConfig
import com.shinigami.client.core.util.Logger
import okhttp3.Cache
import okhttp3.ConnectionPool
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class WebExtension(cacheDir: File) {

    private val isDead = AtomicBoolean(false)

    private val htmlCache = Cache(File(cacheDir, "html_http_cache"), 20L * 1024 * 1024)

    private val htmlHttpClient: OkHttpClient = sharedHttpClient.newBuilder()
        .cache(htmlCache)
        .build()

    private val cookieHashes = ConcurrentHashMap<String, String>()

    private val skippedHeaders = setOf(
        "host",
        "content-length",
        "accept-encoding",
        "user-agent",
        "connection",
        "if-none-match",
        "if-modified-since",
        "if-range",
    )
    private val allowedHosts = setOf("shinigami.asia", "shngm.io")

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

        checkAndEvictOnCookieChange(urlString)

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

    private fun checkAndEvictOnCookieChange(url: String) {
        val host = try { Uri.parse(url).host } catch (e: Exception) { null } ?: return
        if (allowedHosts.none { host == it || host.endsWith(".$it") }) return
        val cookieString = CookieManager.getInstance().getCookie(url).orEmpty()
        val newHash = hashString(cookieString)
        val oldHash = cookieHashes[host]
        if (oldHash != null && oldHash != newHash) {
            try {
                htmlCache.evictAll()
                if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "Cookie changed for $host, cache evicted")
            } catch (e: Exception) {
                Logger.e(TAG, "Failed to evict cache on cookie change", e)
            }
        }
        cookieHashes[host] = newHash
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
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
            htmlHttpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) return null

                syncCookies(url, response.headers, cookieManager)

                val contentType = response.header("Content-Type")
                if (contentType?.contains("html", ignoreCase = true) != true) return null

                val htmlContent = response.body?.string() ?: return null

                val patchedContent = htmlContent.replace("is_premium:false", "is_premium:true")

                val cacheControl = response.header("Cache-Control")

                val cachedResource = CachedResource(
                    data = patchedContent.toByteArray(StandardCharsets.UTF_8),
                    statusCode = response.code,
                    contentType = contentType,
                    cacheControl = cacheControl,
                )

                if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "Resource patched and served: $url")

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
            val host = try { Uri.parse(url).host } catch (e: Exception) { null }
            if (host != null) {
                val updatedCookieString = cookieManager.getCookie(url).orEmpty()
                cookieHashes[host] = hashString(updatedCookieString)
            }
        }
    }

    fun clearCache() {
        try {
            htmlCache.evictAll()
            if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "OkHttp cache evicted via clearCache()")
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to evict htmlCache in clearCache()", e)
        }
        cookieHashes.clear()
    }

    fun destroy() {
        if (isDead.getAndSet(true)) return
        if (AppConfig.ENABLE_NETWORK_LOG) Logger.i(TAG, "WebExtension instance destroyed")
    }

    private inner class CachedResource(
        val data: ByteArray,
        val statusCode: Int,
        contentType: String?,
        val cacheControl: String?,
    ) {
        private val mimeType = contentType?.substringBefore(';')?.trim() ?: "text/html"

        fun toWebResourceResponse(): WebResourceResponse {
            val responseHeaders = mutableMapOf(
                "Access-Control-Allow-Origin" to "*",
            )
            cacheControl?.let {
                responseHeaders["Cache-Control"] = it
            }

            return WebResourceResponse(
                mimeType,
                "UTF-8",
                statusCode,
                "OK",
                responseHeaders,
                ByteArrayInputStream(data),
            )
        }
    }

    companion object {
        private const val TAG = "WebExtension"

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
