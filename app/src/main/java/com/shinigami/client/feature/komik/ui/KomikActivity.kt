package com.shinigami.client.feature.komik.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.shinigami.client.core.ui.theme.ShinigamiTheme
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.ErudaConsole
import com.shinigami.client.core.webview.RequestInterceptor
import com.shinigami.client.core.webview.WebExtension
import com.shinigami.client.ui.PopupHost
import java.lang.ref.WeakReference

class KomikActivity :
    ComponentActivity(),
    PopupHost {

    val viewModel: KomikViewModel by viewModels()
    val webExtension by lazy { WebExtension() }

    var mainWebView: WebView? = null
    var popupWebView by mutableStateOf<WebView?>(null)

    var showContextMenuUrl by mutableStateOf<String?>(null)
    var activeDialog by mutableStateOf<DialogState?>(null)

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null

    private var lastBackPressedTime = 0L
    var touchXCoordinate = 0
    var touchYCoordinate = 0
        var imeBottomPadding by mutableStateOf(0)

    private val filePickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileUploadCallback ?: return@registerForActivityResult
        fileUploadCallback = null
        pendingFileChooserParams = null

        try {
            val resultUris = if (result.resultCode == RESULT_OK) {
                result.data?.data?.let { arrayOf(it) }
                    ?: result.data?.clipData?.let { clipData ->
                        Array(clipData.itemCount) { i -> clipData.getItemAt(i).uri }
                    }
            } else {
                null
            }

            callback.onReceiveValue(resultUris)
        } catch (e: Exception) {
            callback.onReceiveValue(null)
        }
    }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
        val allGranted = permissions.all { it.value }

        if (!allGranted) {
            Toast.makeText(this, "Izin akses media diperlukan untuk mengunggah file.", Toast.LENGTH_SHORT).show()
            fileUploadCallback?.onReceiveValue(null)
            fileUploadCallback = null
            pendingFileChooserParams = null
        } else {
            pendingFileChooserParams?.let { params ->
                launchFileChooser(params)
            } ?: run {
                Toast.makeText(this, "Izin berhasil diberikan, silakan ulangi tindakan Anda.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setupWindowConfiguration()
        super.onCreate(savedInstanceState)

        setupBackNavigation()

        setContent {
            ShinigamiTheme {
                KomikScreen(
                    viewModel = viewModel,
                    activity = this,
                    webExtension = webExtension,
                    mainWebViewState = mainWebView,
                    onMainWebViewCreated = { webView ->
                        mainWebView = webView
                        savedInstanceState?.let { webView.restoreState(it) }
                    },
                    popupWebViewState = popupWebView,
                    onDismissPopup = { dismissPopup() },
                    showContextMenuUrl = showContextMenuUrl,
                    onDismissContextMenu = { showContextMenuUrl = null },
                    onOpenPopupWebView = { url -> openPopupWebView(url) },
                    activeDialog = activeDialog,
                    onDismissDialog = { activeDialog = null },
                )
            }
        }

        performFirstRunCheck()
    }

    private fun setupWindowConfiguration() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { _, insets ->
            val imeInsets = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime())
            imeBottomPadding = imeInsets.bottom
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureWebSettings(webView: WebView) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = true
            allowContentAccess = true

            userAgentString = userAgentString.replace("; wv", "")
        }
    }

    fun detectImageElement() {
        val webView = mainWebView ?: return
        if (touchXCoordinate == 0 && touchYCoordinate == 0) return

        val hitTestResult = webView.hitTestResult
        if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE || hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            hitTestResult.extra?.let { url -> showContextMenuUrl = url }
            return
        }

        val javascriptCommand = JAVASCRIPT_IMAGE_DETECTOR.format(touchXCoordinate, touchYCoordinate)
        webView.evaluateJavascript(javascriptCommand) { result ->
            result?.takeIf { it != "null" && it.length > 2 }
                ?.removeSurrounding("\"")
                ?.let { imageUrl -> showContextMenuUrl = imageUrl }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        popupWebView != null -> dismissPopup()
                        mainWebView?.canGoBack() == true -> mainWebView?.goBack()
                        else -> handleApplicationExit()
                    }
                }
            },
        )
    }

    private fun handleApplicationExit() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressedTime < 2000L) {
            finish()
        } else {
            lastBackPressedTime = currentTime
            Toast.makeText(this, "Tekan kembali sekali lagi untuk keluar", Toast.LENGTH_SHORT).show()
        }
    }

    fun hasRequiredStoragePermission(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    fun requestStoragePermission() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(requiredPermissions)
    }

    fun launchFileChooser(params: WebChromeClient.FileChooserParams) {
        try {
            val fileIntent = params.createIntent().apply { addCategory(Intent.CATEGORY_OPENABLE) }
            if (fileIntent.resolveActivity(packageManager) != null) {
                filePickerLauncher.launch(fileIntent)
            } else {
                Toast.makeText(this, "Aplikasi manajer file tidak ditemukan di perangkat ini.", Toast.LENGTH_SHORT).show()
                clearFileChooserState()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka jendela pemilihan file.", Toast.LENGTH_SHORT).show()
            clearFileChooserState()
        }
    }

    fun clearFileChooserState() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        pendingFileChooserParams = null
    }

    override fun openPopupWebView(url: String) {
        val newWebView = WebView(this).apply {
            configureWebSettings(this)
            webViewClient = DefaultWebViewClient(this@KomikActivity)
            webChromeClient = DefaultWebChromeClient(this@KomikActivity)
        }

        popupWebView = newWebView
        newWebView.loadUrl(url)
    }

    fun dismissPopup() {
        popupWebView?.let { webView ->
            webView.stopLoading()
            webView.destroy()
            popupWebView = null
        }
    }

    fun extractDomainFromUrl(url: String?): String = try {
        Uri.parse(url).host ?: "Situs Web"
    } catch (e: Exception) {
        "Situs Web"
    }

    private fun performFirstRunCheck() {
        val sharedPrefs = getSharedPreferences("Shinigami", MODE_PRIVATE)
        if (!sharedPrefs.getBoolean(PREF_WELCOME_SHOWN, false)) {
            activeDialog = DialogState.Info(
                title = "Selamat Datang!",
                message = "Login dengan akun Google untuk membuka fitur premium secara gratis.",
            )
            sharedPrefs.edit().putBoolean(PREF_WELCOME_SHOWN, true).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        mainWebView?.onPause()
        popupWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        mainWebView?.onResume()
        popupWebView?.onResume()
    }

    override fun onDestroy() {
        mainWebView?.let { webView ->
            webView.stopLoading()
            webView.onPause()
            webView.pauseTimers()
            webView.clearHistory()
            webView.clearCache(false)
            webView.clearFormData()
            webView.loadUrl("about:blank")
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        mainWebView = null

        popupWebView?.let { popupView ->
            popupView.stopLoading()
            popupView.onPause()
            popupView.loadUrl("about:blank")
            popupView.destroy()
        }
        popupWebView = null

        webExtension.destroy()
        super.onDestroy()
    }

    class DefaultWebViewClient(activity: KomikActivity) : WebViewClient() {
        private val activityRef = WeakReference(activity)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            RequestInterceptor.interceptBlockedRequest(request)?.let { return it }

            val urlString = request.url.toString()
            val extension = activityRef.get()?.webExtension ?: return null

            return if (extension.shouldIntercept(urlString, request)) {
                extension.intercept(request)
            } else {
                null
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val urlString = request.url.toString()
            val activity = activityRef.get() ?: return false

            if (isInternalNavigation(urlString)) return false

            return try {
                activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urlString)))
                true
            } catch (e: Exception) {
                Logger.e(TAG, "Cannot launch external application for URL: $urlString", e)
                false
            }
        }

        private fun isInternalNavigation(url: String): Boolean = url.contains("accounts.google.com") || url.contains("shinigami") || url.contains("shngm")

        override fun onPageFinished(view: WebView, url: String) {
            val activity = activityRef.get() ?: return
            activity.viewModel.onPageFinished()
            ErudaConsole.inject(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                Logger.w(TAG, "Main frame failed to load: ${error.description}")
            }
        }
    }

    class DefaultWebChromeClient(activity: KomikActivity) : WebChromeClient() {
        private val activityRef = WeakReference(activity)

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            activityRef.get()?.viewModel?.updateLoadingProgress(newProgress)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            activity.activeDialog = DialogState.Info(
                title = activity.extractDomainFromUrl(url),
                message = message ?: "",
                buttonText = "OK",
                onDone = { result.confirm() },
            )
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            activity.activeDialog = DialogState.Confirm(
                title = activity.extractDomainFromUrl(url),
                message = message ?: "",
                yesText = "OK",
                noText = "Batal",
                onYes = { result.confirm() },
                onNo = { result.cancel() },
            )
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            activity.activeDialog = DialogState.Prompt(
                title = activity.extractDomainFromUrl(url),
                message = message ?: "",
                defaultInput = defaultValue ?: "",
                onDone = { input -> result.confirm(input) },
                onCancel = { result.cancel() },
            )
            return true
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, fileChooserParams: FileChooserParams): Boolean {
            val activity = activityRef.get() ?: return false

            activity.fileUploadCallback?.onReceiveValue(null)
            activity.fileUploadCallback = filePathCallback

            if (!activity.hasRequiredStoragePermission()) {
                activity.pendingFileChooserParams = fileChooserParams
                activity.requestStoragePermission()
                return true
            }

            activity.launchFileChooser(fileChooserParams)
            return true
        }

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            val activity = activityRef.get() ?: return false
            val newWebView = WebView(activity).apply {
                activity.configureWebSettings(this)
                webViewClient = DefaultWebViewClient(activity)
                webChromeClient = this@DefaultWebChromeClient
            }

            activity.popupWebView = newWebView

            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()

            return true
        }
    }

    companion object {
        private const val TAG = "KomikActivity"
        private const val PREF_WELCOME_SHOWN = "welcome_dialog_displayed"

        private val JAVASCRIPT_IMAGE_DETECTOR = """
            (function(x, y) {
                const elements = document.elementsFromPoint(x, y);
                if (!elements.length) return null;
                const extractUrl = (node) => {
                    if (!node) return null;
                    const tag = node.tagName.toUpperCase();
                    if (tag === 'IMG') return node.currentSrc || (node.srcset && node.srcset.split(' ')[0]) || node.src || node.dataset.src || node.dataset.lazySrc;
                    if (tag === 'CANVAS') { try { return node.toDataURL(); } catch (e) { return null; } }
                    if (tag === 'IMAGE' || tag === 'SVG') return (node.href && node.href.baseVal) || node.getAttribute('xlink:href');
                    const bgImage = getComputedStyle(node).backgroundImage;
                    if (bgImage && bgImage !== 'none' && bgImage.startsWith('url(')) {
                        const match = bgImage.match(/url\(['"]?([^'"]+)['"]?\)/);
                        if (match) return match[1];
                    }
                    return null;
                };
                for (let i = 0; i < elements.length; i++) {
                    const url = extractUrl(elements[i]);
                    if (url) return url;
                }
                let parent = elements[0];
                for (let d = 0; d < 5 && parent; d++) {
                    const url = extractUrl(parent);
                    if (url) return url;
                    parent = parent.parentElement;
                }
                return null;
            })(%d, %d);
        """.trimIndent().replace("\n", "").replace(Regex("\\s+"), " ")
    }
}
