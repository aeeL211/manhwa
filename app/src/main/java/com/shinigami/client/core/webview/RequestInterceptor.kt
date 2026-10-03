package com.shinigami.client.core.webview

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

object RequestInterceptor {

    private const val ANNOUNCEMENT_ID = "8dfa0456-9c8a-4f0e-a1de-5153a26e13e5"
    private val EMPTY_INPUT_STREAM = ByteArrayInputStream(ByteArray(0))

    private val ANNOUNCEMENT_ITEM = """{"announcement_id":"$ANNOUNCEMENT_ID","title":"Ads blocking by aeeL","content":"Ads blocking by **aeeL**","thumbnail_image_url":"https://assets.shngm.id/thumbnail/image/72cea7ce-532f-4fea-b83f-80a41ecc340c.jpg","publish_status":1,"created_date":"2025-11-16T05:42:09Z","created_at":"2025-11-16T05:42:09Z","updated_at":"2026-01-09T00:27:11Z"}"""
    private val ANNOUNCEMENT_DETAIL_JSON = """{"retcode":0,"message":"success","data":$ANNOUNCEMENT_ITEM}"""
    private val ANNOUNCEMENT_LIST_JSON = """{"retcode":0,"message":"success","meta":{"page":1,"page_size":10,"total_page":1,"total_record":1},"data":[$ANNOUNCEMENT_ITEM]}"""

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
                WebResourceResponse(mimeType, "utf-8", EMPTY_INPUT_STREAM)
            }
            host.endsWith("novu.my") -> WebResourceResponse("text/plain", "utf-8", EMPTY_INPUT_STREAM)
            else -> null
        }
    }

    private fun announcementResponse(request: WebResourceRequest, path: String): WebResourceResponse = when {
        !path.contains("/detail/") -> jsonResponse(request, ANNOUNCEMENT_LIST_JSON)
        path.endsWith(ANNOUNCEMENT_ID) -> jsonResponse(request, ANNOUNCEMENT_DETAIL_JSON)
        else -> WebResourceResponse("text/plain", "utf-8", EMPTY_INPUT_STREAM)
    }

    fun jsonResponse(request: WebResourceRequest, body: String): WebResourceResponse {
        val headers = mapOf(
            "Access-Control-Allow-Origin" to (request.requestHeaders["Origin"] ?: "*"),
            "Access-Control-Allow-Credentials" to "true",
            "Access-Control-Allow-Methods" to "GET, OPTIONS",
            "Access-Control-Allow-Headers" to (request.requestHeaders["Access-Control-Request-Headers"] ?: "*"),
        )

        return if (request.method == "OPTIONS") {
            WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers, EMPTY_INPUT_STREAM)
        } else {
            WebResourceResponse("application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream(body.toByteArray()))
        }
    }
}
