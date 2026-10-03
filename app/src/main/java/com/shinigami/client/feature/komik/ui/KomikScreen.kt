package com.shinigami.client.feature.komik.ui

import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.shinigami.client.core.ui.theme.PrimaryAccent
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

    val imeBottomDp = (activity.imeBottomPadding / context.resources.displayMetrics.density).dp

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(bottom = imeBottomDp),
    ) {
        if (popupWebViewState != null) {
            AndroidView(
                factory = { popupWebViewState },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
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
                        webView.reload()
                    }

                    onMainWebViewCreated(webView)
                    swipeRefresh
                },
                update = { swipeRefresh ->
                    swipeRefresh.isRefreshing = uiState.isLoading && !uiState.isSplashVisible
                    val webView = (0 until swipeRefresh.childCount)
                        .map { swipeRefresh.getChildAt(it) }
                        .filterIsInstance<WebView>()
                        .firstOrNull() ?: mainWebViewState

                    if (webView != null && uiState.url != null && webView.url == null) {
                        webView.loadUrl(uiState.url!!, viewModel.defaultHeaders)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Splash screen overlay
        AnimatedVisibility(
            visible = uiState.isSplashVisible,
            exit = fadeOut(animationSpec = tween(durationMillis = 500)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground),
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

                if (uiState.loadingProgress > 0) {
                    LinearProgressIndicator(
                        progress = { uiState.loadingProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer,
                        strokeCap = StrokeCap.Round,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 48.dp)
                            .height(3.dp),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceContainer,
                        strokeCap = StrokeCap.Round,
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
