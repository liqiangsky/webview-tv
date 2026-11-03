package com.tvlive.web

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
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
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网页登录页（手机遥控配套）：全屏可交互 WebView + 悬浮光标。
 * 手机当鼠标：发相对位移和动作，本页把动作换算成光标位置的
 * Android MotionEvent（dispatchTouchEvent）打给 WebView —— 走系统输入管线，
 * 网页收到的是 isTrusted=true 的真实触摸，点击/滚动/长按拖拽全部按真实手势处理。
 * 文字输入走 JS：native value setter + input 事件，兼容 React/Vue 受控输入框。
 */
class LoginActivity : Activity() {

    private lateinit var webView: RemoteWebView
    private lateinit var cursor: CursorView
    private lateinit var statusText: TextView
    private var server: RemoteServer? = null

    private var dragging = false
    private var dragDownTime = 0L
    private val main = Handler(Looper.getMainLooper())

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = RemoteWebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
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
                    // 页面加载完成后再量内容宽并设 viewport；延迟补跑几次，
                    // 覆盖异步撑开宽度的情况（字体/图片/懒加载布局稳定后宽度才准）
                    // 缩放按钮也一起补跑：单页应用重渲染会把注入的控件抹掉
                    fun fit() {
                        view?.evaluateJavascript(FIT_WIDTH_JS, null)
                        view?.evaluateJavascript(ZOOM_BAR_JS, null)
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

        setContentView(root)
        hideSystemUi()
        webView.requestFocus()

        val url = intent.getStringExtra("url")
        webView.loadUrl(url ?: DEFAULT_URL)
        Toast.makeText(this, "登录完成后按返回键退出，播放页会自动刷新登录状态", Toast.LENGTH_LONG).show()

        server = RemoteServer(REMOTE_PORT, this::onCommand) { n -> updateStatus(n) }
        server?.start()
        updateStatus(0)
    }

    override fun onDestroy() {
        server?.stop()
        super.onDestroy()
    }

    /** 系统返回键优先收给网页历史，网页没有历史了才退出本页 */
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    // ---------------- 协议分发 ----------------

    private fun onCommand(cmd: JSONObject) {
        when (cmd.optString("t")) {
            "m" -> {
                val dx = cmd.optDouble("dx", 0.0).toFloat() * density * MOVE_SPEED
                val dy = cmd.optDouble("dy", 0.0).toFloat() * density * MOVE_SPEED
                if (dragging) moveDrag(dx, dy) else cursor.moveBy(dx, dy)
            }
            "c" -> tap()
            "dc" -> { tap(); main.postDelayed({ tap() }, 90) }
            "ds" -> startDrag()
            "de" -> endDrag()
            "s" -> scroll(cmd.optDouble("dx", 0.0).toFloat(), cmd.optDouble("dy", 0.0).toFloat())
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
        cursor.moveBy(dx, dy)
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
        webView.dispatchTouchEvent(ev)
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
        if (text.isEmpty()) return
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

    // ---------------- 杂项 ----------------

    private fun updateStatus(clients: Int) {
        val ip = localIp() ?: "未联网?"
        statusText.text = "遥控 $ip:$REMOTE_PORT · 已连接手机 $clients" +
            if (clients > 0) " ✔" else "（手机输入此 IP 连接）"
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
        const val REMOTE_PORT = 8721
        private const val DEFAULT_URL = "https://www.yangshipin.cn"
        private const val MOVE_SPEED = 1.6f
        private const val SCROLL_PX = 4f
        // 保留桌面 UA，注入 viewport 把布局视口宽度设成页面真实内容宽：
        // 内核据此整体等比缩放铺满 WebView 宽度 → 横向不滚动，弹窗也随缩放变矮可点关闭
        val FIT_WIDTH_JS = """
            (function () {
              try {
                var d = document.documentElement, b = document.body;
                var vw = d.clientWidth || window.innerWidth;
                var cw = Math.max(
                  d.scrollWidth, d.offsetWidth,
                  b ? b.scrollWidth : 0, b ? b.offsetWidth : 0);
                if (!cw || cw <= 0) return;
                var m = document.querySelector('meta[name="viewport"]');
                if (!m) {
                  m = document.createElement('meta');
                  m.setAttribute('name', 'viewport');
                  (d.firstElementChild || d).appendChild(m);
                }
                // 内容比视口宽（横向溢出）才按内容宽缩放铺满；否则按设备宽铺满、别缩太小
                m.setAttribute('content',
                  cw > vw ? ('width=' + Math.ceil(cw))
                          : 'width=device-width, initial-scale=1');
              } catch (e) {}
            })();
        """

        // 页面内悬浮缩放条（固定左下角）：－ / 1× / ＋
        // 缩放用 CSS zoom 打在 body 上；控件也在 body 里，但把自己的 zoom 设成 1/c
        // 反向抵消，且锚点用 left:0/bottom:0（0 缩放后仍是 0），于是无论页面放大多少倍，
        // 按钮始终贴在左下角、大小不变。间距靠自身 padding 留白，不用 offset 免得被缩放。
        val ZOOM_BAR_JS = """
            (function () {
              try {
                var d = document;
                if (!d.body) return;
                var c = window.__tvZoom || 1;
                var bar = d.getElementById('__tv_zoom_bar');
                if (!bar || !bar.parentNode) {
                  bar = d.createElement('div');
                  bar.id = '__tv_zoom_bar';
                  bar.style.cssText = 'position:fixed;left:0;bottom:0;z-index:2147483647;' +
                    'display:flex;gap:10px;padding:12px;border-radius:10px;' +
                    'background:rgba(0,0,0,0.55);touch-action:manipulation;';
                  var mk = function (label, fn) {
                    var b = d.createElement('div');
                    b.textContent = label;
                    b.style.cssText = 'color:#fff;font-size:22px;line-height:1;padding:10px 16px;' +
                      'border-radius:8px;background:rgba(255,255,255,0.18);cursor:pointer;' +
                      'user-select:none;';
                    b.addEventListener('click', function (e) {
                      e.preventDefault(); e.stopPropagation(); fn();
                    });
                    bar.appendChild(b);
                  };
                  window.__tvZoomApply = function (v) {
                    c = Math.min(4, Math.max(0.4, v));
                    window.__tvZoom = c;
                    d.body.style.zoom = c;
                    bar.style.zoom = 1 / c;
                  };
                  mk('－', function () { window.__tvZoomApply(c / 1.25); });
                  mk('1×', function () { window.__tvZoomApply(1); });
                  mk('＋', function () { window.__tvZoomApply(c * 1.25); });
                  d.body.appendChild(bar);
                }
                d.body.style.zoom = c;
                bar.style.zoom = 1 / c;
              } catch (e) {}
            })();
        """
    }
}
