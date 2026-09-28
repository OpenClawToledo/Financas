package com.pessoal.financas

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.webkit.WebView
import android.webkit.WebViewClient

/** Cria a WebView que carrega a interface local, usada pela tela principal e pelo lançamento rápido. */
object Tela {
    @SuppressLint("SetJavaScriptEnabled")
    fun criar(act: Activity, host: Hospedeiro, url: String, transparente: Boolean, aoCarregar: (WebView) -> Unit = {}): WebView =
        WebView(act).apply {
            setBackgroundColor(if (transparente) Color.TRANSPARENT else Color.parseColor("#0B1210"))
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            overScrollMode = WebView.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, u: String?) { aoCarregar(view) }
            }
            addJavascriptInterface(Ponte(host, act), "App")
            loadUrl(url)
        }
}
