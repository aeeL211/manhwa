package com.shinigami.client.core.util

object AppConfig {
    const val DEBUG = true
    const val ENABLE_ERUDA = false

    const val ENABLE_LOGGER = DEBUG
    const val ENABLE_CRASH_LOG = DEBUG
    const val ENABLE_NETWORK_LOG = DEBUG
    const val ENABLE_WEBVIEW_DEBUG = DEBUG

    const val MAX_LOG_FILE_SIZE = 5 * 1024 * 1024L
    const val MAX_LOG_FILES = 3

    const val VERSION_NAME = "1.7.0"

    const val BASE_URL = "https://shinigami.to"
    const val CONFIG_URL = "https://raw.githubusercontent.com/aeeL211/manhwa/refs/heads/main/url.txt"
}
