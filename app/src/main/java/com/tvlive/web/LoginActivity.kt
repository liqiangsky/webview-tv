package com.tvlive.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网页登录页（配置页「电视遥控」的配套）：全屏可交互 WebView + 悬浮光标。
 * 手机当鼠标：发相对位移和动作，本页把动作换算成光标位置的
 * Android MotionEvent（dispatchTouchEvent）打给 WebView —— 走系统输入管线，
 * 网页收到的是 isTrusted=true 的真实触摸，点击/滚动/长按拖拽全部按真实手势处理。
 * 文字输入走 JS：native value setter + input 事件，兼容 React/Vue 受控输入框。
 */
class LoginActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: RemoteWebView
    private lateinit var cursor: CursorView
    private lateinit var statusText: TextView
    private var remoteClients = 0

    /** 页面缩放百分比：电视上的缩放按钮和手机遥控页共用这一份档位 */
    private var zoom = ZOOM_STEP_BASE

    /** 铺满这一屏需要的布局视口宽（CSS px），在 1× 下量的 */
    private var fitBasisPx = 0

    private var dragging = false
    private var dragDownTime = 0L
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        root = FrameLayout(this)
        webView = RemoteWebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                // 不按内容宽重新适配：CSS zoom 把内容撑宽后，这一层会把放大比例
                // 一并改掉，等于把缩放吃掉；铺满交给注入的 viewport 宽就够
                loadWithOverviewMode = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = MainActivity.TV_USER_AGENT
                // 登录页要完整渲染，不套用播放页的资源拦截
            }
            setBackgroundColor(Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val url = request?.url?.toString() ?: return false
                    // 只在 http/https 站内跳转，intent:// market:// 等拉起外部 App 的一律拦截
                    return !(url.startsWith("http://") || url.startsWith("https://"))
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    // 新文档：宽度基准要重新量
                    fitBasisPx = 0
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    // 页面加载完成后量内容宽并设 viewport；延迟补跑几次，
                    // 覆盖异步撑开宽度的情况（字体/图片/懒加载布局稳定后宽度才准）
                    fun fit() {
                        refit(measureFirst = true)
                        applyZoom()   // 新文档没有旧文档的行内样式，档位要重新打上去
                    }
                    fit()
                    main.postDelayed({ fit() }, 400)
                    main.postDelayed({ fit() }, 1200)
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
        root.addView(webView, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        cursor = CursorView(this)
        root.addView(cursor, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(dp(10), dp(5), dp(10), dp(5))
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        // 缩放按钮：原生视图钉在左下角，页面怎么重排都跟它无关
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundPlate(dp(12))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(zoomCell("－") { stepZoom(-1) })
            addView(zoomCell("1×") { setZoom(ZOOM_STEP_BASE) })
            addView(zoomCell("＋") { stepZoom(1) })
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })

        setContentView(root)
        hideSystemUi()
        webView.requestFocus()

        val url = intent.getStringExtra("url")
        if (url != null) webView.loadUrl(url)
        Toast.makeText(this, "电视上的直播已暂停，登录完按返回键退出会自动重播当前频道", Toast.LENGTH_LONG).show()

        val app = application as TvApp
        // 遥控指令从配置服务的 WebSocket 进来，本页在前台时才有去处
        app.remoteSink = remoteSink
        app.remoteCount = { n -> remoteClients = n; updateStatus() }
        // 每次进登录页都从原始大小开始，手机那边的读数也要归位
        app.remoteZoom = zoom
        updateStatus()
    }

    /** 网页指令的挂出对象：销毁时要确认挂着的还是自己，别把后继登录页的注册抹掉 */
    private val remoteSink: RemoteSink = { onCommand(it) }

    override fun onDestroy() {
        val app = application as TvApp
        if (app.remoteSink === remoteSink) {
            app.remoteSink = null
            app.remoteCount = null
            // 页面走了，手机上的读数也该落回原始大小
            app.remoteZoom = ZOOM_STEP_BASE
            app.config.pushRemoteState()
        }
        super.onDestroy()
    }

    /** 系统返回键优先收给网页历史，网页没有历史了才退出本页 */
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    /**
     * 遥控器：方向键就是挪光标，确认键就在光标处点一下，跟手机触控板同一套模型
     * （光标、落点、拖动都走同一份代码），缩放按钮只是屏幕上左下角那三个普通按钮，
     * 把光标挪过去按确认就按到了。按住方向键会加速，不然横穿屏幕要按几十下。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val dirX = when (code) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> 0
        }
        val dirY = when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> -1
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            else -> 0
        }
        if (dirX != 0 || dirY != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val gain = dp(CURSOR_STEP_DP) *
                    (1f + event.repeatCount * CURSOR_ACCEL).coerceAtMost(CURSOR_ACCEL_MAX)
                if (dragging) moveDrag(dirX * gain, dirY * gain) else moveCursor(dirX * gain, dirY * gain)
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) tap()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------- 协议分发 ----------------

    private fun onCommand(cmd: JSONObject) {
        when (cmd.optString("t")) {
            "m" -> {
                val dx = cmd.optDouble("dx", 0.0).toFloat() * density * MOVE_SPEED
                val dy = cmd.optDouble("dy", 0.0).toFloat() * density * MOVE_SPEED
                if (dragging) moveDrag(dx, dy) else moveCursor(dx, dy)
            }
            "c" -> tap()
            "dc" -> { tap(); main.postDelayed({ tap() }, 90) }
            "ds" -> startDrag()
            "de" -> endDrag()
            "s" -> scroll(cmd.optDouble("dx", 0.0).toFloat(), cmd.optDouble("dy", 0.0).toFloat())
            "z" -> when (val d = cmd.optInt("d", 0)) {
                0 -> setZoom(ZOOM_STEP_BASE)
                else -> stepZoom(if (d > 0) 1 else -1)
            }
            "txt" -> injectText(cmd.optString("s"), cmd.optBoolean("r", true))
            "nav" -> {
                val u = cmd.optString("u")
                if (u.startsWith("http")) webView.loadUrl(u)
            }
            "k" -> when (cmd.optString("k")) {
                "enter" -> pressKey(KeyEvent.KEYCODE_ENTER)
                "del" -> pressKey(KeyEvent.KEYCODE_DEL)
                "tab" -> pressKey(KeyEvent.KEYCODE_TAB)
                "esc" -> pressKey(KeyEvent.KEYCODE_ESCAPE)
                "back" -> if (webView.canGoBack()) webView.goBack() else finish()
                "refresh" -> webView.reload()
            }
        }
    }

    // ---------------- 触摸注入 ----------------

    private fun tap() {
        val x = cursor.curX()
        val y = cursor.curY()
        val t = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, x, y, t, t)
        main.postDelayed({
            dispatch(MotionEvent.ACTION_UP, x, y, t, SystemClock.uptimeMillis())
        }, 50)
    }

    private fun startDrag() {
        dragging = true
        dragDownTime = SystemClock.uptimeMillis()
        dispatch(MotionEvent.ACTION_DOWN, cursor.curX(), cursor.curY(), dragDownTime, dragDownTime)
    }

    private fun moveDrag(dx: Float, dy: Float) {
        moveCursor(dx, dy)
        dispatch(MotionEvent.ACTION_MOVE, cursor.curX(), cursor.curY(),
            dragDownTime, SystemClock.uptimeMillis())
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        dispatch(MotionEvent.ACTION_UP, cursor.curX(), cursor.curY(),
            dragDownTime, SystemClock.uptimeMillis())
    }

    private fun dispatch(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        // 打给整棵树而不是直接给 WebView：左下角的缩放按钮也在这层，落上去就该归它
        root.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /**
     * 滚动 = 虚拟双指触控拖拽：Chromium 不认合成的 ACTION_SCROLL 滚轮事件，
     * 但双指 DOWN→MOVE→UP 的触控序列会被手势识别器转成页面滚动。
     * 连续滚动指令共用一个手势，250ms 没有新指令才抬手。
     */
    private var scrollDownTime = 0L
    private var scrollFingerX = 0f
    private var scrollFingerY = 0f
    private var scrollActive = false
    private var scrollHorizontal = false
    private var scrollAccumPx = 0f
    private var scrollAccumXpx = 0f
    private var scrollStartTop = -1f
    private var scrollStartLeft = -1f
    private val endScroll = Runnable {
        if (scrollActive) sendScrollPointer(MotionEvent.ACTION_UP)
        finishScrollGesture()
    }

    private fun scroll(dx: Float, dy: Float) {
        val dxPx = dx * density * SCROLL_PX
        val dyPx = dy * density * SCROLL_PX
        if (!scrollActive) {
            scrollActive = true
            scrollHorizontal = kotlin.math.abs(dxPx) > kotlin.math.abs(dyPx)
            scrollAccumPx = 0f
            scrollAccumXpx = 0f
            scrollDownTime = SystemClock.uptimeMillis()
            scrollFingerX = cursor.curX()
            scrollFingerY = cursor.curY()
            sendScrollPointer(MotionEvent.ACTION_DOWN)
            queryScrollPos { top, left -> scrollStartTop = top ?: -1f; scrollStartLeft = left ?: -1f }
        }
        scrollAccumPx += dyPx
        scrollAccumXpx += dxPx
        if (scrollHorizontal) {
            // 手指右移 = 视窗右移 = scrollLeft 增大
            scrollFingerX = (scrollFingerX + dxPx)
                .coerceIn(20f, (webView.width - 20).toFloat())
        } else {
            // 手机双指上滑（dy>0）= 页面向下滚 = 虚拟手指上移
            scrollFingerY = (scrollFingerY - dyPx)
                .coerceIn(20f, (webView.height - 20).toFloat())
        }
        sendScrollPointer(MotionEvent.ACTION_MOVE)
        main.removeCallbacks(endScroll)
        main.postDelayed(endScroll, 250)
    }

    /** 触控手势没滚动页面（PC 布局常只听滚轮）时，退回 JS 滚光标下方的滚动容器 */
    private fun finishScrollGesture() {
        val accumY = scrollAccumPx
        val accumX = scrollAccumXpx
        val start = scrollStartTop
        val startL = scrollStartLeft
        scrollAccumPx = 0f
        scrollAccumXpx = 0f
        scrollStartTop = -1f
        scrollStartLeft = -1f
        if ((accumY == 0f && accumX == 0f) || (start < 0f && startL < 0f)) return
        queryScrollPos { now, nowL ->
            val sameY = now != null && kotlin.math.abs(now - start) < 1f
            val sameX = nowL != null && kotlin.math.abs(nowL - startL) < 1f
            if (scrollHorizontal) {
                if (sameX && accumX != 0f) scrollViaJs(accumX, 0f)
            } else if (sameY && accumY != 0f) {
                scrollViaJs(0f, accumY)
            }
        }
    }

    private fun queryScrollPos(cb: (Float?, Float?) -> Unit) {
        webView.evaluateJavascript(
            "(function(){var el=document.scrollingElement||document.documentElement;return JSON.stringify([el.scrollTop,el.scrollLeft]);})()"
        ) { v ->
            val arr = try { org.json.JSONArray(v?.trim('"')?.replace("\\\"", "\"") ?: "[]") } catch (e: Exception) { null }
            cb(arr?.optDouble(0)?.toFloat(), arr?.optDouble(1)?.toFloat())
        }
    }

    private fun scrollViaJs(dxPx: Float, dyPx: Float) {
        val js = "(function(){var x=" + cursor.curX() + ",y=" + cursor.curY() + ",dx=" + dxPx + ",dy=" + dyPx + ";" +
            "var el=document.elementFromPoint(x,y);" +
            "while(el&&el!==document.body&&el!==document.documentElement){" +
            "var s=getComputedStyle(el);" +
            "var ok=dy?(/(auto|scroll)/.test(s.overflowY)&&el.scrollHeight>el.clientHeight+1)" +
            ":(/(auto|scroll)/.test(s.overflowX)&&el.scrollWidth>el.clientWidth+1);" +
            "if(ok)break;el=el.parentElement;}" +
            "if(!el||(dy?el.scrollHeight<=el.clientHeight+1:el.scrollWidth<=el.clientWidth+1))" +
            "{el=document.scrollingElement||document.documentElement;}" +
            "el.dispatchEvent(new WheelEvent('wheel',{deltaX:dx,deltaY:dy,clientX:x,clientY:y,bubbles:true}));" +
            "el.scrollTop+=dy;el.scrollLeft+=dx;})()"
        webView.evaluateJavascript(js, null)
    }

    private fun sendScrollPointer(action: Int) {
        val t = SystemClock.uptimeMillis()
        scrollActive = action != MotionEvent.ACTION_UP
        val props = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = scrollFingerX
                y = if (scrollHorizontal) scrollFingerY - 30f else scrollFingerY
            },
            MotionEvent.PointerCoords().apply {
                x = scrollFingerX
                y = if (scrollHorizontal) scrollFingerY + 30f else scrollFingerY + 60f
            }
        )
        val ev = MotionEvent.obtain(scrollDownTime, t, action, 2, props, coords,
            0, 0, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_TOUCHSCREEN)
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    private fun pressKey(code: Int) {
        val t = SystemClock.uptimeMillis()
        val down = KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        val up = KeyEvent(t, t + 30, KeyEvent.ACTION_UP, code, 0, 0,
            KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
        webView.dispatchKeyEvent(down)
        webView.dispatchKeyEvent(up)
    }


    // ---------------- 文字注入 ----------------

    /**
     * 文本优先走合成 IME：用页面聚焦输入框时内核建立的 BaseInputConnection，
     * 等同真实输入法输入，站点任何监听都拿得到；replace 时先清空框内全文再提交。
     * 桥还没建立（用户还没点过输入框）时退回 JS（native value setter + input 事件）。
     */
    private fun injectText(text: String, replace: Boolean) {
        // 空内容 + 替换 = 清空这个框，只有追加时才真没内容可写
        if (text.isEmpty() && !replace) return
        val conn = webView.capturedConnection
            ?: webView.onCreateInputConnection(EditorInfo())
        if (conn is BaseInputConnection) {
            if (replace) runCatching { conn.deleteSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE) }
            runCatching { if (conn.commitText(text, 1)) return }
        }
        injectTextViaJs(text, replace)
    }

    /**
     * 把文本写进当前聚焦输入框。必须走 native value setter 再派发 input 事件，
     * 直接 .value= 赋值会被 React/Vue 的受控组件当成“用户没改过”而吞掉。
     */
    private fun injectTextViaJs(text: String, replace: Boolean) {
        val op = if (replace) "t" else "el.value+t"
        val js = "(function(){var t=" + JSONObject.quote(text) + ";var el=document.activeElement;" +
            "if(!el||(el.tagName!=='INPUT'&&el.tagName!=='TEXTAREA'))return false;" +
            "var p=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "Object.getOwnPropertyDescriptor(p,'value').set.call(el," + op + ");" +
            "el.dispatchEvent(new Event('input',{bubbles:true}));" +
            "el.dispatchEvent(new Event('change',{bubbles:true}));return true;})()"
        webView.evaluateJavascript(js) { result ->
            if (result == "false") {
                Toast.makeText(this, "页面里没有聚焦的输入框：先用光标点一下输入框", Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    // ---------------- 缩放 ----------------

    /** 一档 = ±0.1 倍，步进只在电视这边算，手机遥控页读同一份数才不会岔开 */
    private fun stepZoom(dir: Int) {
        setZoom(zoom + dir * ZOOM_STEP)
    }

    private fun setZoom(percent: Int) {
        zoom = percent.coerceIn(ZOOM_MIN, ZOOM_MAX)
        applyZoom()
        // 回报给服务：手机遥控页上的读数跟着电视走
        val app = application as TvApp
        app.remoteZoom = zoom
        app.config.pushRemoteState()
    }

    /** 缩放就是往 html 上打 CSS zoom，视口、布局基准都不动 */
    private fun applyZoom() {
        webView.evaluateJavascript(
            APPLY_ZOOM_JS.replace("__Z__", zoom.toString()), null
        )
    }

    /** 光标挪了只挪光标：页面超出可见区的部分靠滚动指令挪过去 */
    private fun moveCursor(dx: Float, dy: Float) {
        cursor.moveBy(dx, dy)
    }

    /** 量 1× 下铺满这屏需要的布局视口宽，再交给 setViewportWidth */
    private fun refit(measureFirst: Boolean) {
        if (measureFirst || fitBasisPx <= 0) {
            webView.evaluateJavascript(MEASURE_WIDTH_JS) { value ->
                val arr = try {
                    org.json.JSONArray(value?.trim('"')?.replace("\\\"", "\"") ?: "[]")
                } catch (_: Exception) {
                    null
                }
                val vw = arr?.optInt(0) ?: return@evaluateJavascript
                val content = arr.optInt(1)
                if (vw > 0) {
                    fitBasisPx = if (content > vw) content else vw
                    setViewportWidth()
                }
            }
        } else {
            setViewportWidth()
        }
    }

    /** viewport 只负责「铺满这屏」，缩放不掺和进去 */
    private fun setViewportWidth() {
        if (fitBasisPx <= 0) return
        webView.evaluateJavascript(
            SET_VIEWPORT_JS.replace("__W__", fitBasisPx.toString()), null
        )
    }

    private fun roundPlate(cornerPx: Int) = GradientDrawable().apply {
        setColor(0x99000000.toInt())
        cornerRadius = cornerPx.toFloat()
    }

    private fun zoomCell(label: String, onTap: () -> Unit) = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(9), dp(14), dp(9))
        background = roundPlate(dp(9))
        isFocusable = false
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6) }
        setOnClickListener { onTap() }
    }

    // ---------------- 杂项 ----------------

    private fun updateStatus() {
        val ip = localIp() ?: "未联网?"
        statusText.text = "遥控 http://$ip:${TvApp.CONFIG_PORT} · 已连接手机 $remoteClients" +
            if (remoteClients > 0) " ✔" else "（手机打开配置网页「电视遥控」）"
    }

    private fun localIp(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }
                ?.hostAddress
        } catch (_: Exception) {
            null
        }
    }

    private fun hideSystemUi() {
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    companion object {
        private const val MOVE_SPEED = 1.6f
        private const val SCROLL_PX = 4f
        // 方向键挪光标：一步 12dp，按住每拍加 15%，最多 6 步之远
        private const val CURSOR_STEP_DP = 12
        private const val CURSOR_ACCEL = 0.15f
        private const val CURSOR_ACCEL_MAX = 6f
        private const val ZOOM_STEP_BASE = 100
        private const val ZOOM_STEP = 10
        // 下限凑满整档：否则从 30% 再减一档会被夹成 25%，读数反倒涨回去
        private const val ZOOM_MIN = 30
        private const val ZOOM_MAX = 400
        // 保留桌面 UA，注入 viewport 把布局视口宽度设成页面真实内容宽：
        // 内核据此整体等比缩放铺满 WebView 宽度 → 横向不滚动，弹窗也随缩放变矮可点关闭
        private val MEASURE_WIDTH_JS = """
            (function () {
              var d = document.documentElement, b = document.body;
              var vw = d.clientWidth || window.innerWidth;
              var cw = Math.max(
                d.scrollWidth, d.offsetWidth,
                b ? b.scrollWidth : 0, b ? b.offsetWidth : 0);
              return JSON.stringify([vw, cw]);
            })();
        """

        // 1× 时把样式清掉而不是写 1：有些页面自己按设计稿给根节点设了 zoom
        private val APPLY_ZOOM_JS = """
            (function () {
              var c = __Z__ / 100;
              document.documentElement.style.zoom = c === 1 ? '' : c;
            })();
        """

        private val SET_VIEWPORT_JS = """
            (function () {
              var d = document.documentElement;
              var m = document.querySelector('meta[name="viewport"]');
              if (!m) {
                m = document.createElement('meta');
                m.setAttribute('name', 'viewport');
                (d.firstElementChild || d).appendChild(m);
              }
              m.setAttribute('content', 'width=__W__');
            })();
        """
    }
}
