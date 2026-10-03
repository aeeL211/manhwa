package com.shinigami.client.feature.komik.ui

import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground),
    ) {
        if (popupWebViewState != null) {
            AndroidView(
                factory = { popupWebViewState },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            PullToRefreshBox(
                isRefreshing = uiState.isLoading && !uiState.isSplashVisible,
                onRefresh = {
                    mainWebViewState?.reload()
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                AndroidView(
                    factory = { ctx ->
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
                        }
                        onMainWebViewCreated(webView)
                        webView
                    },
                    update = { webView ->
                        if (uiState.url != null && webView.url == null) {
                            webView.loadUrl(uiState.url!!, viewModel.defaultHeaders)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Splash screen overlay
        if (uiState.isSplashVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DarkBackground),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Logo",
                        modifier = Modifier.size(160.dp),
                    )
                    Spacer(modifier = Modifier.height(32.dp))
                    LinearProgressIndicator(
                        progress = { uiState.loadingProgress / 100f },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .height(6.dp),
                        color = PrimaryAccent,
                        trackColor = Color(0xFF2A2A2A),
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
