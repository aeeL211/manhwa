package com.shinigami.client.feature.komik.ui

data class KomikUiState(
    val url: String? = null,
    val isLoading: Boolean = true,
    val loadingProgress: Int = 0,
    val isSplashVisible: Boolean = true,
    val shouldReload: Boolean = false,
    val isConnected: Boolean = true,
)
