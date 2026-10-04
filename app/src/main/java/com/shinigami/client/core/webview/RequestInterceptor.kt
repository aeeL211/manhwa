package com.shinigami.client.core.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

import java.nio.charset.StandardCharsets

object RequestInterceptor {

    private const val ANNOUNCEMENT_ID = "8dfa0456-9c8a-4f0e-a1de-5153a26e13e5"
    private val EMPTY_BYTES = ByteArray(0)

    private val ANNOUNCEMENT_CONTENT = """
Selamat datang! 👋

Aplikasi ini adalah Web2APK: website yang dibungkus menjadi aplikasi
Android, dibuat dan dikembangkan oleh AeeL.

✨ Fitur
• Pemblokiran iklan, baca lebih nyaman tanpa gangguan
• Tampilan dan navigasi yang dioptimalkan untuk layar ponsel
• Premium Extension, sistem ekstensi bawaan aplikasi

🧩 Premium Extension
Ekstensi ini bekerja di dalam aplikasi: respons web diubah secara
langsung sebelum ditampilkan, sehingga fitur premium terbuka di sisi
aplikasi. Terinspirasi oleh nullRE.

✅ Syarat ekstensi aktif
• Sudah login ke akun
• Koneksi internet aktif

❓ Kenapa begitu?
Ekstensi mengubah data akun yang dimuat setelah kamu login, jadi tanpa
login tidak ada yang bisa diubah. Perubahan hanya terjadi di aplikasi
kamu, tidak di server, jadi fitur yang diverifikasi langsung oleh
server tidak ikut terbuka.
""".trimIndent()

    private val ANNOUNCEMENT_ITEM = """{"announcement_id":"$ANNOUNCEMENT_ID","title":"Web2APK by AeeL","content":"${ANNOUNCEMENT_CONTENT.replace("\n", "\\n").replace("\"", "\\\"")}","thumbnail_image_url":"https://assets.shngm.id/thumbnail/image/72cea7ce-532f-4fea-b83f-80a41ecc340c.jpg","publish_status":1,"created_date":"2025-11-16T05:42:09Z","created_at":"2025-11-16T05:42:09Z","updated_at":"2026-01-09T00:27:11Z"}"""
    private val ANNOUNCEMENT_DETAIL_JSON = """{"retcode":0,"message":"success","data":$ANNOUNCEMENT_ITEM}"""
    private val ANNOUNCEMENT_LIST_JSON = """{"retcode":0,"message":"success","meta":{"page":1,"page_size":10,"total_page":1,"total_record":1},"data":[$ANNOUNCEMENT_ITEM]}"""

    private val ANNOUNCEMENT_DETAIL_BYTES = ANNOUNCEMENT_DETAIL_JSON.toByteArray(StandardCharsets.UTF_8)
    private val ANNOUNCEMENT_LIST_BYTES = ANNOUNCEMENT_LIST_JSON.toByteArray(StandardCharsets.UTF_8)

    fun interceptBlockedRequest(request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        val host = url.host.orEmpty()
        val path = url.path.orEmpty()

        return when {
            url.toString().contains("ads.shinigami") -> {
                val pageId = path.substringAfterLast("/")
                jsonResponse(request, """{"message":"success","meta":{"request_id":"","timestamp":0,"process_time":"0ms"},"data":[{"page_id":"$pageId","sections":[{"section_id":1,"section_num":1,"layout":{"rows":1,"columns":1},"type":"fixed","ads":[]}]}]}""")
            }
            host == "api.shngm.io" && path.startsWith("/v1/announcement") -> announcementResponse(request, path)
            host.contains("googletagmanager") -> {
                val mimeType = if (path.endsWith(".js")) "application/javascript" else "text/plain"
                WebResourceResponse(mimeType, "utf-8", ByteArrayInputStream(EMPTY_BYTES))
            }
            host.endsWith("novu.my") -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY_BYTES))
            else -> null
        }
    }

    private fun announcementResponse(request: WebResourceRequest, path: String): WebResourceResponse = when {
        !path.contains("/detail/") -> jsonResponse(request, ANNOUNCEMENT_LIST_BYTES)
        path.endsWith(ANNOUNCEMENT_ID) -> jsonResponse(request, ANNOUNCEMENT_DETAIL_BYTES)
        else -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(EMPTY_BYTES))
    }

    fun jsonResponse(request: WebResourceRequest, bodyBytes: ByteArray): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to (request.requestHeaders["Origin"] ?: "*"),
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to (request.requestHeaders["Access-Control-Request-Headers"] ?: "*"),
        )

        return if (request.method == "OPTIONS") {
            WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers, ByteArrayInputStream(EMPTY_BYTES))
        } else {
            WebResourceResponse("application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream(bodyBytes))
        }
    }

    fun jsonResponse(request: WebResourceRequest, body: String): WebResourceResponse {
        return jsonResponse(request, body.toByteArray(StandardCharsets.UTF_8))
    }
}
