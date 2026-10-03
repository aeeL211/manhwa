package com.shinigami.client.core.webview

import android.webkit.WebView
import com.shinigami.client.core.util.AppConfig

object ErudaConsole {

    fun inject(webView: WebView) {
        if (AppConfig.ENABLE_ERUDA) {
            val erudaScript = """(function(){if(typeof eruda==='undefined'){var script=document.createElement('script');script.src="https://cdn.jsdelivr.net/npm/eruda";document.body.appendChild(script);script.onload=function(){eruda.init();}}})();"""
            webView.evaluateJavascript(erudaScript, null)
        }
    }
}
