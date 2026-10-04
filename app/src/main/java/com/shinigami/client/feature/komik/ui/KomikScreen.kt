package com.shinigami.client.feature.komik.ui

import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.shinigami.client.R
import com.shinigami.client.core.ui.components.ContextMenuBottomSheet
import com.shinigami.client.core.ui.components.ShinigamiConfirmDialog
import com.shinigami.client.core.ui.components.ShinigamiInfoDialog
import com.shinigami.client.core.ui.components.ShinigamiPromptDialog
import com.shinigami.client.core.ui.theme.DarkBackground
import com.shinigami.client.core.ui.theme.SplashGradientBottom
import com.shinigami.client.core.ui.theme.SplashGradientTop
import com.shinigami.client.core.ui.theme.SplashProgress
import com.shinigami.client.core.ui.theme.SplashProgressTrack
import com.shinigami.client.core.util.AppConfig
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.WebExtension
import java.util.Locale

sealed interface DialogState {
    data class Info(val title: String, val message: String, val buttonText: String = "OK", val onDone: (() -> Unit)? = null) : DialogState
    data class Confirm(val title: String, val message: String, val yesText: String = "OK", val noText: String = "Batal", val onYes: () -> Unit, val onNo: (() -> Unit)? = null) : DialogState
    data class Prompt(val title: String, val message: String, val defaultInput: String = "", val onDone: (String) -> Unit, val onCancel: (() -> Unit)? = null) : DialogState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KomikScreen(
    viewModel: KomikViewModel,
    activity: KomikActivity,
    webExtension: WebExtension,
    mainWebViewState: WebView?,
    onMainWebViewCreated: (WebView) -> Unit,
    popupWebViewState: WebView?,
    onDismissPopup: () -> Unit,
    showContextMenuUrl: String?,
    onDismissContextMenu: () -> Unit,
    onOpenPopupWebView: (String) -> Unit,
    activeDialog: DialogState?,
    onDismissDialog: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var loadedInitialUrl by remember { mutableStateOf<String?>(null) }

    val imeBottomDp = (activity.imeBottomPadding / context.resources.displayMetrics.density).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(bottom = imeBottomDp),
    ) {
        AndroidView(
            factory = { ctx ->
                val swipeRefresh = SwipeRefreshLayout(ctx)
                val webView = WebView(ctx).apply {
                    activity.configureWebSettings(this)

                    webExtension.setLanguage(Locale.getDefault().toLanguageTag())
                    webExtension.setUserAgent(settings.userAgentString)

                    CookieManager.getInstance().let { cookieManager ->
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)
                    }

                    webViewClient = KomikActivity.DefaultWebViewClient(activity)
                    webChromeClient = KomikActivity.DefaultWebChromeClient(activity)

                    setOnTouchListener { _, event ->
                        if (event.action == MotionEvent.ACTION_DOWN) {
                            activity.touchXCoordinate = event.x.toInt()
                            activity.touchYCoordinate = event.y.toInt()
                        }
                        false
                    }

                    setOnLongClickListener {
                        activity.detectImageElement()
                        true
                    }

                    setOnScrollChangeListener { _, _, scrollY, _, _ ->
                        swipeRefresh.isEnabled = (scrollY == 0)
                    }
                }

                swipeRefresh.addView(
                    webView,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )

                swipeRefresh.setOnRefreshListener {
                    webExtension.clearCache()
                    webView.reload()
                }

                onMainWebViewCreated(webView)
                swipeRefresh
            },
            update = { swipeRefresh ->
                swipeRefresh.isRefreshing = uiState.isLoading && !uiState.isSplashVisible
                val webView = mainWebViewState ?: (swipeRefresh.getChildAt(0) as? WebView)

                if (webView != null) {
                    val targetUrl = uiState.url
                    if (targetUrl != null) {
                        if (loadedInitialUrl != targetUrl && webView.url == null) {
                            loadedInitialUrl = targetUrl
                            if (AppConfig.DEBUG) {
                                Logger.d(
                                    "KomikScreen",
                                    "[${System.currentTimeMillis()}] first_load_url: url=$targetUrl"
                                )
                            }
                            webView.loadUrl(targetUrl, viewModel.defaultHeaders)
                        } else if (uiState.shouldReload) {
                            viewModel.onReloadHandled()
                            if (webView.url != targetUrl) {
                                webView.loadUrl(targetUrl, viewModel.defaultHeaders)
                            } else {
                                webView.reload()
                            }
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (popupWebViewState != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground),
            ) {
                AndroidView(
                    factory = { popupWebViewState },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Splash screen overlay
        AnimatedVisibility(
            visible = uiState.isSplashVisible,
            exit = fadeOut(animationSpec = tween(durationMillis = 500)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(SplashGradientTop, SplashGradientBottom)
                        )
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth(0.65f)
                        .aspectRatio(1f),
                    contentScale = ContentScale.Fit,
                )

                val showDeterminateProgress = uiState.isConnected && uiState.loadingProgress > 0
                if (showDeterminateProgress) {
                    // Determinate progress filling from 0% to 100% while online and loading
                    LinearProgressIndicator(
                        progress = { uiState.loadingProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .height(3.dp),
                        color = SplashProgress,
                        trackColor = SplashProgressTrack,
                        strokeCap = StrokeCap.Butt,
                    )
                } else {
                    // Indeterminate looping animation while offline or before the first progress event
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .windowInsetsPadding(WindowInsets.navigationBars)
                            .height(3.dp),
                        color = SplashProgress,
                        trackColor = SplashProgressTrack,
                        strokeCap = StrokeCap.Butt,
                    )
                }
            }
        }

        // Dialogs
        when (activeDialog) {
            is DialogState.Info -> {
                ShinigamiInfoDialog(
                    title = activeDialog.title,
                    message = activeDialog.message,
                    buttonText = activeDialog.buttonText,
                    onDismiss = {
                        activeDialog.onDone?.invoke()
                        onDismissDialog()
                    },
                )
            }
            is DialogState.Confirm -> {
                ShinigamiConfirmDialog(
                    title = activeDialog.title,
                    message = activeDialog.message,
                    yesText = activeDialog.yesText,
                    noText = activeDialog.noText,
                    onYes = {
                        activeDialog.onYes()
                        onDismissDialog()
                    },
                    onNo = {
                        activeDialog.onNo?.invoke()
                        onDismissDialog()
                    },
                )
            }
            is DialogState.Prompt -> {
                ShinigamiPromptDialog(
                    title = activeDialog.title,
                    message = activeDialog.message,
                    defaultInput = activeDialog.defaultInput,
                    onDone = { input ->
                        activeDialog.onDone(input)
                        onDismissDialog()
                    },
                    onCancel = {
                        activeDialog.onCancel?.invoke()
                        onDismissDialog()
                    },
                )
            }
            null -> {}
        }

        // Context Menu Bottom Sheet
        if (showContextMenuUrl != null) {
            ContextMenuBottomSheet(
                url = showContextMenuUrl,
                onDismissRequest = onDismissContextMenu,
                onOpenInPopup = onOpenPopupWebView,
            )
        }
    }
}
