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
 * 手机只发相对位移和动作，本页把它们换算成光标位置、鼠标源的 MotionEvent（SOURCE_MOUSE +
 * TOOL_TYPE_MOUSE）打给 WebView，网页收到的就是 PC 鼠标那一套：悬停、单击/双击、拖拽、长按。
 * 两处内核不给的（dblclick 的 click_count、会崩内核的 ACTION_SCROLL）由 JS 补齐。
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

                override fun onPageFinished(view: WebView?, url: String?) {
                    // 每个新文档都要重铺一遍：旧文档的行内样式不在新文档上，
                    // 缩放档位和 viewport 都得重新打上去
                    fun fit() {
                        setViewportWidth()
                        applyZoom()
                    }
                    fit()
                    // 有些页面加载后才用 JS 改自己的 viewport meta，补跑一次按屏幕宽盖回去
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

        // 钉在左下角的原生条：缩放三档 + 关闭，页面怎么重排都跟它无关
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundPlate(dp(12))
            setPadding(dp(10), dp(10), dp(10), dp(10))
            addView(zoomCell("－") { stepZoom(-1) })
            addView(zoomCell("1×") { setZoom(ZOOM_STEP_BASE) })
            addView(zoomCell("＋") { stepZoom(1) })
            // 电视遥控器上 Back 也能退，但触屏观众要找返回键很费劲
            addView(zoomCell("关闭") { finish() })
        }
        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, 0, dp(12)) })

        setContentView(root)
        hideSystemUi()
        webView.requestFocus()

        val url = intent.getStringExtra("url")
        if (url != null) webView.loadUrl(url)
        Toast.makeText(this, "电视上的直播已暂停，登录完按「关闭」或返回键退出，会自动重播当前频道", Toast.LENGTH_LONG).show()

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
            "dc" -> doubleTap()
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
                "close" -> finish()        // 手机遥控页的「关闭登录页」：不退网页，直接收掉这一页
            }
        }
    }

    // ---------------- 指针注入（PC 鼠标语义） ----------------

    /** 一次点击 = 左键按下 + 50ms 后抬起，downTime 相同 */
    private fun tap() {
        val x = cursor.curX()
        val y = cursor.curY()
        val t = SystemClock.uptimeMillis()
        mouseButton(MotionEvent.ACTION_BUTTON_PRESS, x, y, t, t)
        main.postDelayed({
            mouseButton(MotionEvent.ACTION_BUTTON_RELEASE, x, y, t, SystemClock.uptimeMillis())
        }, 50)
    }

    /**
     * 双击：两对按键落在同一个双击窗口里。
     * 内核只给真外设算 click_count，合成事件到不了 dblclick，所以第二对之后
     * 再往光标下的元素补发一次 dblclick（非可信，但监听器照常触发）。
     */
    private fun doubleTap() {
        tap()
        main.postDelayed({
            tap()
            main.postDelayed({ dispatchDblClick() }, 120)
        }, 110)
    }

    private fun dispatchDblClick() {
        webView.evaluateJavascript(
            "(function(){" + pointJs() +
                "var el=document.elementFromPoint(x,y);" +
                "if(el)el.dispatchEvent(new MouseEvent('dblclick',{bubbles:true,cancelable:true," +
                "clientX:x,clientY:y,button:0,buttons:1}));})()", null)
    }

    private fun startDrag() {
        dragging = true
        dragDownTime = SystemClock.uptimeMillis()
        mouseButton(MotionEvent.ACTION_BUTTON_PRESS, cursor.curX(), cursor.curY(),
            dragDownTime, dragDownTime)
    }

    private fun moveDrag(dx: Float, dy: Float) {
        cursor.moveBy(dx, dy)
        val t = SystemClock.uptimeMillis()
        // 按住左键挪位置 = ACTION_MOVE + BUTTON_PRIMARY，页面收到 buttons=1 的 mousemove
        dispatchMouse(mouseEvent(MotionEvent.ACTION_MOVE, cursor.curX(), cursor.curY(),
            dragDownTime, t, MotionEvent.BUTTON_PRIMARY))
    }

    private fun endDrag() {
        if (!dragging) return
        dragging = false
        mouseButton(MotionEvent.ACTION_BUTTON_RELEASE, cursor.curX(), cursor.curY(),
            dragDownTime, SystemClock.uptimeMillis())
    }

    private fun mouseEvent(
        action: Int, x: Float, y: Float, downTime: Long, eventTime: Long, buttonState: Int
    ): MotionEvent = MotionEvent.obtain(downTime, eventTime, action, 1,
        arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }),
        arrayOf(MotionEvent.PointerCoords().apply { this.x = x; this.y = y }),
        0, buttonState, 1f, 1f, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
        InputDevice.SOURCE_MOUSE)

    /**
     * 鼠标源的按键和悬停都交给 WebView.dispatchTouchEvent：
     * 内核按 source=SOURCE_MOUSE 自己分流，合成出可信的 mousedown/mouseup/click/mousemove。
     * 走 dispatchGenericMotionEvent 会被 View 层按焦点拦下（实测返回 false，页面一个事件都收不到）。
     */
    private fun dispatchMouse(ev: MotionEvent) {
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** 按下和抬起都带上 BUTTON_PRIMARY：内核靠它认出是哪一个键在按/在收 */
    private fun mouseButton(action: Int, x: Float, y: Float, downTime: Long, eventTime: Long) {
        dispatchMouse(mouseEvent(action, x, y, downTime, eventTime, MotionEvent.BUTTON_PRIMARY))
    }

    /** 没按键的移动 = 悬停：ACTION_HOVER_MOVE 驱动页面的 :hover 和 mousemove */
    private fun hover() {
        val t = SystemClock.uptimeMillis()
        dispatchMouse(mouseEvent(MotionEvent.ACTION_HOVER_MOVE, cursor.curX(), cursor.curY(), t, t, 0))
    }

    /**
     * 滚轮 = 滚光标下的容器：先给页面一个 wheel 事件，再改 scrollTop/scrollLeft。
     * 不走 ACTION_SCROLL：把带轴向值的滚轮事件打给 WebView，libwebviewchromium 直接 SIGTRAP
     * 把整个应用带走（实测 140 号 WebView 在两台机器上都崩）。
     */
    private fun scroll(dx: Float, dy: Float) {
        scrollViaJs(dx * density * SCROLL_PX, dy * density * SCROLL_PX)
    }

    /** 视图像素 → CSS 像素的换算：页面用 viewport 宽铺满整屏，比例就是 1/视图宽 */
    private fun toCss(px: Float, viewSize: Int): String =
        (if (viewSize > 0) px.toDouble() / viewSize else 0.0).toString()

    /** 光标坐标是视图像素，页面按 CSS 像素排布，进 JS 前先按可视区比例换算 */
    private fun pointJs(): String =
        "var x=" + toCss(cursor.curX(), webView.width) + "*innerWidth," +
            "y=" + toCss(cursor.curY(), webView.height) + "*innerHeight;"

    /** 补滚的位移同样要换算成 CSS 像素：dxPx/dyPx 是视图像素 */
    private fun scrollViaJs(dxPx: Float, dyPx: Float) {
        val js = "(function(){" + pointJs() +
            "var dx=" + toCss(dxPx, webView.width) + "*innerWidth,dy=" + toCss(dyPx, webView.width) + "*innerWidth;" +
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

    /** 缩放就是往 body 上打 CSS zoom，视口、布局基准都不动 */
    private fun applyZoom() {
        webView.evaluateJavascript(
            APPLY_ZOOM_JS.replace("__Z__", zoom.toString()), null
        )
    }

    /** 光标挪了只挪光标：没按住时同步发悬停，页面的 :hover 要跟着走 */
    private fun moveCursor(dx: Float, dy: Float) {
        cursor.moveBy(dx, dy)
        if (!dragging) hover()
    }

    /** 布局视口宽交给 viewport meta：不掺和缩放 */
    private fun setViewportWidth() {
        // 布局视口就按 WebView 自己的宽铺：页面排版和 PC 浏览器同宽，
        // CSS zoom 才是整体放大，而不是先把内容挤进一个猜出来的窄栏
        val cssPx = (webView.width / resources.displayMetrics.density).toInt()
        if (cssPx <= 0) return
        webView.evaluateJavascript(SET_VIEWPORT_JS.replace("__W__", cssPx.toString()), null)
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

        // 1× 时把样式清掉而不是写 1：有些页面自己按设计稿给根节点设了 zoom
        private val APPLY_ZOOM_JS = """
            (function () {
              var c = __Z__ / 100;
              var el = document.body || document.documentElement;
              el.style.zoom = c === 1 ? '' : c;
            })();
        """

        // 注入 viewport 把布局视口宽设成 WebView 的 CSS 宽（屏幕 dp 宽），1× 时网页正好铺满、不横向滚
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
