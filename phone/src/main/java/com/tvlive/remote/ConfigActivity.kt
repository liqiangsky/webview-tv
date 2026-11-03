package com.tvlive.remote

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 配置页容器：把电视上的 config.html 原样装进来，不做任何本地加工。
 *
 * 页面自己带 PIN 门禁和 localStorage 记忆，所以这里只管加载和返回。
 * 电视是局域网 http 明文服务，WebView 需要 manifest 开 usesCleartextTraffic 才不拦。
 */
class ConfigActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra("url")

        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true   // 配对码存 localStorage，不开就每次进来都要重输
                useWideViewPort = true
                loadWithOverviewMode = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            webViewClient = WebViewClient()
        }
        setContentView(web, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        if (url == null) {
            setContentView(TextView(this).apply {
                text = "没带上配置地址：回到首页填好电视 IP 再点「配置」"
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.BLACK)
                setPadding(40, 60, 40, 40)
                textSize = 16f
            })
        } else {
            web.loadUrl(url)
        }
    }

    /** 网页有历史就先回退，退无可退才关页 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && ::web.isInitialized && web.canGoBack()) {
            web.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (::web.isInitialized) {
            web.destroy()
        }
        super.onDestroy()
    }
}
