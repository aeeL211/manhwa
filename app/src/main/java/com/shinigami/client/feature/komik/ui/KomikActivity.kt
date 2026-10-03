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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.shinigami.client.core.ui.theme.ShinigamiTheme
import com.shinigami.client.core.util.Logger
import com.shinigami.client.core.webview.ErudaConsole
import com.shinigami.client.core.webview.RequestInterceptor
import com.shinigami.client.core.webview.WebExtension
import com.shinigami.client.ui.PopupHost
import org.json.JSONObject
import java.lang.ref.WeakReference

class KomikActivity :
    ComponentActivity(),
    PopupHost {

    val viewModel: KomikViewModel by viewModels()
    val webExtension by lazy { WebExtension(cacheDir) }

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
                val uiState by viewModel.uiState.collectAsState()

                LaunchedEffect(uiState.isSplashVisible) {
                    val window = this@KomikActivity.window
                    val insetsController = WindowCompat.getInsetsController(window, window.decorView)

                    if (uiState.isSplashVisible) {
                        window.statusBarColor = android.graphics.Color.parseColor("#18181B")
                        window.navigationBarColor = android.graphics.Color.parseColor("#09090B")
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    } else {
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        insetsController.isAppearanceLightStatusBars = false
                        insetsController.isAppearanceLightNavigationBars = false
                    }
                }

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
        if (touchXCoordinate <= 0 && touchYCoordinate <= 0) return

        val javascriptCommand = JAVASCRIPT_IMAGE_DETECTOR.format(touchXCoordinate, touchYCoordinate)
        webView.evaluateJavascript(javascriptCommand) { result ->
            val jsonObj = parseJavascriptResult(result)
            val jsUrl = jsonObj?.optString("url")?.takeIf { it.isNotBlank() && it != "null" }

            if (jsUrl != null) {
                showContextMenuUrl = jsUrl
                return@evaluateJavascript
            }

            val hitTestResult = webView.hitTestResult
            val hitUrl = if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE || hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
                hitTestResult.extra?.takeIf { it.isNotBlank() }
            } else {
                null
            }

            if (hitUrl != null) {
                showContextMenuUrl = hitUrl
                return@evaluateJavascript
            }

            val diagArray = jsonObj?.optJSONArray("diag")
            val topElementsStr = if (diagArray != null && diagArray.length() > 0) {
                (0 until diagArray.length()).mapNotNull { i ->
                    val item = diagArray.optJSONObject(i) ?: return@mapNotNull null
                    val tag = item.optString("tag", "unknown")
                    val cls = item.optString("cls", "").let { if (it.isNotEmpty()) ".$it" else "" }
                    val pe = item.optString("pe", "unknown")
                    "$tag$cls(pe:$pe)"
                }.joinToString(", ")
            } else {
                "none"
            }

            val pageUrl = webView.url ?: "unknown"
            val logMessage = "No image detected on $pageUrl at ($touchXCoordinate,$touchYCoordinate) | Top elements: [$topElementsStr]".take(500)
            Logger.d(TAG, logMessage)
        }
    }

    private fun parseJavascriptResult(result: String?): JSONObject? {
        if (result.isNullOrBlank() || result == "null") return null
        var unescaped = result
        if (unescaped.startsWith("\"") && unescaped.endsWith("\"")) {
            try {
                unescaped = org.json.JSONTokener(unescaped).nextValue() as? String ?: unescaped
            } catch (_: Exception) {}
        }
        return try {
            JSONObject(unescaped)
        } catch (_: Exception) {
            null
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
            if (com.shinigami.client.core.util.AppConfig.ENABLE_ERUDA) {
                ErudaConsole.inject(view)
            }
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
            (function(px, py) {
                var dpr = window.devicePixelRatio || 1;
                var x = px / dpr;
                var y = py / dpr;
                var isImgUrl = function(u) {
                    if (!u || typeof u !== 'string') return false;
                    var s = u.trim();
                    if (!s || s.indexOf('data:image/svg+xml') === 0 || s === 'about:blank') return false;
                    if (s.indexOf('data:image/') === 0 || s.indexOf('blob:') === 0 || s.indexOf('http://') === 0 || s.indexOf('https://') === 0 || s.indexOf('//') === 0 || s.indexOf('/') === 0) return true;
                    var clean = s.split('?')[0].split('#')[0].toLowerCase();
                    var exts = ['.webp', '.png', '.jpg', '.jpeg', '.gif', '.svg', '.avif'];
                    for (var i = 0; i < exts.length; i++) {
                        if (clean.endsWith(exts[i])) return true;
                    }
                    return false;
                };
                var toAbs = function(u) {
                    if (!u) return null;
                    try {
                        return new URL(u, document.baseURI || window.location.href).href;
                    } catch(e) {
                        return u;
                    }
                };
                var parseSrcset = function(ss) {
                    if (!ss || typeof ss !== 'string') return null;
                    var parts = ss.split(',');
                    for (var i = 0; i < parts.length; i++) {
                        var item = parts[i].trim().split(/\s+/)[0];
                        if (isImgUrl(item)) return toAbs(item);
                    }
                    return null;
                };
                var extractFromNode = function(node) {
                    if (!node || node.nodeType !== 1) return null;
                    var tag = node.tagName ? node.tagName.toUpperCase() : '';
                    if (tag === 'IMG') {
                        if (isImgUrl(node.currentSrc)) return toAbs(node.currentSrc);
                        var ss = parseSrcset(node.srcset || node.getAttribute('srcset'));
                        if (ss) return ss;
                        if (isImgUrl(node.src)) return toAbs(node.src);
                    }
                    if (tag === 'SOURCE') {
                        var ssS = parseSrcset(node.srcset || node.getAttribute('srcset'));
                        if (ssS) return ssS;
                        var sS = node.src || node.getAttribute('src');
                        if (isImgUrl(sS)) return toAbs(sS);
                    }
                    if (tag === 'PICTURE') {
                        var sources = node.querySelectorAll('source, img');
                        for (var i = 0; i < sources.length; i++) {
                            var u = extractFromNode(sources[i]);
                            if (u) return u;
                        }
                    }
                    if (tag === 'CANVAS') {
                        try {
                            var du = node.toDataURL();
                            if (isImgUrl(du)) return du;
                        } catch(e) {}
                    }
                    if (tag === 'SVG' || tag === 'IMAGE' || tag === 'USE') {
                        var href = (node.href && node.href.baseVal) || node.getAttribute('href') || node.getAttribute('xlink:href') || node.getAttribute('src');
                        if (isImgUrl(href)) return toAbs(href);
                    }
                    var dataAttrs = ['data-src', 'data-lazy-src', 'data-original', 'data-srcset', 'data-url', 'data-fallback', 'data-full-src', 'data-lazy', 'data-bg'];
                    for (var j = 0; j < dataAttrs.length; j++) {
                        var val = node.getAttribute(dataAttrs[j]);
                        if (val) {
                            var parsedSs = parseSrcset(val);
                            if (parsedSs) return parsedSs;
                            if (isImgUrl(val)) return toAbs(val);
                        }
                    }
                    if (node.attributes) {
                        for (var k = 0; k < node.attributes.length; k++) {
                            var attr = node.attributes[k];
                            if (attr && attr.name && attr.name.indexOf('data-') === 0 && attr.value) {
                                if (isImgUrl(attr.value)) return toAbs(attr.value);
                            }
                        }
                    }
                    var checkBg = function(styleObj) {
                        if (!styleObj) return null;
                        var bg = styleObj.backgroundImage;
                        if (bg && bg !== 'none') {
                            var m = bg.match(/url\(['"]?([^'"]+)['"]?\)/i);
                            if (m && m[1] && isImgUrl(m[1])) return toAbs(m[1]);
                        }
                        return null;
                    };
                    try {
                        var cs = getComputedStyle(node);
                        var bgU = checkBg(cs);
                        if (bgU) return bgU;
                        var beforeU = checkBg(getComputedStyle(node, '::before'));
                        if (beforeU) return beforeU;
                        var afterU = checkBg(getComputedStyle(node, '::after'));
                        if (afterU) return afterU;
                    } catch(e) {}
                    return null;
                };
                var inspectElementAndTree = function(el) {
                    if (!el) return null;
                    var direct = extractFromNode(el);
                    if (direct) return direct;
                    var childImgs = el.querySelectorAll('img, picture, source, svg, image, canvas');
                    for (var i = 0; i < childImgs.length; i++) {
                        var cu = extractFromNode(childImgs[i]);
                        if (cu) return cu;
                    }
                    var curr = el.parentElement;
                    for (var p = 0; p < 5 && curr; p++) {
                        var pu = extractFromNode(curr);
                        if (pu) return pu;
                        curr = curr.parentElement;
                    }
                    return null;
                };
                var rawElements = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [];
                if (!rawElements.length && document.elementFromPoint) {
                    var topEl = document.elementFromPoint(x, y);
                    if (topEl) rawElements = [topEl];
                }
                var elements = [];
                for (var i = 0; i < rawElements.length; i++) {
                    var el = rawElements[i];
                    elements.push(el);
                    if (el.shadowRoot && el.shadowRoot.elementsFromPoint) {
                        try {
                            var sEls = el.shadowRoot.elementsFromPoint(x, y);
                            for (var s = 0; s < sEls.length; s++) elements.push(sEls[s]);
                        } catch(e) {}
                    }
                    if ((el.tagName === 'IFRAME' || el.tagName === 'FRAME') && el.contentDocument) {
                        try {
                            var rect = el.getBoundingClientRect();
                            var ix = x - rect.left;
                            var iy = y - rect.top;
                            if (el.contentDocument.elementsFromPoint) {
                                var ifEls = el.contentDocument.elementsFromPoint(ix, iy);
                                for (var f = 0; f < ifEls.length; f++) elements.push(ifEls[f]);
                            }
                        } catch(e) {}
                    }
                }
                for (var j = 0; j < elements.length; j++) {
                    var foundUrl = inspectElementAndTree(elements[j]);
                    if (foundUrl) {
                        return JSON.stringify({ url: foundUrl });
                    }
                }
                var diag = [];
                for (var k = 0; k < Math.min(3, rawElements.length); k++) {
                    var item = rawElements[k];
                    var tag = item.tagName ? item.tagName.toLowerCase() : 'unknown';
                    var cls = '';
                    if (typeof item.className === 'string') {
                        cls = item.className.trim().replace(/\s+/g, '.');
                    } else if (item.getAttribute) {
                        cls = (item.getAttribute('class') || '').trim().replace(/\s+/g, '.');
                    }
                    var pe = 'unknown';
                    try {
                        pe = getComputedStyle(item).pointerEvents || 'unknown';
                    } catch(e) {}
                    diag.push({ tag: tag, cls: cls, pe: pe });
                }
                return JSON.stringify({ url: null, diag: diag });
            })(%d, %d);
        """.trimIndent().replace("\n", "").replace(Regex("\\s+"), " ")
    }
}
