package com.tvlive.web

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.WebView

/**
 * 登录页专用 WebView：捕获页面聚焦输入框时内核建立的 InputConnection
 * （Chromium 返回的 WebViewInputConnection 继承自 BaseInputConnection），
 * 网页遥控的文字即可通过 commitText 走真实 IME 管线写进聚焦框。
 */
class RemoteWebView(context: Context) : WebView(context) {

    @Volatile var capturedConnection: InputConnection? = null
        private set

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val conn = super.onCreateInputConnection(outAttrs)
        if (conn != null) capturedConnection = conn
        return conn
    }
}
