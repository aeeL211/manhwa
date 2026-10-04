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

    var isConnectedToNetwork: Boolean = false
        private set

    private var isPageFinishedLoading: Boolean = false
    private var isConfigFetching: Boolean = false
    private var hangTimeoutJob: Job? = null
    private var delayDismissJob: Job? = null

    private var milestone10Logged = false
    private var milestone50Logged = false
    private var milestone100Logged = false
    private var pageFinishedLogged = false

    init {
        if (AppConfig.DEBUG) {
            Logger.d(TAG, "[${System.currentTimeMillis()}] app_start: KomikViewModel initialized")
        }
        initializeData()
    }

    private fun initializeData() {
        viewModelScope.launch {
            networkMonitor.networkStatus.collect { isConnected ->
                val wasConnected = isConnectedToNetwork
                isConnectedToNetwork = isConnected
                _uiState.update { currentState -> currentState.copy(isConnected = isConnected) }
                if (isConnected) {
                    val currentUrl = _uiState.value.url
                    if (currentUrl == null) {
                        val cachedUrl = configRepository.getCachedUrlIfPresent()
                        if (cachedUrl != null) {
                            if (AppConfig.DEBUG) {
                                Logger.d(TAG, "[${System.currentTimeMillis()}] config_ready: url=$cachedUrl (cached=true)")
                            }
                            _uiState.update { currentState ->
                                currentState.copy(url = cachedUrl)
                            }
                            fetchRemoteConfigInBackground(cachedUrl)
                        } else {
                            val remoteUrl = configRepository.getUrl()
                            if (AppConfig.DEBUG) {
                                Logger.d(TAG, "[${System.currentTimeMillis()}] config_ready: url=$remoteUrl (cached=false)")
                            }
                            _uiState.update { currentState ->
                                currentState.copy(url = remoteUrl)
                            }
                        }
                    } else if (!wasConnected && _uiState.value.isSplashVisible) {
                        if (AppConfig.DEBUG) {
                            Logger.d(TAG, "[${System.currentTimeMillis()}] splash transition: network restored, reloading")
                        }
                        _uiState.update { currentState ->
                            currentState.copy(shouldReload = true)
                        }
                    }

                    if (_uiState.value.isSplashVisible) {
                        if (isPageFinishedLoading) {
                            startDelayDismissTimer()
                        } else if (hangTimeoutJob == null || hangTimeoutJob?.isActive != true) {
                            startHangTimeoutTimer()
                        }
                    }
                } else {
                    if (AppConfig.DEBUG && _uiState.value.isSplashVisible) {
                        Logger.d(TAG, "[${System.currentTimeMillis()}] splash transition: network lost, cancelling timeouts")
                    }
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
                if (AppConfig.DEBUG) {
                    Logger.d(TAG, "[${System.currentTimeMillis()}] config_background_fetched: remoteUrl=$remoteUrl (cachedUrl=$cachedUrl)")
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
        hangTimeoutJob?.cancel()
        hangTimeoutJob = null
        delayDismissJob?.cancel()
        delayDismissJob = null
    }

    private fun startHangTimeoutTimer() {
        if (!isConnectedToNetwork) return
        if (hangTimeoutJob?.isActive == true) return
        if (AppConfig.DEBUG) {
            Logger.d(TAG, "[${System.currentTimeMillis()}] splash transition: starting hang timeout timer (20s)")
        }
        hangTimeoutJob?.cancel()
        hangTimeoutJob = viewModelScope.launch {
            delay(20000L)
            if (isConnectedToNetwork && _uiState.value.isSplashVisible) {
                dismissSplashInternal("20s_hang_timeout")
            }
        }
    }

    private fun startDelayDismissTimer() {
        if (!isConnectedToNetwork) return
        if (delayDismissJob?.isActive == true) return
        if (AppConfig.DEBUG) {
            Logger.d(TAG, "[${System.currentTimeMillis()}] splash transition: starting delay dismiss timer (3s)")
        }
        delayDismissJob = viewModelScope.launch {
            delay(3000L)
            if (isConnectedToNetwork && _uiState.value.isSplashVisible) {
                dismissSplashInternal("3s_timer_after_finish")
            }
        }
    }

    fun updateLoadingProgress(progress: Int) {
        val currentProgress = _uiState.value.loadingProgress
        val newProgress = maxOf(currentProgress, progress.coerceIn(0, 100))

        if (newProgress != currentProgress) {
            _uiState.update { currentState ->
                currentState.copy(loadingProgress = newProgress)
            }
        }

        if (AppConfig.DEBUG) {
            val now = System.currentTimeMillis()
            if (!milestone10Logged && newProgress >= 10) {
                milestone10Logged = true
                Logger.d(TAG, "[$now] progress_milestone: 10%")
            }
            if (!milestone50Logged && newProgress >= 50) {
                milestone50Logged = true
                Logger.d(TAG, "[$now] progress_milestone: 50%")
            }
            if (!milestone100Logged && newProgress >= 100) {
                milestone100Logged = true
                Logger.d(TAG, "[$now] progress_milestone: 100%")
            }
        }

        if (newProgress == 100) {
            onPageFinished()
        }
    }

    fun onPageFinished() {
        if (AppConfig.DEBUG && !pageFinishedLogged) {
            pageFinishedLogged = true
            Logger.d(TAG, "[${System.currentTimeMillis()}] on_page_finished: url=${_uiState.value.url}")
        }
        isPageFinishedLoading = true

        if (!isConnectedToNetwork) return

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

    private fun dismissSplashInternal(reason: String) {
        if (!_uiState.value.isSplashVisible) return
        if (AppConfig.DEBUG) {
            Logger.d(TAG, "[${System.currentTimeMillis()}] splash_hidden: reason=$reason")
        }
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
