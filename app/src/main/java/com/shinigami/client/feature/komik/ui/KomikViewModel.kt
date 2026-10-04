package com.shinigami.client.feature.komik.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shinigami.client.core.network.NetworkMonitor
import com.shinigami.client.core.util.AppConfig
import com.shinigami.client.core.util.Logger
import com.shinigami.client.feature.komik.data.ConfigRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

    private var isConnectedToNetwork: Boolean = false
    private var isPageFinishedLoading: Boolean = false
    private var isConfigFetching: Boolean = false
    private var hangTimeoutJob: Job? = null
    private var delayDismissJob: Job? = null

    init {
        initializeData()
    }

    private fun initializeData() {
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { isConnected ->
                val wasConnected = isConnectedToNetwork
                isConnectedToNetwork = isConnected
                if (isConnected) {
                    val currentUrl = _uiState.value.url
                    if (currentUrl == null) {
                        val cachedUrl = configRepository.getCachedUrlIfPresent()
                        if (cachedUrl != null) {
                            if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: instant load starting with cached URL: $cachedUrl")
                            _uiState.update { currentState ->
                                currentState.copy(url = cachedUrl)
                            }
                            fetchRemoteConfigInBackground(cachedUrl)
                        } else {
                            if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: no cached URL, fetching remote config first")
                            val remoteUrl = configRepository.getUrl()
                            _uiState.update { currentState ->
                                currentState.copy(url = remoteUrl)
                            }
                        }
                    } else if (!wasConnected && _uiState.value.isSplashVisible) {
                        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: network restored while splash visible, reloading")
                        _uiState.update { currentState ->
                            currentState.copy(shouldReload = true)
                        }
                    }

                    if (_uiState.value.isSplashVisible) {
                        if (isPageFinishedLoading) {
                            startDelayDismissTimer()
                        } else {
                            startHangTimeoutTimer()
                        }
                    }
                } else {
                    if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: network lost, cancelling timeouts")
                    cancelAllTimeouts()
                }
            }
        }
    }

    private fun fetchRemoteConfigInBackground(cachedUrl: String) {
        if (isConfigFetching) return
        isConfigFetching = true
        viewModelScope.launch {
            val remoteUrl = configRepository.fetchRemoteUrl()
            if (remoteUrl != null) {
                configRepository.saveCachedUrl(remoteUrl)
                if (remoteUrl != cachedUrl) {
                    if (!isPageFinishedLoading && _uiState.value.isSplashVisible) {
                        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: background config update from $cachedUrl to $remoteUrl")
                        isPageFinishedLoading = false
                        _uiState.update { currentState ->
                            currentState.copy(url = remoteUrl, shouldReload = true)
                        }
                        startHangTimeoutTimer()
                    } else {
                        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: remote URL updated ($remoteUrl), saved for next launch")
                    }
                }
            }
            isConfigFetching = false
        }
    }

    fun onReloadHandled() {
        _uiState.update { currentState ->
            currentState.copy(shouldReload = false)
        }
    }

    private fun cancelAllTimeouts() {
        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: cancelAllTimeouts called")
        hangTimeoutJob?.cancel()
        hangTimeoutJob = null
        delayDismissJob?.cancel()
        delayDismissJob = null
    }

    private fun startHangTimeoutTimer() {
        if (!isConnectedToNetwork) return
        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: starting hang timeout timer (20s)")
        hangTimeoutJob?.cancel()
        hangTimeoutJob = viewModelScope.launch {
            delay(20000L)
            if (isConnectedToNetwork && _uiState.value.isSplashVisible) {
                if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: hang timeout (20s) reached, dismissing splash")
                dismissSplashInternal()
            }
        }
    }

    private fun startDelayDismissTimer() {
        if (!isConnectedToNetwork) return
        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: starting delay dismiss timer (3s)")
        delayDismissJob?.cancel()
        delayDismissJob = viewModelScope.launch {
            delay(3000L)
            if (isConnectedToNetwork && _uiState.value.isSplashVisible) {
                if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: delay dismiss timer (3s) completed, dismissing splash")
                dismissSplashInternal()
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
        if (!isConnectedToNetwork) return
        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: onPageFinished called (isSplashVisible=${_uiState.value.isSplashVisible})")
        isPageFinishedLoading = true
        hangTimeoutJob?.cancel()
        hangTimeoutJob = null
        if (_uiState.value.isSplashVisible) {
            startDelayDismissTimer()
        } else {
            _uiState.update { currentState ->
                currentState.copy(isLoading = false)
            }
        }
    }

    private fun dismissSplashInternal() {
        if (AppConfig.DEBUG) Logger.d(TAG, "Splash transition: dismissSplashInternal called, hiding splash overlay")
        cancelAllTimeouts()
        _uiState.update { currentState ->
            currentState.copy(
                isLoading = false,
                isSplashVisible = false,
            )
        }
    }

    companion object {
        private const val TAG = "KomikViewModel"
    }
}
