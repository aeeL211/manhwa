package com.shinigami.client.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
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
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.shinigami.client.databinding.ActivityKomikBinding
import com.shinigami.client.extension.WebExtension
import com.shinigami.client.manager.DialogManager
import com.shinigami.client.util.AppConfig
import com.shinigami.client.util.Logger
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.lang.ref.WeakReference
import java.util.Locale

class KomikActivity : AppCompatActivity(), PopupHost {

    private val viewModel: KomikViewModel by viewModels()
    private val webExtension by lazy { WebExtension() }

    private lateinit var binding: ActivityKomikBinding
    private var popupWebView: WebView? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingFileChooserParams: WebChromeClient.FileChooserParams? = null

    private var lastBackPressedTime = 0L
    private var touchXCoordinate = 0
    private var touchYCoordinate = 0

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
            } else null

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
        binding = ActivityKomikBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupInsets()
        initializeWebView()
        setupUIInteractions()
        observeViewModelState()
        setupBackNavigation()

        savedInstanceState?.let { binding.webKomik.restoreState(it) }
    }

    private fun setupWindowConfiguration() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, imeInsets.bottom)
            insets
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
    private fun initializeWebView() {
        configureWebSettings(binding.webKomik)

        webExtension.setLanguage(Locale.getDefault().toLanguageTag())
        webExtension.setUserAgent(binding.webKomik.settings.userAgentString)

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webKomik, true)
        }

        binding.webKomik.webViewClient = DefaultWebViewClient(this)
        binding.webKomik.webChromeClient = DefaultWebChromeClient(this)

        binding.webKomik.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                touchXCoordinate = event.x.toInt()
                touchYCoordinate = event.y.toInt()
            }
            false
        }

        binding.webKomik.setOnLongClickListener {
            detectImageElement()
            true
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebSettings(webView: WebView) {
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

    private fun detectImageElement() {
        if (touchXCoordinate == 0 && touchYCoordinate == 0) return

        val hitTestResult = binding.webKomik.hitTestResult
        if (hitTestResult.type == WebView.HitTestResult.IMAGE_TYPE || hitTestResult.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE) {
            hitTestResult.extra?.let { url -> showContextMenu(url) }
            return
        }

        val javascriptCommand = JAVASCRIPT_IMAGE_DETECTOR.format(touchXCoordinate, touchYCoordinate)
        binding.webKomik.evaluateJavascript(javascriptCommand) { result ->
            result?.takeIf { it != "null" && it.length > 2 }
                ?.removeSurrounding("\"")
                ?.let { imageUrl -> showContextMenu(imageUrl) }
        }
    }

    private fun observeViewModelState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderUiState(state)
                }
            }
        }
    }

    private fun renderUiState(state: KomikUiState) {
        if (state.url != null && binding.webKomik.url == null) {
            binding.webKomik.loadUrl(state.url, viewModel.defaultHeaders)
        }

        binding.swipeRefreshLayout.isRefreshing = state.isLoading && !state.isSplashVisible

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            binding.splashProgress.setProgress(state.loadingProgress, true)
        } else {
            binding.splashProgress.progress = state.loadingProgress
        }

        if (!state.isSplashVisible && binding.splashLayout.isVisible && binding.splashLayout.alpha == 1f) {
            binding.splashLayout.animate()
                .alpha(0f)
                .setDuration(500)
                .setListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (isFinishing || isDestroyed) return
                        binding.splashLayout.visibility = View.GONE
                        performFirstRunCheck()
                    }
                })
        }
    }

    private fun setupUIInteractions() {
        binding.swipeRefreshLayout.setOnRefreshListener {
            if (popupWebView != null) {
                popupWebView?.reload()
            } else {
                binding.webKomik.reload()
            }
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    popupWebView != null -> dismissPopup()
                    binding.webKomik.canGoBack() -> binding.webKomik.goBack()
                    else -> handleApplicationExit()
                }
            }
        })
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

    private fun hasRequiredStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun requestStoragePermission() {
        val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(requiredPermissions)
    }

    private fun launchFileChooser(params: WebChromeClient.FileChooserParams) {
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

    private fun clearFileChooserState() {
        fileUploadCallback?.onReceiveValue(null)
        fileUploadCallback = null
        pendingFileChooserParams = null
    }

    private fun injectErudaConsole(webView: WebView) {
        if (AppConfig.ENABLE_ERUDA) {
            val erudaScript = """(function(){if(typeof eruda==='undefined'){var script=document.createElement('script');script.src="https://cdn.jsdelivr.net/npm/eruda";document.body.appendChild(script);script.onload=function(){eruda.init();}}})();"""
            webView.evaluateJavascript(erudaScript, null)
        }
    }

    override fun openPopupWebView(url: String) {
        val newWebView = WebView(this).apply {
            configureWebSettings(this)

            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    return interceptBlockedRequest(request) ?: super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    injectErudaConsole(view)
                }
            }

            webChromeClient = DefaultWebChromeClient(this@KomikActivity)
        }

        popupWebView = newWebView
        val layoutParams = ConstraintLayout.LayoutParams(
            ConstraintLayout.LayoutParams.MATCH_PARENT,
            ConstraintLayout.LayoutParams.MATCH_PARENT
        )
        binding.rootContainer.addView(newWebView, layoutParams)
        binding.swipeRefreshLayout.isVisible = false

        newWebView.loadUrl(url)
    }

    private fun dismissPopup() {
        popupWebView?.let { webView ->
            webView.stopLoading()
            binding.rootContainer.removeView(webView)
            webView.destroy()
            popupWebView = null
            binding.swipeRefreshLayout.isVisible = true
        }
    }

    private fun showContextMenu(url: String) {
        ContextMenuSheet.newInstance(url).show(supportFragmentManager, "ContextMenuSheet")
    }

    private fun extractDomainFromUrl(url: String?): String {
        return try {
            Uri.parse(url).host ?: "Situs Web"
        } catch (e: Exception) {
            "Situs Web"
        }
    }

    private fun performFirstRunCheck() {
        val sharedPrefs = getSharedPreferences("Shinigami", MODE_PRIVATE)
        if (!sharedPrefs.getBoolean(PREF_WELCOME_SHOWN, false)) {
            DialogManager.info(
                ctx = this,
                title = "Selamat Datang!",
                msg = "Login dengan akun Google untuk membuka fitur premium secara gratis."
            )
            sharedPrefs.edit().putBoolean(PREF_WELCOME_SHOWN, true).apply()
        }
    }

    override fun onPause() {
        super.onPause()
        binding.webKomik.onPause()
        popupWebView?.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webKomik.onResume()
        popupWebView?.onResume()
    }

    override fun onDestroy() {
        val mainWebView: WebView = binding.webKomik

        mainWebView.stopLoading()
        mainWebView.onPause()
        mainWebView.pauseTimers()

        popupWebView?.apply {
            stopLoading()
            onPause()
        }

        mainWebView.clearHistory()
        mainWebView.clearCache(false)
        mainWebView.clearFormData()
        mainWebView.loadUrl("about:blank")

        (mainWebView.parent as? ViewGroup)?.removeView(mainWebView)
        mainWebView.destroy()

        popupWebView?.let { popupView ->
            popupView.loadUrl("about:blank")
            binding.rootContainer.removeView(popupView)
            popupView.destroy()
        }
        popupWebView = null

        webExtension.destroy()
        super.onDestroy()
    }

    private class DefaultWebViewClient(activity: KomikActivity) : WebViewClient() {
        private val activityRef = WeakReference(activity)

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            interceptBlockedRequest(request)?.let { return it }

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

        private fun isInternalNavigation(url: String): Boolean {
            return url.contains("accounts.google.com") || url.contains("shinigami") || url.contains("shngm")
        }

        override fun onPageFinished(view: WebView, url: String) {
            val activity = activityRef.get() ?: return
            activity.viewModel.onPageFinished()
            activity.injectErudaConsole(view)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                Logger.w(TAG, "Main frame failed to load: ${error.description}")
            }
        }
    }

    private class DefaultWebChromeClient(activity: KomikActivity) : WebChromeClient() {
        private val activityRef = WeakReference(activity)

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            activityRef.get()?.viewModel?.updateLoadingProgress(newProgress)
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            DialogManager.info(activity, activity.extractDomainFromUrl(url), message ?: "", "OK") { result.confirm() }
            return true
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            DialogManager.confirm(
                ctx = activity,
                title = activity.extractDomainFromUrl(url),
                msg = message ?: "",
                yesText = "OK",
                noText = "Batal",
                onYes = { result.confirm() },
                onNo = { result.cancel() }
            )
            return true
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            val activity = activityRef.get()
            if (result == null || activity == null) return false
            DialogManager.prompt(
                ctx = activity,
                title = activity.extractDomainFromUrl(url),
                msg = message ?: "",
                defaultInput = defaultValue ?: "",
                onDone = { input -> result.confirm(input) },
                onCancel = { result.cancel() }
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
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        return interceptBlockedRequest(request) ?: super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView, url: String) {
                        activity.injectErudaConsole(view)
                    }
                }
                webChromeClient = this@DefaultWebChromeClient
            }

            activity.popupWebView = newWebView
            val layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
            activity.binding.rootContainer.addView(newWebView, layoutParams)
            activity.binding.swipeRefreshLayout.isVisible = false

            val transport = resultMsg?.obj as? WebView.WebViewTransport
            transport?.webView = newWebView
            resultMsg?.sendToTarget()

            return true
        }
    }

    companion object {
        private const val TAG = "KomikActivity"
        private const val PREF_WELCOME_SHOWN = "welcome_dialog_displayed"
        private const val ANNOUNCEMENT_ID = "8dfa0456-9c8a-4f0e-a1de-5153a26e13e5"

        private val EMPTY_INPUT_STREAM = ByteArrayInputStream(ByteArray(0))

        private const val ADS_JSON = """{"message":"success","data":[{"sections":[]}]}"""

        private val ANNOUNCEMENT_ITEM = """{"announcement_id":"$ANNOUNCEMENT_ID","title":"Ads blocking by aeeL","content":"Ads blocking by **aeeL**","thumbnail_image_url":"https://assets.shngm.id/thumbnail/image/72cea7ce-532f-4fea-b83f-80a41ecc340c.jpg","publish_status":1,"created_date":"2025-11-16T05:42:09Z","created_at":"2025-11-16T05:42:09Z","updated_at":"2026-01-09T00:27:11Z"}"""
        private val ANNOUNCEMENT_DETAIL_JSON = """{"retcode":0,"message":"success","data":$ANNOUNCEMENT_ITEM}"""
        private val ANNOUNCEMENT_LIST_JSON = """{"retcode":0,"message":"success","meta":{"page":1,"page_size":10,"total_page":1,"total_record":1},"data":[$ANNOUNCEMENT_ITEM]}"""

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

        private fun interceptBlockedRequest(request: WebResourceRequest): WebResourceResponse? {
            val url = request.url
            val host = url.host.orEmpty()
            val path = url.path.orEmpty()

            return when {
                url.toString().contains("ads.shinigami") -> jsonResponse(request, ADS_JSON)
                host == "api.shngm.io" && path.startsWith("/v1/announcement") -> announcementResponse(request, path)
                host.contains("googletagmanager") -> {
                    val mimeType = if (path.endsWith(".js")) "application/javascript" else "text/plain"
                    WebResourceResponse(mimeType, "utf-8", EMPTY_INPUT_STREAM)
                }
                host.endsWith("novu.my") -> WebResourceResponse("text/plain", "utf-8", EMPTY_INPUT_STREAM)
                else -> null
            }
        }

        private fun announcementResponse(request: WebResourceRequest, path: String): WebResourceResponse {
            return when {
                !path.contains("/detail/") -> jsonResponse(request, ANNOUNCEMENT_LIST_JSON)
                path.endsWith(ANNOUNCEMENT_ID) -> jsonResponse(request, ANNOUNCEMENT_DETAIL_JSON)
                else -> WebResourceResponse("text/plain", "utf-8", EMPTY_INPUT_STREAM)
            }
        }

        private fun jsonResponse(request: WebResourceRequest, body: String): WebResourceResponse {
            val headers = mapOf(
                "Access-Control-Allow-Origin" to (request.requestHeaders["Origin"] ?: "*"),
                "Access-Control-Allow-Credentials" to "true",
                "Access-Control-Allow-Methods" to "GET, OPTIONS",
                "Access-Control-Allow-Headers" to (request.requestHeaders["Access-Control-Request-Headers"] ?: "*")
            )

            return if (request.method == "OPTIONS") {
                WebResourceResponse("text/plain", "utf-8", 204, "No Content", headers, EMPTY_INPUT_STREAM)
            } else {
                WebResourceResponse("application/json", "utf-8", 200, "OK", headers, ByteArrayInputStream(body.toByteArray()))
            }
        }
    }
}
