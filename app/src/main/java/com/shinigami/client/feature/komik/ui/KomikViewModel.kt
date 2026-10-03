package com.shinigami.client.feature.komik.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shinigami.client.core.network.NetworkMonitor
import com.shinigami.client.feature.komik.data.ConfigRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

class KomikViewModel(application: Application) : AndroidViewModel(application) {

    private val networkMonitor = NetworkMonitor(application)
    private val configRepository = ConfigRepository(
        application.getSharedPreferences("Shinigami", Context.MODE_PRIVATE),
    )

    private val _uiState = MutableStateFlow(KomikUiState())
    val uiState: StateFlow<KomikUiState> = _uiState.asStateFlow()

    val defaultHeaders: Map<String, String> = mapOf("Accept-Language" to Locale.getDefault().language)

    init {
        initializeData()
        startSafetyTimeout()
    }

    private fun startSafetyTimeout() {
        viewModelScope.launch {
            kotlinx.coroutines.delay(5000L)
            onPageFinished()
        }
    }

    private fun initializeData() {
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { isConnected ->
                if (isConnected && _uiState.value.url == null) {
                    val remoteUrl = configRepository.getUrl()
                    _uiState.update { currentState ->
                        currentState.copy(url = remoteUrl)
                    }
                }
            }
        }
    }

    fun updateLoadingProgress(progress: Int) {
        _uiState.update { currentState ->
            currentState.copy(loadingProgress = progress)
        }
        if (progress == 100) {
            onPageFinished()
        }
    }

    fun onPageFinished() {
        _uiState.update { currentState ->
            currentState.copy(
                isLoading = false,
                isSplashVisible = false,
            )
        }
    }
}
