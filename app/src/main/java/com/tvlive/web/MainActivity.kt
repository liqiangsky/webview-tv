package com.tvlive.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Android TV 直播网页播放器（沉浸式频道面板）：
 * 底层全屏 WebView 常驻播放，按 OK / 点屏呼出遮罩。遮罩一屏三列：
 * 左 频道信息（在播/预览、EPG、进度、时间，纯展示）；
 * 中 频道列表（上下选台、左右换组、OK 切台并收起，键位与最初设计一致）；
 * 右 手机配置二维码（地址/配对码，电视端只展示回显）。
 * 网站登录不在电视上列条目——手机配置页里点条目的「打开」，电视直接开该站登录页。
 * 频道与登录网址全部由配置网页写入，电视端只读展示；BACK 收起遮罩。
 */
class MainActivity : Activity(), ConfigServer.ConfigHost {

    private lateinit var webView: WebView

    // 遮罩：左 频道信息 / 中 频道列表 / 右 手机配置二维码
    private lateinit var panelRoot: View
    private lateinit var panelBody: View
    private lateinit var panelList: RecyclerView
    private lateinit var panelEmpty: View
    private lateinit var groupName: TextView
    private lateinit var groupCount: TextView
    private lateinit var groupPrev: ImageView
    private lateinit var groupNext: ImageView
    private lateinit var qrImage: ImageView
    private lateinit var qrUrl: TextView
    private lateinit var qrPin: TextView
    private lateinit var qrRegen: View

    // 左列频道信息（频道名 + 在播/预览状态 + 节目单，纯展示）
    private lateinit var infoPanel: View
    private lateinit var infoTitle: TextView
    private lateinit var infoState: TextView
    private lateinit var infoQuality: TextView
    private lateinit var infoSource: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoDate: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoRemain: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: View
    private lateinit var guideNextLabel: TextView
    private lateinit var guideRows: List<View>   // 进度线、分隔、 「接下来」标题：没节目单时一起收掉
    private var infoTarget: Channel? = null    // 信息栏当前展示的频道，节目单跟着它走
    private var infoFollowFocus = true        // 打开面板的首次聚焦不覆盖“在播频道”
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dateFmt = java.text.SimpleDateFormat("M月d日 EEE", java.util.Locale.CHINA)

    // 直播页 / 遮罩是否处于打开状态
    private var webOpen = false
    private var playTimeoutArmed = false     // 起播超时是否已排期（一次点台只计一次时）
    private var panelOpen = false
    private var openKeyUp = false        // 呼出面板那一下按键的 UP 需要吃掉

    // 频道列表的重建条件：分组、数据版本、在播行号，三者都没变就不必重搭
    private var listVersion = 0
    private var listBuiltFor: Triple<Int, Int, Int>? = null

    // 登录页返回后要重播当前频道（新 cookie 才用得上）；等窗口拿到焦点再动手
    private var needReloadAfterLogin = false

    // 静态脚本磁盘缓存：meta 访问时间异步刷新用的单线程池
    private val cacheExecutor = Executors.newSingleThreadExecutor()

    // 当前页面地址：后台下载脚本时充当 Referer，尽量复现页面自己的那次请求
    @Volatile private var docUrl: String? = null

    // 频道数据（按分组）与浏览状态（与播放状态解耦）
    private var groups: List<Group> = emptyList()
    private var curGroupIdx = 0
    private val listIndexByGroup = HashMap<Int, Int>()
    private var playingLine = -1              // 正在播放频道所在文件行号
    private var lastGroupNavAt = 0L           // 左右切组防抖

    // ---------- 站点脚本（用户自己写的页内 JS）----------
    // 当前页面匹配到的脚本正文；null 表示这个站没脚本，脚本通道全部关着
    private var scriptCode: String? = null
    private var scriptRepeatMs = 0            // 脚本要求的重复注入间隔（0 = 只注入一次）
    // 脚本压着黑幕：页内切台正在发生，黑幕不能按"旧画面有帧"就提前扬了，
    // 得等脚本自己说切完了（TV.afterSwitch）才走正常的首帧揭示流程
    private var scriptHoldCover = false
    // 这次页面里脚本已注入的时间戳（0 = 还没跑过）。配了脚本的页面要先让它跑完再揭幕，
    // 否则会先露出旧频道的画面、再被脚本一切。
    private var scriptRanAt = 0L
    private var scriptUrl: String? = null        // 上次注入时的地址，用来发现 SPA 换路由

    private val darkTint = ColorStateList.valueOf(0xFF0E0E10.toInt())
    private val whiteTint = ColorStateList.valueOf(0xFFFFFFFF.toInt())

    // 触屏点按检测（无面板时点屏呼出）
    private var tapX = 0f
    private var tapY = 0f
    private var tapDownAt = 0L

    // 网页视频全屏（WebChromeClient.onShowCustomView）时的视图与回调
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    // 过渡动画遮罩：打开页面前盖上，网页视频全屏后渐进退出
    private var transitionCover: FrameLayout? = null

    // 页面里的 blob 视频是否已经铺满屏幕（CSS 全屏）
    private var cssFullscreen = false

    // 首帧到位但全屏容器还在改尺寸的起始时刻（0 = 没在等），超宽限期就不再等
    private var settlingSince = 0L

    // 本次进入直播是否已经揭示过画面：为 true 后，页面内部跳转/换源不再盖黑幕（只进入一次）
    private var liveEntered = false

    // 进入直播网页后屏蔽页面操作（只看视频，不让人点网页）
    private var pageInputBlocked = false

    // 调试浮层：把页面里每个 <video> 的 src / 状态显示在屏幕上（排查广告 video）
    private var debugOverlay: TextView? = null

    // 上一次下发给页面的几何标记开关：和当前值不一样就立刻重注入，不等切台
    private var markersApplied: Boolean? = null

    /**
     * 开发者调试里那两个看得见的开关，每 700ms 对一次表：
     * 视频状态浮层该出现的就建出来、该关的就撤掉；几何标记变了就把新值重新注入页面。
     * 做成实时是因为排查时最怕“改了没反应，还得先切个台才能确认”。
     */
    private val devWatch: Runnable = object : Runnable {
        override fun run() {
            syncDevOverlay()
            syncGeomMarkers()
            webView.postDelayed(this, 700)
        }
    }

    // 浮层一行能放几个字：用浮层自己的字体度量现算，不能写死。
    // 写死的话换个字号/屏幕密度就错：给多了末尾被裁、给少了明明有地方也折行。
    private fun overlayCols(tv: TextView): Int {
        val paint = tv.paint
        val charW = paint.measureText("0")
        val avail = (tv.width - tv.paddingLeft - tv.paddingRight)
            .takeIf { it > 0 }
            ?: (resources.displayMetrics.widthPixels - tv.paddingLeft - tv.paddingRight)
        if (charW <= 0f || avail <= 0) return 72
        // 留一个字余量：等宽字体里个别字符（URL 里的斜杠/短横线）宽度不完全一样
        return ((avail / charW).toInt() - 1).coerceAtLeast(20)
    }

    private fun syncDevOverlay() {
        if (!ChannelStore.devFlag("videoOverlay")) {
            debugOverlay?.let { tv ->
                (tv.parent as? ViewGroup)?.removeView(tv)
                debugOverlay = null
            }
            return
        }
        val tv = debugOverlay ?: TextView(this).apply {
            setBackgroundColor(0xCC000000.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(android.graphics.Typeface.MONOSPACE)
            // 11sp + 等宽：一行装得下一条 video 的完整字段，不至于全折成两行
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            visibility = View.GONE
        }.also {
            // 宽度必须占满屏幕：WRAP_CONTENT 会被量成比屏幕还宽的一行，
            // 超出窗口的部分直接被裁掉（字段后半截就丢了），而不是折行
            addContentView(
                it,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                )
            )
            debugOverlay = it
        }
        if (!webOpen) {
            // 直播页没打开时不显示浮层（避免盖住原生首页）
            tv.visibility = View.GONE
            return
        }
        tv.visibility = View.VISIBLE
        webView.evaluateJavascript(debugInfoJs(overlayCols(tv))) { result ->
            if (result != null) {
                runCatching {
                    tv.text = JSONTokener(result).nextValue() as String
                    tv.bringToFront()
                }
            }
        }
    }

    private fun syncGeomMarkers() {
        val on = ChannelStore.devFlag("frameMarkers")
        if (markersApplied == on) return
        // 页面还没开就等着：开页时 window.__tvDbgFrame 会被正常注入，下轮再对表
        if (!webOpen) {
            markersApplied = null
            return
        }
        markersApplied = on
        webView.evaluateJavascript(applyFsJs(), null)
        if (on) webView.evaluateJavascript(GEOM_PROBE_JS, null)
        TvLog.d(TAG, "几何调试：" + if (on) "开" else "关")
    }

    // ============================================================================
    // 直播播放总览（全部通用逻辑，不针对任何具体站点）
    // ----------------------------------------------------------------------------
    // openChannel(url)  —— 换台：重建 WebView（会话不复用）→ liveEntered=false
    //                      → showTransitionCover() 盖一次黑幕 → loadUrl
    //   ↓ 页面加载后由 onPageStarted 尽早注入 APPLY_FS_JS（不等整页加载完）
    // APPLY_FS_JS       —— pickVideo()：在 document（含 open shadowRoot）里挑 src 可播
    //                      （blob: 或 m3u8/flv/mp4 等）且 getBoundingClientRect 面积最大的
    //                      <video>，塞进 #000 fixed 全屏容器、applyStyles 铺满、tryPlay 重试起播。
    //                      返回 '0'=还没找到可播的视频，'1'=已铺满。
    // videoWatch(800ms)  —— 轮询 APPLY_FS_JS，首次返回 '1' 后停轮询、转 frameWatch
    // frameWatch(120ms) —— 等首帧真正呈现（FRAME_READY_JS），置 liveEntered=true、
    //                      hideTransitionCover() 淡入画面（这就是“进入”那一下）
    // fsWatch(1000ms)    —— 进入后持续跟随：换源/广告→正片时复用同一容器换 video，
    //                      并把播放器重写的样式重新落实
    // 只进入一次黑幕     —— liveEntered 置位后，onPageStarted / shouldOverrideUrlLoading
    //                      / onPageFinished 里都不再 showTransitionCover，页面连续
    //                      重定向/换源就不会反复黑屏
    // 登录态共享         —— 所有 WebView 共用全局 CookieManager，同域名登录自动携带
    // 登录页             —— 打开前 stopPlayback() 停掉直播，退出后重播当前频道，新 cookie 才用得上
    // ============================================================================

    /** 轮询页面里的 blob 视频；一出现就把它铺满视口（不需要全屏 API，不需要用户手势） */
    // 每次重建 WebView 都 +1：回包回来时对不上号就是上一个台子迟到的结果，
    // 不能拿来当新页面的状态（否则它会替新页面把轮询停掉、画面永远不铺满）
    private var webGen = 0

    // 声音闸门：起播阶段一直是静音的（页面上先播的那个台不能出声），
    // 等这一源真出画面、黑幕要掀开时才开
    private var soundOn = false

    private val videoWatch: Runnable = object : Runnable {
        override fun run() {
            if (customView != null) return
            // 起播那次必须等黑幕在（没有黑幕就说明已经在播了）；进来以后的静默重铺
            // 没有黑幕，此时只要 liveEntered 就能继续干
            if (transitionCover == null && !liveEntered) return
            val view = webView
            val gen = webGen
            injectViewportSize()
            view.evaluateJavascript(applyFsJs()) { result ->
                if (gen != webGen) return@evaluateJavascript
                if (result?.trim()?.trim('"') == "1") {
                    cssFullscreen = true
                    TvLog.d(TAG, "blob video fullscreen applied")
                    // 播放器就绪这一刻再跑一次脚本：频道栏、当前频道名这时才真的在
                    injectSiteScript()
                    view.removeCallbacks(videoWatch)
                    if (transitionCover != null) {
                        if (ChannelStore.devFlag("frameMarkers")) {
                            view.evaluateJavascript(GEOM_PROBE_JS, null)
                        }
                        // 等视频真正出第一帧再褪黑幕（不拍脑袋定时间）
                        view.removeCallbacks(frameWatch)
                        view.postDelayed(frameWatch, 80)
                    } else {
                        // 静默重铺（站内跳转 / 返回）：没黑幕可等，直接上每秒一轮的看门狗，
                        // 盯着播放器重写样式、换源换 video
                        view.removeCallbacks(fsWatch)
                        view.postDelayed(fsWatch, 1000)
                        // 没黑幕也要等脚本先跑完，再把声音放出来
                        view.removeCallbacks(soundWatch)
                        view.postDelayed(soundWatch, 400)
                    }
                }
            }
            view.postDelayed(this, 800)
        }
    }

    // 页面刚建立就把声音闸门挂上去。
    // 一个页面上有多个频道时，站点自己的播放器可能比我们 400ms 后的第一轮全屏轮询
    // 还早开始播第一个台——那一小段声音就是切台过渡里听到的“别的台在响”。
    // 所以不等轮询：文档一建就盯住新增的 video/audio，闸门没开就静音。
    // 只在闸门关着时生效（window.__tvSoundOn），开声之后不干预页面。
    private val EARLY_MUTE_JS = """
        (function () {
          // Android WebView 的 UA-CH 固定汇报 mobile=true / platform=Android，换 UA 字符串也改不了；
          // 只看 navigator.userAgentData.mobile 的站点就会直接跳到手机站。改成按桌面浏览器汇报。
          try {
            if (!window.__tvUaShim) {
              window.__tvUaShim = 1;
              var brands = [
                { brand: 'Chromium', version: '156' },
                { brand: 'Google Chrome', version: '156' },
                { brand: 'Not?A_Brand', version: '24' }
              ];
              var meta = { mobile: false, platform: 'Windows', brands: brands };
              meta.getHighEntropyValues = function (hints) {
                var all = { architecture: 'x86', bitness: '64', model: '', platformVersion: '15.0.0',
                  uaFullVersion: '156.0.0.0', fullVersionList: brands, wow64: false, mobile: false };
                var out = {};
                (hints || []).forEach(function (k) { if (k in all) out[k] = all[k]; });
                return Promise.resolve(out);
              };
              meta.toJSON = function () {
                return { brands: brands, mobile: false, platform: 'Windows' };
              };
              Object.defineProperty(navigator, 'userAgentData', {
                configurable: true, get: function () { return meta; }
              });
              // 触摸能力同样按桌面报：电视没有触摸屏，WebView 却固定给 5 个触摸点、
              // 指针算 coarse，站点拿这两条加屏幕尺寸就能判定成手机
              try {
                Object.defineProperty(navigator, 'maxTouchPoints', {
                  configurable: true, get: function () { return 0; }
                });
              } catch (e) {}
              try {
                var rawMM = window.matchMedia;
                if (rawMM && !rawMM.__tvPatched) {
                  var mm = function (q) {
                    if (typeof q === 'string' && q.indexOf('pointer') >= 0 && q.indexOf('coarse') < 0) {
                      return {
                        matches: true, media: q, onchange: null,
                        addListener: function () {}, removeListener: function () {},
                        addEventListener: function () {}, removeEventListener: function () {},
                        dispatchEvent: function () { return false; }
                      };
                    }
                    return rawMM.call(window, q);
                  };
                  mm.__tvPatched = 1;
                  window.matchMedia = mm;
                }
              } catch (e) {}
            }
          } catch (e) {}
          if (window.__tvMuteHook) return '1';
          window.__tvMuteHook = 1;
          function mute(el) {
            try { if (window.__tvSoundOn !== 1 && !el.muted) el.muted = true; } catch (e) {}
          }
          function scan(root) {
            if (!root || !root.querySelectorAll) return;
            try {
              var els = root.querySelectorAll('video, audio');
              for (var i = 0; i < els.length; i++) mute(els[i]);
            } catch (e) {}
          }
          scan(document);
          try {
            new MutationObserver(function (ms) {
              for (var i = 0; i < ms.length; i++) {
                var nodes = ms[i].addedNodes;
                for (var j = 0; j < nodes.length; j++) {
                  var n = nodes[j];
                  if (!n || n.nodeType !== 1) continue;
                  if (n.tagName === 'VIDEO' || n.tagName === 'AUDIO') mute(n);
                  else scan(n);
                }
              }
            }).observe(document.documentElement || document, { childList: true, subtree: true });
          } catch (e) {}
          // 播放器也可能自己把静音关掉再播：拦住 play 这一下
          try {
            var proto = HTMLMediaElement.prototype, rawPlay = proto.play;
            proto.play = function () {
              // 铺满之外的那一路（典型：直播起来之后才被插进来的广告）即使声音闸门已经打开
              // 也不能出声，否则直播画面里会混进广告音
              var cur = window.__tvFs && window.__tvFs.el;
              if (cur && this !== cur) { try { this.muted = true; } catch (e) {} }
              mute(this);
              return rawPlay.apply(this, arguments);
            };
          } catch (e) {}
          return '1';
        })();
    """

    // 等这一源连续交出画面帧，然后才淡入直播画面
    private val frameWatch: Runnable = object : Runnable {
        override fun run() {
            if (transitionCover == null) return
            // 脚本还在页内切台：这时的"有帧"是旧频道的帧，扬了幕就是先给看旧频道再一跳
            if (scriptHoldCover) {
                webView.postDelayed(this, 120)
                return
            }
            // 配了脚本的页面：等脚本至少跑过一轮（并给它几百毫秒发 holdCover）再揭幕。
            // 只有配了脚本的页面才有这个等待，普通频道不受影响。
            if (scriptCode != null &&
                (scriptRanAt == 0L || SystemClock.uptimeMillis() - scriptRanAt < SCRIPT_GRACE_MS)
            ) {
                webView.postDelayed(this, 120)
                return
            }
            val view = webView
            val gen = webGen
            view.evaluateJavascript(FRAME_READY_JS) { result ->
                // 旧实例迟到的首帧回包：别拿它把新页面的黑幕掘掉
                if (gen != webGen) return@evaluateJavascript
                // 起播那几秒主线程被页面加载压着，排队的几次探测回调会挤在一起回来：
                // 幕已经褪了就不再重复揭示
                if (transitionCover == null) return@evaluateJavascript
                when (result?.trim()?.trim('"')) {
                    "1" -> {
                        settlingSince = 0L
                        liveEntered = true
                        // 目标台的画面已经在了：这时才开声音（起播阶段一直静音）
                        setSoundOn(true)
                        hideTransitionCover()
                    }
                    // 画面到位但容器还在改尺寸：黑幕再压一会儿，露出来就是稳的。
                    // 压过宽限期就不再等 —— 不能为了对齐尺寸把画面一直黑着
                    "2" -> {
                        if (settlingSince == 0L) settlingSince = SystemClock.uptimeMillis()
                        if (SystemClock.uptimeMillis() - settlingSince > FRAME_SETTLE_MS) {
                            TvLog.d(TAG, "reveal after settle grace")
                            settlingSince = 0L
                            liveEntered = true
                            setSoundOn(true)
                            hideTransitionCover()
                        }
                    }
                }
            }
            view.postDelayed(this, 120)
        }
    }

    // 全屏后的看门狗：跟着切到正在播的 video，并把被播放器重写的样式重新落实
    private var geomTick = false

    private val fsWatch: Runnable = object : Runnable {
        override fun run() {
            if (!cssFullscreen) return
            val view = webView
            injectViewportSize()
            view.evaluateJavascript(applyFsJs(), null)
            // SPA 换路由不会触发新页面加载：地址变了就把脚本重新匹配、重新跑一次
            val now = view.url
            if (now != null && now != scriptUrl) {
                scriptUrl = now
                val fresh = SiteScripts.forUrl(now)?.code
                scriptCode = fresh
                scriptRanAt = 0L
                if (fresh != null) injectSiteScript()
            }
            // 几何日志每秒一轮太吵，隔轮打一次（只在调试边界标记打开时）
            if (ChannelStore.devFlag("frameMarkers")) {
                geomTick = !geomTick
                if (geomTick) view.evaluateJavascript(GEOM_PROBE_JS, null)
            }
            view.postDelayed(this, 1000)
        }
    }

    /**
     * 把 WebView 自己的像素尺寸交给页面。
     *
     * 全屏容器要铺满“屏幕上真正看得见的那块”，而页面自己报的尺寸可能是别的数：
     * 没有 viewport meta 的页面布局视口可能比屏幕宽（innerWidth=1008、可视只有 851），
     * 换台那一瞬间 visualViewport 还可能停在布局视口上没定型 —— 按它铺出来的容器
     * 比屏幕大一圈，画面就被推到屏幕外、只剩一部分（切台快了才偶发）。
     * 原生这边知道 WebView 到底占多少设备像素，交给 JS 除以当前页面缩放，
     * 得到的才是可视区的 CSS 像素，每一轮都重新给，定错了下一轮就自己纠回来。
     */
    private fun injectViewportSize() {
        if (!::webView.isInitialized) return
        val w = webView.width
        val h = webView.height
        if (w <= 0 || h <= 0) return
        webView.evaluateJavascript("window.__tvDev={w:$w,h:$h}", null)
    }

    // 起播兜底：点台后 15 秒内没等到视频首帧，就当这个台起不来，收黑幕并显示错误页。
    // 计时只在 openChannel 起一次，页面中途重定向/换源不再续表（有些页面会一直跳）
    private val coverTimeout = Runnable {
        playTimeoutArmed = false
        // 脚本一直压着黑幕却没报切换完成（脚本挂了/写错了）：放它走，
        // 宁可露出旧画面也不能一直黑着，更不能因此弹错误页
        if (scriptHoldCover) {
            TvLog.d(TAG, "script held cover too long, release")
            scriptHoldCover = false
            restartVideoWatch()
            return@Runnable
        }
        // 配了脚本但脚本一直没跑成（页面迟迟没加载完）：补注入一次、再给一轮时间。
        // 不能因为脚本没跑就把这个台判成起不来。
        if (scriptCode != null && scriptRanAt == 0L) {
            TvLog.d(TAG, "play timeout, inject script late")
            injectSiteScript()
            restartVideoWatch()
            armPlayTimeout()
            return@Runnable
        }
        if (customView == null && transitionCover != null) {
            TvLog.d(TAG, "play timeout, show error page")
            showPlayError()
        }
    }

    private fun armPlayTimeout() {
        if (playTimeoutArmed) return
        playTimeoutArmed = true
        webView.removeCallbacks(coverTimeout)
        webView.postDelayed(coverTimeout, PLAY_TIMEOUT_MS)
    }

    private fun disarmPlayTimeout() {
        playTimeoutArmed = false
        webView.removeCallbacks(coverTimeout)
    }

    /** 起播超时：先掐掉起不来的网页（释放它的播放器/解码器/JS 堆），再显示本地错误页 */
    private fun showPlayError() {
        webView.stopLoading()
        hideTransitionCover()
        webView.loadUrl("$ERROR_URL?msg=" + Uri.encode(PLAY_ERROR_TEXT))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        // 必须在 setContentView 之后：此时 DecorView 才存在
        hideSystemUi()

        panelRoot = findViewById(R.id.panelRoot)
        panelBody = findViewById(R.id.panelBody)
        panelList = findViewById<RecyclerView>(R.id.panelList).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
            // 换组/换台时不要做条目动画：动画期间焦点会挂在被回收的行上
            itemAnimator = null
        }
        panelEmpty = findViewById(R.id.panelEmpty)
        groupName = findViewById(R.id.groupName)
        groupCount = findViewById(R.id.groupCount)
        groupPrev = findViewById(R.id.groupPrev)
        groupNext = findViewById(R.id.groupNext)
        qrImage = findViewById(R.id.qrImage)
        qrUrl = findViewById(R.id.qrUrl)
        qrPin = findViewById(R.id.qrPin)
        qrRegen = findViewById(R.id.qrRegen)
        infoPanel = findViewById(R.id.infoPanel)
        infoTitle = findViewById(R.id.infoTitle)
        infoState = findViewById(R.id.infoState)
        infoQuality = findViewById(R.id.infoQuality)
        infoSource = findViewById(R.id.infoSource)
        infoClock = findViewById(R.id.infoClock)
        infoDate = findViewById(R.id.infoDate)
        infoNow = findViewById(R.id.infoNow)
        infoRemain = findViewById(R.id.infoRemain)
        infoNext = findViewById(R.id.infoNext)
        infoProgress = findViewById(R.id.infoProgress)
        guideNextLabel = findViewById(R.id.guideNextLabel)
        guideRows = listOf<View>(findViewById(R.id.guideTrack), findViewById(R.id.guideDivider), guideNextLabel)
        // 换码按钮：tvOS 的焦点语言是放大提亮，不是描边
        qrRegen.setOnClickListener { rotatePin() }
        qrRegen.addFocusScale()
        // 组头箭头：触屏没有左右键，换组得有地方按；遥控器仍走列表里的左右键
        groupPrev.setOnClickListener { stepGroup(-1) }
        groupNext.setOnClickListener { stepGroup(1) }

        // 点空白处收起遮罩
        panelRoot.setOnClickListener { hidePanel() }

        webView = findViewById(R.id.webView)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        setupWebView()

        // 底层常驻播放：直接播第一个频道，遮罩随时呼出
        ChannelStore.onChanged = { onStoreChanged() }
        reloadChannels()
        val first = ChannelStore.channels().firstOrNull()
        if (first == null) showPairing()          // 出厂不带频道：先教用户怎么连配置页
        else {
            playingLine = first.line
            renderPanel()
            openChannel(first.url)
        }
        renderQrCard()
        // 节目单到货（闹钟或启动补拉）后重画信息栏
        EpgManager.onChanged = { renderGuide() }
        renderInfo(null)
        infoPanel.postDelayed(clockTick, 20_000)

        // 开发者调试里那两个看得见的开关：浮层和几何标记都由这一条心跳实时对表
        webView.postDelayed(devWatch, 1200)
    }

    // ---------- 遮罩：频道信息 / 频道列表 / 手机配置二维码 ----------

    /**
     * 从 ChannelStore 重读频道并重建面板。
     * 数据只由配置网页写入，这里负责人家在落盘后把界面跟上。
     */
    private fun reloadChannels() {
        listVersion++
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in ChannelStore.channels()) map.getOrPut(ch.group) { mutableListOf() }.add(ch)
        groups = map.map { Group(it.key, it.value) }
        curGroupIdx = curGroupIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        // 在播频道可能已被删掉：行号找不到就当没在播，避免角标和实际画面不一致
        if (playingLine >= 0 && groups.asSequence().flatMap { it.channels.asSequence() }
                .none { it.line == playingLine }
        ) playingLine = -1
        renderPanel()
    }

    /**
     * 配置网页改了数据：重建面板并重新认一次在播频道。
     * 行号是文件里的物理行，任何一次增删都会让后面的频道整体位移，
     * 所以不能沿用旧行号——要按地址重新定位，否则“播放中”角标会挂到别的频道上。
     */
    private fun onStoreChanged() {
        val url = playingChannel()?.url
        reloadChannels()
        playingLine = url?.let { u ->
            groups.asSequence().flatMap { it.channels.asSequence() }
                .firstOrNull { it.url == u }?.line
        } ?: -1
        renderPanel()
        renderInfo(null)
        if (ChannelStore.channels().isEmpty()) showPairing() else hidePairing()
    }

    // ---------- 配对页（配置网址二维码 + 配对码） ----------

    private var pairing: PairingView? = null

    /** 配置页地址：用实时局域网 IP，换网络后重新生成即失效 */
    private fun configUrl(): String? =
        Net.localIp()?.let { "http://$it:${TvApp.CONFIG_PORT}" }

    /** 配置页地址：用实时局域网 IP，换网络后重新生成即失效 */
    private fun renderQrCard() {
        val url = configUrl()
        qrUrl.text = url?.removePrefix("http://") ?: "未联网：连上 WiFi 后再试"
        qrPin.text = ChannelStore.pin
        url?.let { qrImage.setImageBitmap(Qr.bitmap(it, 440)) }
    }

    private fun rotatePin() {
        ChannelStore.regeneratePin()
        renderQrCard()
        pairing?.refresh(configUrl(), ChannelStore.pin)
        toast("已换新配对码 ${ChannelStore.pin}")
    }

    /** tvOS 的焦点反馈：聚焦项放大提亮，而不是给它描一条边 */
    private fun View.addFocusScale() = setOnFocusChangeListener { v, has ->
        val s = if (has) 1.08f else 1f
        v.scaleX = s
        v.scaleY = s
        v.alpha = if (has) 1f else 0.7f
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun showPairing() {
        val v = pairing ?: PairingView(this).also { fresh ->
            fresh.onRegenerate = {
                fresh.refresh(configUrl(), ChannelStore.regeneratePin())
            }
            pairing = fresh
        }
        v.refresh(configUrl(), ChannelStore.pin)
        if (v.parent == null) {
            addContentView(v, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        v.visibility = View.VISIBLE
        v.requestFocus()
        hideSystemUi()
    }

    private fun hidePairing() {
        pairing?.takeIf { it.visibility == View.VISIBLE }?.visibility = View.GONE
    }

    private val pairingOpen get() = pairing?.visibility == View.VISIBLE

    // ---------- 配置服务落到界面上的动作 ----------

    override fun playing(): Channel? = playingChannel()

    override fun play(group: String, name: String, url: String): Boolean {
        if (isFinishing) return false
        // 优先认列表里已有的条目，行号和台标才对得上；网页刚加完还没回读时退回合成条目
        val ch = groups.asSequence().flatMap { it.channels.asSequence() }
            .firstOrNull { it.url == url }
            ?: Channel(name, url, -1, group, name, "HD")
        hidePairing()
        hidePanel()
        playingLine = ch.line
        // 手机点的台可能在别的分组：面板跟着跳过去，回来按呼出键才看得到它高亮
        groups.indexOfFirst { g -> g.channels.any { it.line == ch.line } }
            .takeIf { it >= 0 }?.let { curGroupIdx = it }
        renderPanel()
        renderInfo(ch)
        openChannel(ch.url)
        return true
    }

    /** 打开登录页：直播先停下，本页退到后面；退出登录页时重播当前频道 */
    override fun openLogin(url: String) {
        hidePairing()
        hidePanel()
        stopPlayback()
        needReloadAfterLogin = true
        startActivity(Intent(this, LoginActivity::class.java).putExtra("url", url))
    }

    /** 中栏整体重画：组名/计数 + 换组箭头可用性 + 当前分组的频道列表 */
    private fun renderPanel() {
        val g = groups.getOrNull(curGroupIdx)
        groupName.text = g?.name ?: "暂无频道"
        groupCount.text = if (g == null) "" else "${curGroupIdx + 1} / ${groups.size} · ${g.channels.size} 个"
        // 首尾没有可换的组，箭头压暗禁用
        groupPrev.isEnabled = curGroupIdx > 0
        groupNext.isEnabled = curGroupIdx < groups.size - 1
        groupPrev.alpha = if (groupPrev.isEnabled) 1f else 0.35f
        groupNext.alpha = if (groupNext.isEnabled) 1f else 0.35f
        renderChannelList()
    }

    /** 左右翻一组（260ms 防抖，连按遥控器不会糊过去） */
    private fun stepGroup(delta: Int) {
        val target = curGroupIdx + delta
        if (target !in groups.indices) return
        val now = SystemClock.uptimeMillis()
        if (now - lastGroupNavAt < 260) return
        lastGroupNavAt = now
        showGroup(target)
    }

    /** 切到指定分组：重画列表，光标落在本组的在播频道（没有就第一行） */
    private fun showGroup(idx: Int) {
        if (idx !in groups.indices) return
        curGroupIdx = idx
        listIndexByGroup[idx] = openListIndex(idx)
        renderPanel()
        focusListIndex(listIndexByGroup[idx] ?: 0)
        if (infoFollowFocus) renderInfo(focusedChannel())
        persistFocus()
    }

    /**
     * 频道列表走视图回收：一组上百个频道，全建真实 View 的话光 measure/layout
     * 就能把呼出面板那一下顶掉好几帧，屏上其实只需要十几行。
     */
    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {

        inner class Holder(val row: View) : RecyclerView.ViewHolder(row)

        override fun getItemCount(): Int = groups.getOrNull(curGroupIdx)?.channels?.size ?: 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val row = newRow()
            val holder = Holder(row)
            row.setOnFocusChangeListener { v, hasFocus ->
                val position = holder.bindingAdapterPosition
                val ch = channelAt(position) ?: return@setOnFocusChangeListener
                styleRow(v, hasFocus || playingNow(ch))
                if (hasFocus) {
                    listIndexByGroup[curGroupIdx] = position
                    if (infoFollowFocus) renderInfo(ch)
                    // 光标行摆到可视区上三分之一：焦点落定后 RecyclerView 只做“露出来”的最小滚动，
                    // 位置得自己挪（这一帧末尾再挪，避免和它的滚动叠成两下）
                    panelList.post { focusToThird(position) }
                    persistFocus()
                }
            }
            row.setOnClickListener {
                channelAt(holder.bindingAdapterPosition)?.let { playChannel(it) }
            }
            row.setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> { stepGroup(-1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { stepGroup(1); true }
                    KeyEvent.KEYCODE_DPAD_UP -> { moveFocus(-1); true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { moveFocus(1); true }
                    else -> false
                }
            }
            return holder
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val ch = channelAt(position) ?: return
            val row = holder.row
            row.findViewById<TextView>(R.id.rowName).text = ch.name
            val playing = playingNow(ch)
            row.findViewById<View>(R.id.playBadge).visibility =
                if (playing) View.VISIBLE else View.GONE
            // 回收来的行带着上一条目的白药丸，绑定时按当前状态重刷一遍
            styleRow(row, row.isFocused || playing)
        }

        private fun channelAt(position: Int): Channel? =
            groups.getOrNull(curGroupIdx)?.channels?.getOrNull(position)
    }

    private val channelAdapter = ChannelAdapter()

    /** 渲染当前分组的频道列表（正在播放的行带声波角标） */
    private fun renderChannelList() {
        val g = groups.getOrNull(curGroupIdx)
        panelEmpty.visibility = if (g == null || g.channels.isEmpty()) View.VISIBLE else View.GONE
        // 只有换组、换台或配置页改了数据才重绑，其余时候原样留着
        val key = Triple(curGroupIdx, listVersion, playingLine)
        if (listBuiltFor == key) return
        listBuiltFor = key
        channelAdapter.notifyDataSetChanged()
    }

    /** 行视图代码直搭，不走 XML inflate：每行 inflate 要一两毫秒，一屏十几行也是白花的 */
    private fun newRow(): View {
        val row = LinearLayout(this).apply {
            layoutParams = RecyclerView.LayoutParams(
                RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(3) }
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.panel_row)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(18), dp(7), dp(18), dp(7))
        }
        row.addView(
            TextView(this).apply {
                id = R.id.rowName
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 18f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = dp(400)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
        // 名字靠左，中间留白由这块吸收，角标顶到行尾（整行仍是可点的焦点区）
        row.addView(
            View(this),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        row.addView(newBadge())
        return row
    }

    private fun newBadge(): View {
        val badge = LinearLayout(this).apply {
            id = R.id.playBadge
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
        }
        badge.addView(
            BadgeIconView(this).apply {
                id = R.id.playBadgeIcon
                setImageResource(R.drawable.badge_playing)
            },
            LinearLayout.LayoutParams(dp(17), dp(16))
        )
        badge.addView(
            TextView(this).apply {
                id = R.id.badgeLabel
                text = "播放中"
                setTextColor(0xFFFFFFFF.toInt())
                textSize = 13f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(7) }
        )
        return badge
    }

    // ---------- 左列频道信息（在播·预览状态 / EPG / 系统时间） ----------

    /** 信息栏跟着焦点预览；ch 为 null 表示回到“在播频道” */
    private fun renderInfo(ch: Channel?) {
        val target = ch ?: playingChannel() ?: return
        infoTarget = target
        val playing = playingNow(target)
        infoTitle.text = target.name
        // 清晰度和来源都是可选信息：有值才占位，空着不留空药丸
        infoQuality.text = target.quality
        infoQuality.visibility = if (target.quality.isNotEmpty()) View.VISIBLE else View.GONE
        infoSource.text = target.source
        infoSource.visibility = if (target.source.isNotEmpty()) View.VISIBLE else View.GONE
        // 电视只有光标没有鼠标，信息栏必须说清这是“在播的台”还是“正在预览的台”
        infoState.text = if (playing) "正在播放" else "预览中"
        infoState.setBackgroundResource(
            if (playing) R.drawable.info_state_playing else R.drawable.info_state_preview
        )
        infoState.setTextColor(if (playing) 0xFF0E0E10.toInt() else 0xE6FFFFFF.toInt())
        updateInfoClock()
        renderGuide()
    }

    /** 节目单：在播标题 + 时段/剩余 + 进度线 + 下一档；没对上频道就退回占位文案 */
    private fun renderGuide() {
        val ch = infoTarget ?: return
        val guide = if (EpgManager.ready()) {
            EpgManager.guideFor(ch.name).takeIf { it.total > 0 } ?: EpgManager.guideFor(ch.logo)
        } else null
        if (guide == null || guide.total == 0) {
            infoNow.text = when {
                guide != null -> "节目单未收录该频道"
                EpgManager.loaded -> "节目单已过截止时间（${EpgManager.coverageText}）"
                EpgManager.attempted -> "节目单获取失败"
                else -> "节目单加载中…"
            }
            infoRemain.text = ""
            infoNext.text = ""
            guideNextLabel.text = "接下来"
            guideRows.forEach { it.visibility = View.GONE }
            return
        }
        guideRows.forEach { it.visibility = View.VISIBLE }
        infoNow.text = guide.nowText
        // 左列窄，时段和剩余都压成最短写法，免得被省略号截掉
        infoRemain.text = guide.now?.let {
            "${guide.timeText.replace(" ~ ", "~")} · 剩${leftMinutes(it.stop)}"
        } ?: guide.timeText
        // 开档时间跟在「接下来」后面：节目单不锁行数，标题再长也整行留给标题
        val next = guide.next
        guideNextLabel.text = next?.let { "接下来 ${clockFmt.format(Date(it.start))}" } ?: "接下来"
        infoNext.text = next?.title ?: "暂无节目单"
        setGuideProgress(guide.percent)
    }

    /** 离这一档结束还有多久：不足一分钟按一分钟算，跨小时也照样用分钟说（电视上一眼要看完） */
    private fun leftMinutes(stopAt: Long): String {
        val m = Math.ceil((stopAt - System.currentTimeMillis()) / 60000.0).toInt()
        return if (m <= 0) "即将结束" else if (m < 60) "${m}分" else "${m / 60}时${m % 60}分"
    }

    private fun setGuideProgress(pct: Int) {
        val track = infoProgress.parent as? View ?: return
        val w = track.width
        if (w == 0) {
            // 面板刚展开时还没测量，等一帧再画
            if (panelOpen) infoProgress.post { renderGuide() }
            return
        }
        val target = if (pct <= 0) 0 else (w * pct / 100).coerceAtMost(w)
        val lp = infoProgress.layoutParams
        if (lp.width != target) {
            lp.width = target
            infoProgress.layoutParams = lp
        }
    }

    /**
     * 该频道是否正在播放。登录条目和配对条目没有行号（-1），而 playingLine 为 -1
     * 表示“未在播”，两者直接比会把它们点亮成在播，所以必须先挡掉 -1。
     */
    private fun playingNow(ch: Channel) = playingLine >= 0 && ch.line == playingLine

    /** 在播频道。登录条目和配对条目没有行号（-1），未播放时也必须返回 null，否则会被误认成在播 */
    private fun playingChannel(): Channel? {
        if (playingLine < 0) return null
        return groups.asSequence().flatMap { it.channels.asSequence() }
            .firstOrNull { it.line == playingLine }
    }

    /** 焦点所在频道（浏览位置）；该组还没记位置时退回首行 */
    private fun focusedChannel(): Channel? =
        groups.getOrNull(curGroupIdx)?.channels?.getOrNull(listIndexByGroup[curGroupIdx] ?: 0)

    private fun updateInfoClock() {
        val now = Date()
        infoClock.text = clockFmt.format(now)
        infoDate.text = dateFmt.format(now)
    }

    private val clockTick = object : Runnable {
        override fun run() {
            updateInfoClock()
            renderGuide()
            infoPanel.postDelayed(this, 20_000)
        }
    }

    /** tvOS 光标：聚焦/在播的行浮出白色药丸、文字转深色；普通行透明白字 */
    private fun styleRow(row: View, on: Boolean) {
        row.setBackgroundResource(if (on) R.drawable.panel_row_focus else R.drawable.panel_row)
        row.findViewById<TextView>(R.id.rowName)?.setTextColor(
            if (on) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()
        )
        // 角标跟文字一起反色：白药丸上转深色，暗行上保持白色，否则黑底黑条看不见。
        // 换 tint 等于把 drawable 重置一遍，所以换完要让动画重新起来
        val icon = row.findViewById<BadgeIconView>(R.id.playBadgeIcon)
        val tint = if (on) darkTint else whiteTint
        if (icon != null && icon.tag !== tint) {
            icon.tag = tint
            icon.imageTintList = tint
            icon.play(row.findViewById<View>(R.id.playBadge)?.visibility == View.VISIBLE)
        }
        row.findViewById<TextView>(R.id.badgeLabel)?.setTextColor(
            if (on) 0xFF0E0E10.toInt() else 0xFFFFFFFF.toInt()
        )
    }

    /**
     * 上下换行自己走：回收列表里的行随时可能被拆掉，系统默认焦点搜索一挑就挑空，
     * 遥控器上下键等于哑巴。这里按位置取，屏上就直接抢焦点（RecyclerView 会顺手滚进视野），
     * 已经滚出屏的就先铺过去再抢。
     */
    private fun moveFocus(delta: Int) {
        val g = groups.getOrNull(curGroupIdx) ?: return
        if (g.channels.isEmpty()) return
        val target = ((listIndexByGroup[curGroupIdx] ?: 0) + delta)
            .coerceIn(0, g.channels.size - 1)
        val row = panelList.findViewHolderForAdapterPosition(target)?.itemView
        if (row != null) row.requestFocus() else focusListIndex(target)
    }

    /** 光标行摆到可视区上三分之一处（居中偏上，改造前就是这个位） */
    private fun focusToThird(pos: Int) {
        val row = panelList.findViewHolderForAdapterPosition(pos)?.itemView ?: return
        val delta = row.top - panelList.height / 3
        if (delta != 0) panelList.smoothScrollBy(0, delta)
    }

    /** 把光标放到列表第 index 行：回收列表里这行不一定已经建出来，先滚过去再抢焦点 */
    private fun focusListIndex(index: Int) {
        val g = groups.getOrNull(curGroupIdx) ?: return
        if (g.channels.isEmpty()) return
        val pos = index.coerceIn(0, g.channels.size - 1)
        // 落点直接按“上三分之一”给，省掉抢完焦点再补一次滚动
        (panelList.layoutManager as LinearLayoutManager)
            .scrollToPositionWithOffset(pos, panelList.height / 3)
        focusRow(pos, 4)
    }

    /** 滚动要到下一帧才落位，那时条目才建得出来，所以补几次尝试 */
    private fun focusRow(pos: Int, tries: Int) {
        panelList.post {
            val row = panelList.findViewHolderForAdapterPosition(pos)?.itemView
            when {
                row != null -> row.requestFocus()
                tries > 0 -> focusRow(pos, tries - 1)
            }
        }
    }

    /** 选中频道：切台后面板保持打开（可连续换台）；点的正是在播频道则只收起面板 */
    private fun playChannel(ch: Channel) {
        if (playingNow(ch)) {
            hidePanel()
            return
        }
        playingLine = ch.line
        renderPanel()
        renderInfo(ch)
        persistFocus()
        openChannel(ch.url)
    }

    /** 面板毛玻璃：面板可见（含切台加载期间）把底层视频层整体高斯模糊（API31+ 实时 GPU 模糊，低版本不做） */
    private fun syncBackdropBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val effect = if (panelOpen) {
            android.graphics.RenderEffect.createBlurEffect(
                30f, 30f, android.graphics.Shader.TileMode.CLAMP
            )
        } else null
        webView.setRenderEffect(effect)
        customView?.setRenderEffect(effect)
    }

    /** 进组的落点：在播频道在本组内就选它，否则第一行 */
    private fun openListIndex(gi: Int): Int {
        val g = groups.getOrNull(gi) ?: return 0
        if (g.channels.isEmpty()) return 0
        return g.channels.indexOfFirst { it.line == playingLine }.coerceAtLeast(0)
    }

    /**
     * 呼出遮罩：回到用户上次浏览的分组，列表落在正在播放的频道（本组没有在播频道则第一行）
     */
    private fun showPanel() {
        if (panelOpen || groups.isEmpty()) return
        panelOpen = true
        panelRoot.animate().cancel()
        syncBackdropBlur()
        restoreFocus()
        val gi = curGroupIdx.coerceIn(0, groups.size - 1)
        curGroupIdx = gi
        val li = openListIndex(gi)
        panelRoot.visibility = View.VISIBLE
        panelRoot.alpha = 0f
        panelRoot.animate().alpha(1f).setDuration(250).start()
        renderPanel()
        renderQrCard()
        // 刚打开时信息栏先给“在播频道”，等恢复焦点的那一下过去再跟着焦点走
        infoFollowFocus = false
        renderInfo(null)
        panelRoot.post {
            focusListIndex(li)
            panelRoot.post { infoFollowFocus = true }
        }
        hideSystemUi()
    }

    private fun hidePanel() {
        if (!panelOpen) return
        if (panelRoot.visibility != View.VISIBLE) { panelOpen = false; return }
        panelOpen = false
        renderInfo(null)
        persistFocus()
        panelRoot.animate().cancel()
        panelRoot.animate().alpha(0f).setDuration(250)
            .withEndAction { if (!panelOpen) panelRoot.visibility = View.GONE }
            .start()
        syncBackdropBlur()
        webView.requestFocus()
    }

    // 分组记忆：只记上次停留在哪个分组（组内落点由在播频道决定，不需要记忆）
    private fun persistFocus() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt("group", curGroupIdx)
            .apply()
    }

    private fun restoreFocus() {
        curGroupIdx = getSharedPreferences(PREFS, MODE_PRIVATE).getInt("group", 0)
    }

    // ---------- 直播页 ----------

    /**
     * 打开 / 切换直播频道：盖过渡黑幕 → WebView 加载。
     * 每次换台都重建实例：会话不能复用，残留的上一个站点文档会让新页面起不了播、
     * 也铺不满屏（首帧探测还会立刻命中旧视频，黑幕提前褪去露出上一个台）
     */
    private fun openChannel(url: String) {
        if (webOpen) recreateWebView()
        webOpen = true
        liveEntered = false
        // 新台重新静音：页面先自己期起播的那个频道不能出声，等黑幕要掀开时才开
        soundOn = false
        webView.removeCallbacks(soundWatch)
        // 新台重新计时：上一站脚本压着的黑幕不能带过来
        scriptHoldCover = false
        scriptRepeatMs = 0
        playTimeoutArmed = false   // 新台重新计时：showTransitionCover 里才会真的排期
        showTransitionCover()
        webView.visibility = View.VISIBLE
        webView.requestFocus()
        // 配置页从频道行打开的日志从这里往后看，就是这一次播的
        TvLog.record("▶ 播放 " + url)
        webView.loadUrl(url)
    }

    /** 丢掉整个网页会话：新建 WebView 实例，旧页面的画面、播放器、JS 一起销毁 */
    private fun recreateWebView() {
        val old = webView
        val parent = old.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(old)
        val lp = old.layoutParams
        // 首帧/全屏那几个轮询是挂在主线程 Handler 上的，不清掉会在新生成实例里叠成两份
        old.removeCallbacks(videoWatch)
        old.removeCallbacks(frameWatch)
        old.removeCallbacks(fsWatch)
        old.removeCallbacks(soundWatch)
        // 上一个台子的起播超时也要清：留着会在新页面加载中途把黑幕掀开
        old.removeCallbacks(coverTimeout)
        (customView?.parent as? ViewGroup)?.removeView(customView)
        customView = null
        customViewCallback = null
        cssFullscreen = false
        docUrl = null
        webGen++
        parent.removeView(old)
        old.destroy()
        val fresh = WebView(this)
        parent.addView(fresh, index, lp)
        fresh.isFocusable = true
        fresh.isFocusableInTouchMode = true
        webView = fresh
        setupWebView()
    }

    /**
     * 进登录页前把直播停下：掐掉还在跑的请求，再把页面里的 video/audio 全按停。
     * 回来时不接这半口气的流，直接重播当前频道。
     */
    private fun stopPlayback() {
        webView.stopLoading()
        webView.removeCallbacks(videoWatch)
        webView.removeCallbacks(frameWatch)
        webView.removeCallbacks(fsWatch)
        webView.removeCallbacks(soundWatch)
        webView.removeCallbacks(coverTimeout)
        if (cssFullscreen) exitCssFullscreen()
        webView.evaluateJavascript(PAUSE_MEDIA_JS, null)
    }

    /** 沉浸式全屏：隐藏状态栏和导航栏 */
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 走 androidx 的兼容入口：系统那个 setDecorFitsSystemWindows 在 API 35 已标记过时
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // 直播自动播放，不等用户手势
            mediaPlaybackRequiresUserGesture = false
            // 部分直播源是 http，允许混合内容
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            useWideViewPort = true
            loadWithOverviewMode = true
            // target=_blank 的链接在当前窗口打开
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = true
            cacheMode = WebSettings.LOAD_DEFAULT
            // PC 桌面浏览器 UA：网站返回电脑版页面
            userAgentString = TV_USER_AGENT
            setAllowFileAccess(true)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        // 页面里 window.TV 的回调出口。注入的对象对页面里任何 JS 都可见，
        // 所以真正的闸在 onScriptMessage：当前页面没匹配到脚本就一律不理
        webView.addJavascriptInterface(TvBridge(), "TVNative")

        webView.webChromeClient = object : WebChromeClient() {

            /** 网页请求视频全屏（blob 播放器的全屏按钮或 JS requestFullscreen） */
            override fun onShowCustomView(
                view: View,
                callback: WebChromeClient.CustomViewCallback
            ) {
                if (customView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                webView.visibility = View.GONE
                // 插到频道面板之下：全屏视频之上还能正常呼出面板
                val root = findViewById<FrameLayout>(R.id.root)
                root.addView(
                    view, root.indexOfChild(panelRoot),
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                if (panelOpen) syncBackdropBlur() // 面板开着时视频才全屏：新层也要模糊
                // 视频全屏了：过渡动画渐进退出，露出画面
                val cover = transitionCover
                if (cover != null) {
                    hideTransitionCover()
                } else {
                    TvLog.d(TAG, "onShowCustomView")
                // 没有过渡动画时：黑屏淡出兜底，避免页面元素闪现
                    val fade = FrameLayout(this@MainActivity).apply {
                        setBackgroundColor(0xFF000000.toInt())
                    }
                    addContentView(
                        fade,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                    fade.animate().alpha(0f).setDuration(300)
                        .withEndAction { (fade.parent as? ViewGroup)?.removeView(fade) }
                        .start()
                }
                hideSystemUi()
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            /**
             * 页面 console：一律进 logcat；配置页的日志区只留页面自己 log 的与未捕获异常，
             * 否则光一屏浏览器警告就能把有用的播报冲掉。
             */
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                val text = message.message()
                Log.d(TAG, "console: $text")
                // 只留两类：我们自己注入的脚本打的（都以 [ 开头）、以及页面里的未捕获异常。
                // 站点自己的 console 输出很多，全收就会把播放事件淹了。
                if (text.startsWith("[tv") || text.contains("Uncaught")) {
                    TvLog.record("console: $text")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url ?: return false
                val scheme = url.scheme?.lowercase()
                return if (scheme == "http" || scheme == "https") {
                    // 打开外部页面：还没进入过直播才盖过渡动画（进入后跳转不再黑屏）
                    if (!liveEntered) showTransitionCover()
                    false
                } else if (scheme == "file") {
                    false // 本地错误页，留在应用内打开
                } else {
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, url))
                    } catch (_: Exception) {
                        // 没有能处理该协议的应用，忽略即可
                    }
                    true
                }
            }

            /**
             * 不加载网页的图片 / 字体等资源（只拦 http/https 的网络请求，
             * 本地 file:// 首页资源不受影响）。CSS / 视频 / JS 等一律放行：样式表不拦，
             * 页面就不会变成无样式排版（那种状态下隐藏层全显形、懒加载也失去高度边界）。
             * 另外整域拦截统计/广告脚本，以及 sourcemap / manifest 这类对播放无用的资源。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                // 两个开关各管一件事（开关都在配置页「开发者调试」）：
                // blockStyle 只管“拦不拦图片/字体这类资源”（CSS 不在其中），
                // 关掉它也不让脚本缓存跟着失效。
                val blockStyle = ChannelStore.devFlag("blockStyle")
                val url = request?.url ?: return null
                if (url.scheme?.lowercase() != "http" && url.scheme?.lowercase() != "https") {
                    return null
                }

                val host = url.host?.lowercase()
                if (blockStyle && host != null && TRACKING_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    TvLog.d(TAG, "block tracking: $url")
                    return emptyResponse("script")
                }

                // mp4 广告视频、ico 图标：页面不需要加载，直接回空响应拦掉
                val ext = url.path?.lowercase() ?: ""
                if (blockStyle && (ext.endsWith(".mp4") || ext.endsWith(".ico"))) {
                    TvLog.d(TAG, "block asset: $url")
                    return emptyResponse(if (ext.endsWith(".ico")) "image" else "video")
                }

                val dest = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                    ?.value?.lowercase()

                // 关掉拦截时一律当成“不是样式类资源”：继续往下走脚本缓存那条路
                val kind = if (!blockStyle) null else when {
                    // style（CSS）不在拦截之列：页面没有样式表会变成无样式排版，
                    // 省下那几十 KB 换来的是隐藏层全显形、懒加载无限追加
                    dest == "image" || dest == "font" ||
                        dest == "manifest" -> dest
                    // 网站声明了资源类型且不在此列，放行
                    dest != null -> null
                    // 老 WebView 没有 Sec-Fetch-Dest 时按扩展名兜底
                    else -> kindByExtension(url.path?.lowercase())
                }
                if (kind == null) {
                    // 放行的脚本：按域名分组查本地缓存，未命中当场下载一份存起来
                    val path = url.path?.lowercase()
                    val staticAsset = dest == "script" || dest == "worker" ||
                        (dest == null && (path?.endsWith(".js") == true || path?.endsWith(".mjs") == true))
                    val plainFile = url.query == null &&
                        (path?.endsWith(".js") == true || path?.endsWith(".mjs") == true)
                    if (staticAsset && plainFile && ChannelStore.devFlag("scriptCache") &&
                        request.method == "GET"
                    ) {
                        val origin = request.requestHeaders.entries
                            .firstOrNull { it.key.equals("Origin", ignoreCase = true) }?.value
                        cacheLookupOrFetch(url.toString(), origin)?.let { return it }
                    }
                    return null
                }

                return emptyResponse(kind)
            }

            // 页面一开始就注入全屏脚本，不等整页加载完
            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?
            ) {
                super.onPageStarted(view, url, favicon)
                // about:blank 是退出时的清空导航：不动遮罩/输入屏蔽状态
                if (url != null && url.startsWith("about:")) return
                cssFullscreen = false
                webView.removeCallbacks(fsWatch)
                val isLivePage = url != null && url.startsWith("http")
                docUrl = if (isLivePage) url else null
                pageInputBlocked = isLivePage
                // 站点脚本：换页面重新匹配；这个站没脚本就把脚本通道全关掉
                scriptCode = if (isLivePage) SiteScripts.forUrl(url)?.code else null
                scriptUrl = url
                scriptRanAt = 0L
                scriptRepeatMs = 0
                webView.removeCallbacks(scriptTick)
                if (isLivePage) {
                    // 新文档没有上一个文档的 window.__tvSoundOn，闸门自己就关上了；
                    // 原生这边也同步成静音，等这一页真出画面再开声（站内跳转也一样）
                    soundOn = false
                    webView.removeCallbacks(soundWatch)
                    // 页面自己的播放器可能比全屏轮询先起播：文档一建就先把闸门挂上
                    webView.evaluateJavascript(EARLY_MUTE_JS, null)
                    // 已经进入过就静默重铺：站内跳转 / 重定向 / 返回上一页都会走到这里，
                    // 新文档不会继承上一个文档的 window.__tvFs，不重新起轮询就再也没人
                    // 铺满屏幕了（画面裸着页面、只剩页面自己的布局）——切台快了最容易中。
                    if (!liveEntered) showTransitionCover() else restartVideoWatch()
                } else {
                    // 回到本地首页 / 错误页：不再需要过渡动画，也不屏蔽操作
                    hideTransitionCover()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.startsWith("about:")) return
                // 兜底：视频全屏还没触发时，过渡动画一直盖着（已进入过就不再盖）
                if (customView == null && !liveEntered && url != null && url.startsWith("http")) {
                    showTransitionCover()
                }
                // 站点脚本：加载完才注入，此时页面的 DOM 才建好
                injectSiteScript()
                // 诊断：报告页面里 video 元素状态与全屏调用结果
                view?.evaluateJavascript(PROBE_JS) { result ->
                    TvLog.d(TAG, "probe: $result")
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                val isMainHttpFrame = request?.isForMainFrame == true &&
                    request.url.scheme?.startsWith("http") == true
                if (isMainHttpFrame) {
                    view?.loadUrl(ERROR_URL)
                }
            }

            // 渲染进程崩溃时不要直接杀应用：销毁 WebView 后退出，系统会重启
            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?
            ): Boolean {
                runCatching { webView.destroy() }
                finish()
                return true
            }
        }
    }

    /**
     * 呼出面板的按键要抢在 WebView 之前处理：直播页里的 video 控件会吞掉 OK/方向键，
     * 事件根本走不到 onKeyDown，遥控器就成了哑巴。
     * 抬起的那一下也要吃掉：面板刚展开，光标已经落在频道行上，
     * 让 UP 继续走下去等于刚呼出面板就替用户选了个台。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) openKeyUp = false
        if (!panelOpen && !pairingOpen &&
            event.action == KeyEvent.ACTION_DOWN &&
            event.keyCode in PANEL_KEYS && ChannelStore.channels().isNotEmpty()
        ) {
            showPanel()
            openKeyUp = true
            return true
        }
        if (openKeyUp && event.action == KeyEvent.ACTION_UP) {
            openKeyUp = false
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 配对页盖在最上层：返回先收它，别一路退到退出应用
            if (pairingOpen) {
                hidePairing()
                return true
            }
            if (panelOpen) {
                hidePanel()
                return true
            }
            // 网页视频全屏：返回 = 退出全屏
            if (customView != null) {
                hideCustomView()
                return true
            }
            finish()
            return true
        }
        // 直播网页里屏蔽一切页面操作（方向键/OK/Tab），只看视频。
        // 面板是自己家的界面，不在屏蔽之列，否则光标在频道列表里挪不动
        if (pageInputBlocked && !panelOpen && keyCode in BLOCKED_PAGE_KEYS) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onResume() {
        super.onResume()
        // 退后台再回来，系统栏常常又被系统放出来了：画面会被顶进安全区，看着就是“歪了一截”。
        // 全屏容器也要照新的可视区立刻重铺，等下一轮轮询的那一秒里画面还是旧的尺寸。
        hideSystemUi()
        if (cssFullscreen) webView.post {
            injectViewportSize()
            webView.evaluateJavascript(applyFsJs(), null)
        }
        // 前台才接配置服务的界面动作；退到后台让给登录页或干脆无人应答
        TvApp.instance.host = this
        // 换过网络后 IP 会变，回到前台就把配对页上的地址和二维码刷成当前值
        if (pairingOpen) pairing?.refresh(configUrl(), ChannelStore.pin)
        reloadAfterLogin()
    }

    /**
     * 重播挂起的频道。得等窗口真拿到焦点再起播：onResume 那一刻登录页的窗口常常还没撤干净，
     * 页面在这半秒里加载，站点自己的播放器常停在“有画面但没播”的状态。
     */
    private fun reloadAfterLogin() {
        if (!needReloadAfterLogin) return
        if (!hasWindowFocus()) return
        needReloadAfterLogin = false
        playingChannel()?.let { openChannel(it.url) }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reloadAfterLogin()
    }

    override fun onPause() {
        super.onPause()
        if (TvApp.instance.host === this) TvApp.instance.host = null
    }

    /**
     * 直播画面里屏蔽网页触摸；手机/电视点一下屏幕即可呼出遮罩
     * （遮罩打开时触摸全部交给原生面板，点面板本体以外的空白处关闭）
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        if (panelOpen) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    tapX = ev.x
                    tapY = ev.y
                    tapDownAt = SystemClock.uptimeMillis()
                }
                MotionEvent.ACTION_UP ->
                    if (isTap(ev.x, ev.y) && !inPanelCard(ev.x, ev.y)) {
                        hidePanel()
                        return true
                    }
            }
            return super.dispatchTouchEvent(ev)
        }
        // 配对页上的控件要能点，触摸照旧下发
        if (pairingOpen) return super.dispatchTouchEvent(ev)
        // 错误页/首页不是 http 页面，但同样只用于点屏呼出面板（页面本身没有可点元素）
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapX = ev.x
                tapY = ev.y
                tapDownAt = SystemClock.uptimeMillis()
            }
            MotionEvent.ACTION_UP -> if (isTap(ev.x, ev.y)) showPanel()
        }
        return true
    }

    private fun isTap(x: Float, y: Float): Boolean =
        kotlin.math.hypot((x - tapX).toDouble(), (y - tapY).toDouble()) < 40 &&
            SystemClock.uptimeMillis() - tapDownAt < 400

    /** 两列卡片和抽屉都属于面板本体，只有点到四周压暗的空白处才收起 */
    private fun inPanelCard(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        panelBody.getLocationInWindow(loc)
        return x >= loc[0] && x <= loc[0] + panelBody.width &&
            y >= loc[1] && y <= loc[1] + panelBody.height
    }

    /** 退出网页视频全屏：先渐进盖上过渡黑屏，切回网页后再渐进退出 */
    private fun hideCustomView() {
        val cv = customView ?: return
        customView = null
        val cb = customViewCallback
        customViewCallback = null

        val cover = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
        }
        addContentView(
            cover,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        cover.animate().alpha(1f).setDuration(180).withEndAction {
            (cv.parent as? ViewGroup)?.removeView(cv)
            cb?.onCustomViewHidden()
            // 直播页可能已经整体退回首页：别再把 WebView 显示出来
            webView.visibility = if (webOpen) View.VISIBLE else View.GONE
            hideSystemUi()
            cover.animate().alpha(0f).setDuration(260)
                .withEndAction { (cover.parent as? ViewGroup)?.removeView(cover) }
                .start()
        }.start()
    }

    /** 打开外部页面前的过渡：黑屏盖住网页，直到视频首帧才渐退（层级在面板之下） */
    private fun showTransitionCover() {
        // 黑幕可能还压着上一个起不来的页面：这里不能直接 return，
        // 必须重新起轮询（超时已排过就不重排，计时始终从点台那一下算），
        // 否则新页面能播也永远等不到首帧探测，黑幕一直盖着
        val cover = transitionCover ?: FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            alpha = 0f
        }.also {
            // 插到频道面板之下：加载期间面板仍可呼出操作，网页画面被黑幕挡住
            val root = findViewById<FrameLayout>(R.id.root)
            root.addView(it, root.indexOfChild(panelRoot),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
            // 一进来就是全黑遮罩（不露出正在加载的网页），只在退出时渐隐
            it.alpha = 1f
            transitionCover = it
        }
        cover.animate().cancel()
        cover.alpha = 1f
        settlingSince = 0L
        // 开始盯页面里的 blob 视频（先清掉可能在旧实例上排队的同一轮询，避免叠成多份）
        restartVideoWatch()
        armPlayTimeout()
    }

    /** 开始/重开铺满轮询：先清掉可能在队列里的同一轮，避免叠成多份 */
    private fun restartVideoWatch() {
        webView.removeCallbacks(videoWatch)
        webView.removeCallbacks(frameWatch)
        webView.removeCallbacks(soundWatch)
        webView.postDelayed(videoWatch, 400)
    }

    /**
     * 开关页面里的声音闸门（页面那边只看 window.__tvSoundOn）。
     * 关着的时候页面上任何 video 都被按成静音，所以起播/切台过渡期间不会有声音；
     * 开的时候会把当前那个全屏 video 的静音属性拿掉、音量压到 50%。
     */
    private fun setSoundOn(on: Boolean) {
        soundOn = on
        if (!::webView.isInitialized) return
        TvLog.d(TAG, if (on) "sound on" else "sound muted")
        webView.evaluateJavascript(
            "window.__tvSound&&window.__tvSound(" + (if (on) "1" else "0") + ")", null
        )
    }

    // 没有黑幕可等的静默重铺（站内跳转 / 返回）：靠这里等目标源真的出画面再开声。
    // 配了脚本的页面还要等脚本跑过一轮，否则开声的可能是脚本正要切掉的那个台。
    private val soundWatch: Runnable = object : Runnable {
        override fun run() {
            if (!webOpen || soundOn) return
            if (scriptHoldCover ||
                (scriptCode != null &&
                    (scriptRanAt == 0L || SystemClock.uptimeMillis() - scriptRanAt < SCRIPT_GRACE_MS))
            ) {
                webView.postDelayed(this, 200)
                return
            }
            val gen = webGen
            webView.evaluateJavascript(FRAME_READY_JS) { result ->
                if (gen != webGen || soundOn) return@evaluateJavascript
                if (result?.trim()?.trim('"') == "1") setSoundOn(true)
                else webView.postDelayed(this, 200)
            }
        }
    }

    // ---------- 站点脚本 ----------

    /**
     * 页面里 TV.* 的出口。这个方法跑在页面的 JS 线程上，界面动作一律回主线程；
     * 当前页面没匹配到脚本时直接丢弃，别的站拿不到任何能力。
     */
    private inner class TvBridge {
        @JavascriptInterface
        fun send(json: String) {
            webView.post { onScriptMessage(json) }
        }
    }

    private fun onScriptMessage(json: String) {
        if (scriptCode == null || !webOpen) return
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        when (obj.optString("t")) {
            "log" -> TvLog.d(TAG, "[tvscript] " + obj.optString("m"))
            "toast" -> toast(obj.optString("m"))
            "repeat" -> armScriptRepeat(obj.optInt("ms", 0))
            "holdCover" -> holdCoverForScript()
            "afterSwitch" -> {
                // 脚本说切完了：放开黑幕，交给正常的首帧揭示流程（新流有帧才渐退）
                scriptHoldCover = false
                reassertFullscreen()
            }
        }
    }

    /**
     * 注入：先铺 TV 这层接口，再跑脚本正文。
     * 脚本异常一律吞掉只记日志 —— 用户写的代码挂了不能影响播放。
     */
    private fun injectSiteScript() {
        val code = scriptCode ?: return
        if (!webOpen) return
        scriptRanAt = SystemClock.uptimeMillis()
        webView.evaluateJavascript(
            TV_BOOT_JS + "\n(function(){try{\n" + code +
                "\n}catch(e){window.TV.log('脚本异常: ' + (e && e.stack ? e.stack : e));}})();",
            null
        )
    }

    /** 脚本要求按自己的节奏重复注入（SPA 换路由、播放器异步渲染） */
    private fun armScriptRepeat(ms: Int) {
        webView.removeCallbacks(scriptTick)
        scriptRepeatMs = if (ms in 200..60_000) ms else 0
        if (scriptRepeatMs > 0) webView.postDelayed(scriptTick, scriptRepeatMs.toLong())
    }

    private val scriptTick = object : Runnable {
        override fun run() {
            if (scriptCode == null || scriptRepeatMs <= 0) return
            injectSiteScript()
            webView.postDelayed(this, scriptRepeatMs.toLong())
        }
    }

    /**
     * 脚本换完流后重新接管：清掉旧轮询重开一轮，新播放器/新 video 会在这轮里被重新铺满，
     * 尺寸也重给一次 —— 有的站换台后页面缩放变了。
     */
    private fun reassertFullscreen() {
        if (!webOpen || customView != null) return
        injectViewportSize()
        webView.removeCallbacks(fsWatch)
        restartVideoWatch()
    }

    /**
     * 脚本要开始页内切台：把黑幕重新盖上并压住，直到 TV.afterSwitch。
     * 已经露着画面的情况也要盖（切台中间新旧画面交替很难看），所以这里会
     * 把 liveEntered 复位一次，让后面的揭示流程重新走一遍。
     */
    private fun holdCoverForScript() {
        if (!webOpen) return
        scriptHoldCover = true
        if (transitionCover == null) {
            liveEntered = false
            playTimeoutArmed = false
            showTransitionCover()
        }
        TvLog.d(TAG, "[tvscript] hold cover")
    }

    /** 退出 CSS 全屏（把视频放回页面原位置） */
    private fun exitCssFullscreen() {
        cssFullscreen = false
        webView.removeCallbacks(fsWatch)
        webView.evaluateJavascript(EXIT_FS_JS, null)
    }

    /** 过渡退出：黑屏慢慢褪去，露出视频 */
    private fun hideTransitionCover() {
        val cover = transitionCover ?: return
        transitionCover = null
        TvLog.d(TAG, "cover fade out")
        webView.removeCallbacks(videoWatch)
        webView.removeCallbacks(frameWatch)
        disarmPlayTimeout()
        webView.removeCallbacks(fsWatch)
        webView.postDelayed(fsWatch, 1000)
        cover.animate().cancel()
        cover.animate().alpha(0f).setDuration(700)
            .withEndAction { (cover.parent as? ViewGroup)?.removeView(cover) }
            .start()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        EpgManager.onChanged = null
        cacheExecutor.shutdownNow()
        if (::webView.isInitialized) {
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun emptyResponse(kind: String): WebResourceResponse {
        val mime = when (kind) {
            "style" -> "text/css"
            "font" -> "font/woff2"
            "script" -> "text/javascript"
            "map", "manifest" -> "application/json"
            "video" -> "video/mp4"
            else -> "image/png"
        }
        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun kindByExtension(path: String?): String? {
        if (path == null) return null
        return when {
            path.endsWith(".map") -> "map"
            path.endsWith(".webmanifest") || path.endsWith(".manifest") -> "manifest"
            FONT_EXTS.any { path.endsWith(it) } -> "font"
            IMAGE_EXTS.any { path.endsWith(it) } -> "image"
            else -> null
        }
    }

    // ---------- 静态脚本磁盘缓存（同站多频道复用，二次进台免下载 JS） ----------

    // 目录名带版本：v1 只存过没解压的原始字节（见 plainBody），那些条目一律作废，
    // 留着还会被当成有效缓存回给内核
    private fun cacheRoot(): File = File(cacheDir, CACHE_DIR).apply {
        mkdirs()
        synchronized(legacyCacheLock) {
            if (!legacyCacheCleared) {
                legacyCacheCleared = true
                File(cacheDir, "webstatic").takeIf { it.isDirectory }?.deleteRecursively()
            }
        }
    }

    private val legacyCacheLock = Any()

    @Volatile
    private var legacyCacheCleared = false

    /**
     * 把响应体还原成明文。有些 CDN 无视 Accept-Encoding: identity
     * 照样回 gzip：把压缩字节当 JS 交给内核，内核按文本解析二进制，直接
     * Uncaught SyntaxError: Invalid or unexpected token，整段脚本作废（取流函数就这么丢的）。
     * 返回 null 表示这个编码解不了，交回内核自己走网络。
     */
    private fun plainBody(bytes: ByteArray, encoding: String): ByteArray? {
        val enc = encoding.trim().lowercase()
        // 头里没写但确实是 gzip 的，按魔数认
        val gzip = enc.contains("gzip") ||
            (bytes.size > 2 && bytes[0] == 0x1f.toByte() && bytes[1] == 0x8b.toByte())
        return when {
            gzip -> runCatching {
                GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull()
            enc.isEmpty() || enc == "identity" -> bytes
            enc.contains("deflate") -> runCatching {
                InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            }.getOrNull()
            else -> null
        }
    }

    /** 域名分组目录：cache/webstatic/<host>/<路径哈希>.js|.meta */
    private fun cacheEntry(urlStr: String): Pair<File, String>? {
        val host = URL(urlStr).host?.lowercase() ?: return null
        val key = MessageDigest.getInstance("MD5")
            .digest(urlStr.toByteArray()).joinToString("") { "%02x".format(it) }
        return Pair(File(cacheRoot(), host), key)
    }

    /** 命中直接返回；未命中当场下载（就是页面本该收到的那份）并落盘 */
    private fun cacheLookupOrFetch(urlStr: String, origin: String?): WebResourceResponse? {
        if (!cacheableScriptUrl(urlStr)) return null
        val (dir, key) = cacheEntry(urlStr) ?: return null
        val meta = File(dir, "$key.meta")
        val body = File(dir, "$key.js")
        if (meta.isFile && body.isFile) {
            val lines = runCatching { meta.readLines() }.getOrNull()
                ?: return cacheFetch(urlStr, dir, key, origin)
            val exp = lines.getOrNull(1)?.toLongOrNull() ?: 0L
            if (exp > System.currentTimeMillis()) {
                val headers = lines.drop(3).mapNotNull {
                    val i = it.indexOf(": ")
                    if (i > 0) it.substring(0, i) to it.substring(i + 2) else null
                }.toMap().toMutableMap()
                val mime = headers["Content-Type"]?.substringBefore(';')?.takeIf { it.isNotEmpty() }
                    ?: lines.getOrNull(0)?.ifEmpty { null }
                    ?: "application/javascript"
                val resp = runCatching {
                    WebResourceResponse(mime, null, body.inputStream())
                        .apply { setStatusCodeAndReasonPhrase(200, "OK") }
                }.getOrNull() ?: return null
                if (origin != null && headers.keys.none { it.equals("Access-Control-Allow-Origin", true) })
                    headers["Access-Control-Allow-Origin"] = origin
                resp.setResponseHeaders(headers)
                touchAccess(meta)
                // 每次页面加载都有几百条，只进 logcat，不占配置页的日志区
                Log.d(TAG, "cache hit: $urlStr")
                return resp
            }
        }
        return cacheFetch(urlStr, dir, key, origin)
    }

    private fun cacheFetch(urlStr: String, dir: File, key: String, origin: String?): WebResourceResponse? {
        try {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 5000
                readTimeout = 10000
                setRequestProperty("User-Agent", TV_USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                docUrl?.let { setRequestProperty("Referer", it) }
                origin?.let { setRequestProperty("Origin", it) }
                CookieManager.getInstance().getCookie(urlStr)
                    ?.let { setRequestProperty("Cookie", it) }
            }
            if (conn.responseCode != 200) return null
            val type = conn.contentType?.substringBefore(';').orEmpty()
            if (!type.contains("javascript")) return null
            val cc = conn.getHeaderField("Cache-Control").orEmpty().lowercase()
            val maxAge = Regex("max-age\\s*=\\s*(\\d+)").find(cc)?.groupValues?.get(1)
                ?.toLongOrNull() ?: 0L
            val now = System.currentTimeMillis()
            // 无 max-age 时，带内容哈希/版本号的 JS 文件名视为不可变资源，给默认 TTL
            val ttl = when {
                cc.contains("no-store") || cc.contains("private") -> 0L
                maxAge > 0 -> maxAge
                VERSIONED_URL.containsMatchIn(urlStr) -> CACHE_DEFAULT_TTL
                else -> 3600L
            }
            // no-store/private：不存也不代答，交回内核自己走网络
            if (ttl <= 0L) return null
            val raw = conn.inputStream.use { it.readBytes() }
            if (raw.isEmpty() || raw.size > CACHE_MAX_OBJECT) return null
            val enc = conn.getHeaderField("Content-Encoding").orEmpty()
            val bytes = plainBody(raw, enc) ?: return null
            if (bytes.isEmpty() || bytes.size > CACHE_MAX_OBJECT) return null
            // 原样收下服务端的关键响应头：跨域脚本缺 CORS 头会被内核判成加载失败
            val headers = LinkedHashMap<String, String>()
            for (h in CORS_HEADERS) {
                conn.getHeaderField(h)?.let { headers[h] = it }
            }
            headers["Content-Type"] = type
            dir.mkdirs()
            val body = File(dir, "$key.js")
            body.writeBytes(bytes)
            File(dir, "$key.meta").writeText(
                buildString {
                    append(type).append('\n')
                    append(now + ttl * 1000).append('\n')
                    append(now).append('\n')
                    for ((k, v) in headers) append(k).append(": ").append(v).append('\n')
                }
            )
            cacheTrim()
            Log.d(TAG, "cache store: $urlStr ttl=$ttl size=${bytes.size}" +
                if (enc.isNotEmpty() && !enc.equals("identity", true)) " enc=$enc" else "")
            val out = LinkedHashMap(headers)
            if (origin != null && out.keys.none { it.equals("Access-Control-Allow-Origin", true) })
                out["Access-Control-Allow-Origin"] = origin
            return WebResourceResponse(type, null, ByteArrayInputStream(bytes))
                .apply {
                    setStatusCodeAndReasonPhrase(200, "OK")
                    setResponseHeaders(out)
                }
        } catch (_: Exception) {
            return null
        }
    }

    /** 刷新 meta 里的访问时间（供 LRU 排序），异步写不挡加载 */
    private fun touchAccess(meta: File) {
        cacheExecutor.execute {
            runCatching {
                val l = meta.readLines()
                if (l.size >= 3) {
                    val rest = if (l.size > 3) l.subList(3, l.size) else emptyList()
                    meta.writeText(
                        (l.take(2) + System.currentTimeMillis().toString() + rest)
                            .joinToString("\n") + "\n"
                    )
                }
            }
        }
    }

    /** 只缓存看起来是脚本的地址 */
    private fun cacheableScriptUrl(urlStr: String): Boolean {
        val p = URL(urlStr).path?.lowercase() ?: return false
        return p.endsWith(".js") || p.endsWith(".mjs")
    }

    /** 先清各域名目录里的过期/孤儿条目，仍超总预算再按最久未访问淘汰 */
    private fun cacheTrim() {
        val root = cacheRoot()
        val now = System.currentTimeMillis()
        val items = ArrayList<Triple<Long, Long, File>>()
        var total = 0L
        for (hostDir in root.listFiles()?.filter { it.isDirectory }.orEmpty()) {
            for (m in hostDir.listFiles { f: File -> f.name.endsWith(".meta") } ?: emptyArray()) {
                val b = File(hostDir, m.name.removeSuffix(".meta") + ".js")
                val exp = runCatching { m.readLines().getOrNull(1)?.toLongOrNull() }.getOrNull()
                if (!b.isFile || exp == null || exp < now) { m.delete(); b.delete(); continue }
                total += b.length()
                items.add(Triple(m.lastModified(), b.length(), m))
            }
            hostDir.listFiles { f: File -> !f.name.endsWith(".meta") && !f.name.endsWith(".js") }
                ?.forEach { it.delete() }
        }
        if (total <= CACHE_BUDGET) return
        for ((_, size, m) in items.sortedBy { it.first }) {
            File(m.parentFile, m.name.removeSuffix(".meta") + ".js").delete()
            m.delete()
            total -= size
            if (total <= CACHE_BUDGET) break
        }
    }

    companion object {
        const val TAG = "TvWeb"
        const val ERROR_URL = "file:///android_asset/error.html"

        // 起播超时：点台后这么久还没等到视频首帧，就关掉网页显示错误页
        const val PLAY_TIMEOUT_MS = 15_000L
        // 首帧到位后黑幕最多再压这么久等容器停稳：页面缩放一般一两拍内就收敛了
        const val FRAME_SETTLE_MS = 1_200L
        const val PLAY_ERROR_TEXT = "该频道暂时无法播放"

        // 焦点记忆存储名
        const val PREFS = "panel_focus"

        // 桌面浏览器 UA：网站才会下发电脑版网页布局而不是 app/手机版
        const val TV_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/156.0.0.0 Safari/537.36"

        // 拦截样式资源 / 脚本缓存的开关在配置页「开发者调试」里（ChannelStore 存，
        // 默认值那两个开关都是开的），不在代码里写死

        // 统计/广告/埋点域名黑名单：整域（含子域）返回空响应，播放器不感知
        val TRACKING_HOSTS = arrayOf(
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "googleadservices.com", "doubleclick.net", "adnxs.com",
            "hm.baidu.com", "cnzz.com", "umeng.com", "umeng.co",
            "mmstat.com", "51.la", "growingio.com", "sensorsdata.cn",
            "talkingdata.com", "bugly.qq.com"
        )

        // 静态脚本磁盘缓存（按域名分组）：单文件上限 6MB、总预算 80MB
        // 目录名带版本：v1 存的是没解压的原始字节，换版本名顺带把脏缓存整个作废
        const val CACHE_DIR = "webstatic_v2"
        const val CACHE_MAX_OBJECT = 6L * 1024 * 1024
        const val CACHE_BUDGET = 80L * 1024 * 1024
        // 无 Cache-Control 但文件名带内容哈希/版本号的 JS：按不可变资源缓存 7 天
        const val CACHE_DEFAULT_TTL = 7 * 24 * 3600L
        val VERSIONED_URL = Regex("\\.[0-9a-f]{8,}\\.js|ver=[0-9a-z]+")

        // 回灌给内核的服务端响应头：缺了 CORS 头，crossorigin 脚本会被判加载失败
        val CORS_HEADERS = listOf(
            "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
            "Access-Control-Allow-Headers", "Access-Control-Expose-Headers",
            "Cache-Control", "ETag", "Last-Modified", "Vary"
        )

        // 几何调试（全屏容器画青色边框、video 画粉色内描边 + 每秒一行 [tvgeom] 日志）：
        // 开关在配置页「开发者调试」里，默认关（正式包画面干净）。
        // 标记由 applyFsJs() 下发到页面的 window.__tvDbgFrame，所以切台后生效。

        // 列出页面所有 video 的 src 与状态
        // maxCols 由 overlayCols() 现算传入（见上），不在这里写死
        private fun debugInfoJs(maxCols: Int): String = """
            (function () {
              var out = [];
              // 地址给完整的：截断了就分不清是取流失败还是浮层没显全
              out.push('url=' + location.href);
              // 标题和正文不打了：只用来认页面，占地方；body.innerText 还会每轮
              // 强制一次布局（700ms 一次），去掉也省事
              // shadow 里的 video 也算进来（开放影子才行）：每个 host 的影子树里再搜一次 video
              // 影子根里的 video：由 attachShadow 拦截 + MutationObserver 收集在
              // window.__tvShadowVids 里，这里直接读，不再钻树
              var vShadow = window.__tvShadowVids || [];
              var vs = document.querySelectorAll('video');
              var vIdx = 0;
              // 只列会被选片逻辑考虑的那些：mp4 广告、以及还没拿到流也没出画面的占位元素，
              // 一律不列。列出来只会让人以为它被选中了
              for (var i = 0; i < vs.length; i++) {
                if (eligible(vs[i])) out.push(videoEntry(vs[i], vIdx++));
              }
              for (var i2 = 0; i2 < vShadow.length; i2++) {
                var sv = vShadow[i2];
                if (!sv) continue;
                // 已经在上面这批里的就不重复写一遍
                var dup = false;
                for (var j = 0; j < vs.length; j++) { if (vs[j] === sv) { dup = true; break; } }
                if (dup) continue;
                if (eligible(sv)) out.push(videoEntry(sv, vIdx++));
              }
              // 和选片（APPLY_FS_JS 里的 playableSrc + mp4 过滤）同一套门槛：
              // 地址是 mp4 直链 = 广告，不算；没地址也没出帧 = 还没就绪，也不算
              function eligible(v) {
                if (!v) return false;
                var a = v.getAttribute('src') || '';
                var c = v.currentSrc || '';
                var p = v.src || '';
                var mp4 = /\.mp4(\?|#|&|$)/i;
                if (mp4.test(a) || mp4.test(c) || mp4.test(p)) return false;
                var s = a || c || p;
                if (s.indexOf('blob:') === 0) return true;
                if (/\.(m3u8|mpd|flv|webm|ogg|ogv|ts|mp3|m4a|aac|wav)(\?|#|&|$)/i.test(s)) return true;
                // 和 playableSrc 一模一样的最后一道：出过帧才算。
                // 只看 srcObject 会把“接上了流但一帧没出”的空壳也列进来，
                // 而选片那边根本不会选它 —— 列出来只会让人以为它参与了竞争
                return v.videoWidth > 0;
              }
              // duration 只写成一个短标签：直播是 inf / -（NaN 或还没加载），
              // 贴片广告和点播是有限的秒数，对应选片里的 finiteDur() 判据
              function durText(v) {
                try {
                  var d = v.duration;
                  if (typeof d !== 'number' || isNaN(d)) return '-';
                  if (!isFinite(d)) return 'inf';
                  return d.toFixed(1);
                } catch (e) { return '-'; }
              }
              function videoEntry(v, idx) {
                if (!v) return 'v? src=- paused=- size=0x0 t=0.0 rs=- tracks=-';
                var r = v.getBoundingClientRect();
                var attr = v.getAttribute('src') || '-';
                var prop = v.src || '-';
                var cur = v.currentSrc || '-';
                var obj = v.srcObject ? String(v.srcObject) : '-';
                // 标题标出“这个就是被铺满的那个”，否则列一堆分不清哪个在播
                var fs = window.__tvFs;
                var tag = (fs && fs.el === v) ? '  ←全屏中' : '';
                // 被铺满那个的 size 是“我们铺完之后”的尺寸（wrap 的 100%x100%），
                // 不是它原本在页面里的大小；选片时拿来比较的是进 wrap 之前那个（preWrap）
                var pre = (fs && fs.el === v && fs.origW)
                  ? ' preWrap=' + Math.round(fs.origW) + 'x' + Math.round(fs.origH)
                  : '';
                var sizeLine = ' size=' + Math.round(r.width) + 'x' + Math.round(r.height) + pre;
                // 地址全给：鉴权参数往往在地址尾部，截了就看不出是哪个源
                return 'v' + idx + tag +
                  '\n  attr=' + attr +
                  '\n  src=' + prop +
                  '\n  cur=' + cur +
                  '\n  obj=' + obj +
                  '\n  paused=' + v.paused + sizeLine +
                  ' t=' + v.currentTime.toFixed(1) +
                  ' dur=' + durText(v) + (v.muted ? ' muted' : '') +
                  ' vol=' + v.volume.toFixed(2) +
                  ' vw=' + v.videoWidth + 'x' + v.videoHeight + ' rs=' + v.readyState +
                  ' tracks=' + (v.srcObject && v.srcObject.getTracks
                    ? v.srcObject.getTracks().map(function (t) { return t.kind; }).join(',')
                    : '-');
              }
              // 诊断用：视口尺寸 / 全屏容器实际覆盖范围
              out.push('vw=' + window.innerWidth + 'x' + window.innerHeight +
                ' doc=' + document.documentElement.clientWidth + 'x' +
                document.documentElement.clientHeight +
                ' scr=' + screen.width + 'x' + screen.height +
                ' dpr=' + window.devicePixelRatio);
              var vv = window.visualViewport;
              if (vv) {
                out.push('vv=' + Math.round(vv.width) + 'x' + Math.round(vv.height) +
                  ' scale=' + vv.scale + ' off=' + Math.round(vv.offsetLeft) + ',' +
                  Math.round(vv.offsetTop) + ' page=' + Math.round(vv.pageWidth) + 'x' +
                  Math.round(vv.pageHeight));
              } else out.push('vv none');
              out.push('scroll=' + document.documentElement.scrollWidth + 'x' +
                document.documentElement.scrollHeight);
              var bb = document.body ? document.body.getBoundingClientRect() : null;
              out.push('zoom html=' + getComputedStyle(document.documentElement).zoom +
                ' body=' + (document.body ? getComputedStyle(document.body).zoom : '-') +
                ' bodyRect=' + (bb ? Math.round(bb.width) + 'x' + Math.round(bb.height) +
                  ' @' + Math.round(bb.left) + ',' + Math.round(bb.top) : '-'));
              // iframe 不单独列：选片只看主文档，iframe 里的 video 不参与比较，
              // 而 iframe 条目多、src 又长，列出来只会把浮层刷满
              var s = window.__tvFs;
              if (s && s.wrap) {
                var rw = s.wrap.getBoundingClientRect();
                out.push('wrap ' + Math.round(rw.width) + 'x' + Math.round(rw.height) +
                  ' @' + Math.round(rw.left) + ',' + Math.round(rw.top) +
                  ' inDoc=' + (s.wrap.ownerDocument === document) +
                  ' elDoc=' + (s.el ? (s.el.ownerDocument === document) : '?'));
              } else {
                out.push('wrap none');
              }
              // 内核不会给没有空格的长串（URL）折行，插零宽空格也不断，超出一行就直接裁掉。
              // 所以自己按列数断行；列数由原生按浮层字体度量现算（overlayCols），
              // 宽度够就不用折，只有真放不下才断。
              var MAX_COLS = $maxCols;
              var raw = out.length ? out.join('\n').split('\n') : ['(no video)'];
              var lines = [];
              for (var li = 0; li < raw.length; li++) {
                var ln = raw[li];
                while (ln.length > MAX_COLS) {
                  var at = MAX_COLS;
                  // 别把代理对（emoji 之类）从中间切开
                  var code = ln.charCodeAt(at - 1);
                  if (code >= 0xD800 && code <= 0xDBFF) at--;
                  lines.push(ln.slice(0, at));
                  ln = '  ' + ln.slice(at);
                }
                lines.push(ln);
              }
              // 浮层高度有限（一屏也就 30 行）：超了就截住并写明，别让人以为是全量
              var MAX_LINES = 30;
              if (lines.length > MAX_LINES) {
                return lines.slice(0, MAX_LINES).join('\n') +
                  '\n…（还有 ' + (lines.length - MAX_LINES) + ' 行未显示）';
              }
              return lines.join('\n');
            })();
        """

        // 页面一启动就监听 DOM：新出现的 video[src^="blob:"] 立刻全屏（只看主文档，不进 iframe）。
        // keydown/click 处理器里调用才带“用户手势”，部分 WebView 全屏必须要有手势。
        // 把 blob 视频铺满视口（CSS 全屏，不依赖全屏 API / 用户手势）。
        // 页面上可能有多个 video（广告/正片）：选面积最大且带可播放 src 的那个
        // （blob: 或 m3u8/flv/mp4 等地址），覆盖样式铺满 100% 后全屏；每轮复选并尝试 play()。
        fun applyFsJs(): String =
            "window.__tvDbgFrame=" +
                (if (ChannelStore.devFlag("frameMarkers")) "1" else "0") + ";" + """
            (function () {
              // 调试边界标记开关（由原生每次注入时下发，页面重新加载也会重给一遍）
              var DBG = window.__tvDbgFrame === 1;
              // 页面自己的 View Transition：切换时会把整页快照画进 top layer 演一遍，
              // 那一层连 popover 都盖得住 —— 表现就是铺满之后被页面/播放器外框闪一下。
              // 换成直接执行回调的版本：页面走的还是同一套 DOM 更新，只是不再生成快照。
              (function () {
                try {
                  if (window.__tvNoVT) return;
                  window.__tvNoVT = 1;
                  if (typeof document.startViewTransition !== 'function') return;
                  var shim = function (cb) {
                    var p = Promise.resolve().then(function () {
                      if (typeof cb === 'function') return cb();
                    });
                    return {
                      ready: p, finished: p, updateCallbackDone: p,
                      skipTransition: function () {},
                      types: { add: function () {}, has: function () { return false; },
                        forEach: function () {} }
                    };
                  };
                  try { Object.defineProperty(shim, 'name', { value: 'startViewTransition' }); } catch (e) {}
                  document.startViewTransition = shim;
                } catch (e) {}
              })();
              // 这个 video 是不是真的在放画面：
              // 1) src 是 blob: 或媒体后缀（MSE / 直连流）
              // 2) 或者元素已经解出帧（videoWidth>0）——播放器把 MediaStream 挂到
              //    srcObject 上时三个 src 全是空，光看 src 会把它当成不存在
              function playableSrc(v) {
                var s = v.getAttribute('src') || v.currentSrc || v.src || '';
                if (s.indexOf('blob:') === 0) return s;
                if (/\.(m3u8|mpd|flv|mp4|webm|ogg|ogv|ts|mp3|m4a|aac|wav)(\?|#|&|$)/i.test(s)) return s;
                if (v.videoWidth > 0) return 'frame';
                return null;
              }
              // 广告基本都是 mp4 直链（直播正片通常是 MSE 或 m3u8/flv，不走 mp4 文件）。
              // 所以地址里带 .mp4 的一律不选，避免把广告铺满全屏。
              function isMp4Url(s) {
                return /\.mp4(\?|#|&|$)/i.test(s || '');
              }
              // 贴片广告是固定时长的一段媒体（15s/30s 那种），duration 一加载就是有限值；
              // 直播是持续推流，duration 要么 Infinity，要么刚加载时 NaN / 0。
              // 所以「有限且 > 0」只可能是点播式媒体，不是直播。
              // 返回 false 一律表示「不像广告」（无限/未知也算）：flv、自研播放器报 NaN 很常见，
              // 把不确定的当成广告会把正常直播排除掉，宁可不动它。
              function finiteDur(v) {
                try {
                  var d = v.duration;
                  return typeof d === 'number' && isFinite(d) && d > 0;
                } catch (e) { return false; }
              }
              // 声音分两段：
              //   起播阶段一律静音（声音闸门关着）—— 一个页面上有多个频道时，页面自己
              //     先播的那个台、和脚本切过去的目标台之间有一段过渡，不静音就会先听到
              //     第一个台的声音再一跳；而且静音起播也不会被自动播放策略拦下。
              //   原生在画面真正落定、黑幕要揭开时调 window.__tvSound(1) 开声。
              // 开声之后：有些播放器默认静音起播（autoplay 策略 / 自己的默认值），
              // 画面有了却没声，这里把声音压到 50% 并持续纠正。
              var TV_VOLUME = 0.5;
              function applyVolume(el) {
                if (!el) return;
                try {
                  // 规则只有一条：铺满的那一路要有声、音量 50%；其它一律静音。
                  // 不再看「声音闸门」——那个门只在还没选片时用来压住页面先起播的东西，
                  // 一旦已经选定这一路，它就是用户要听的东西，闸门开没开都不应该把它静音
                  // （早先按闸门判断，闸门没来得及打开时全屏画面就一直静音、音量也没改）。
                  var cur = window.__tvFs && window.__tvFs.el;
                  if (!cur) {
                    if (window.__tvSoundOn !== 1) {
                      if (!el.muted) el.muted = true;
                      if (el.hasAttribute && !el.hasAttribute('muted')) el.setAttribute('muted', '');
                      return;
                    }
                  } else if (el !== cur) {
                    if (!el.muted) el.muted = true;
                    return;
                  }
                  if (el.muted) el.muted = false;
                  if (el.defaultMuted) el.defaultMuted = false;
                  if (el.hasAttribute && el.hasAttribute('muted')) el.removeAttribute('muted');
                  if (Math.abs(el.volume - TV_VOLUME) > 0.001) el.volume = TV_VOLUME;
                } catch (e) {}
              }
              // 原生开/关声音闸门的唯一入口
              window.__tvSound = function (on) {
                window.__tvSoundOn = on ? 1 : 0;
                var s = window.__tvFs;
                applyVolume(s && s.el);
              };
              // 同一元素只挂一次；设完值不会再触发额外的写入（值已正确就是空转）
              function bindVolume(el) {
                if (!el || el.__tvVolBound) return;
                el.__tvVolBound = 1;
                var fix = function () { applyVolume(el); };
                try {
                  el.addEventListener('volumechange', fix);
                  el.addEventListener('play', fix);
                  el.addEventListener('playing', fix);
                  el.addEventListener('loadeddata', fix);
                  el.addEventListener('canplay', fix);
                } catch (e) {}
              }
              // ---- 影子 DOM 里的 video：attachShadow 拦截 + MutationObserver 收集 ----
              // shadowRoot 里的 video 用 document.querySelectorAll('video') 拿不到。以前是每次选片
              // 递归钻树；改成拦一次 attachShadow：谁建了影子根就拿到它的引用、对根挂观察器，
              // 看着它内部插进来的 video 登记到清单里，选片时直接读清单。
              // 好处是不猜标签/类名，closed 的影子根也收得到（根本身在我们手里）。
              var SHADOW_VIDS = window.__tvShadowVids || (window.__tvShadowVids = []);
              function shadowAdd(el) {
                if (!el || el.tagName !== 'VIDEO') return;
                if (SHADOW_VIDS.indexOf(el) >= 0) return;
                SHADOW_VIDS.push(el);
              }
              function shadowAddTree(root) {
                if (!root || !root.querySelectorAll) return;
                try {
                  var vs = root.querySelectorAll('video');
                  for (var i = 0; i < vs.length; i++) shadowAdd(vs[i]);
                } catch (e) {}
              }
              function shadowWatch(root) {
                if (!root || root.__tvShadowWatched) return;
                root.__tvShadowWatched = 1;
                shadowAddTree(root);
                try {
                  new MutationObserver(function (ms) {
                    for (var i = 0; i < ms.length; i++) {
                      var added = ms[i].addedNodes;
                      for (var j = 0; j < added.length; j++) {
                        var n = added[j];
                        if (!n || n.nodeType !== 1) continue;
                        if (n.tagName === 'VIDEO') shadowAdd(n);
                        else shadowAddTree(n);
                      }
                    }
                  }).observe(root, { childList: true, subtree: true });
                } catch (e) {}
              }
              // 组件的 shadowRoot 刚建出来时它自己还在构造中，同步插 DOM 很常见；
              // 观察器推到本轮之后挂，不去打扰它的初始化
              function shadowWatchLater(root) {
                if (!root) return;
                try {
                  if (typeof Promise === 'function') {
                    Promise.resolve().then(function () { try { shadowWatch(root); } catch (e) {} });
                  } else {
                    setTimeout(function () { try { shadowWatch(root); } catch (e) {} }, 0);
                  }
                } catch (e) {}
              }
              if (!window.__tvShadowHook) {
                window.__tvShadowHook = 1;
                try {
                  var rawAttach = Element.prototype.attachShadow;
                  if (rawAttach && !rawAttach.__tvHooked) {
                    var hookedAttach = function (opts) {
                      var root = rawAttach.call(this, opts);
                      try { shadowWatchLater(root); } catch (e) {}
                      return root;
                    };
                    // 让钩子尽量像原生：名称、形参个数、toString 都对齐，
                    // 页面里查「原型被改过没有」的逻辑看到的是 [native code]
                    try { Object.defineProperty(hookedAttach, 'name', { value: 'attachShadow' }); } catch (e) {}
                    try { Object.defineProperty(hookedAttach, 'length', { value: 1 }); } catch (e) {}
                    try {
                      hookedAttach.toString = function () {
                        return 'function attachShadow() { [native code] }';
                      };
                    } catch (e) {}
                    hookedAttach.__tvHooked = 1;
                    Element.prototype.attachShadow = hookedAttach;
                  }
                } catch (e) {}
              }
              // 钩子装上之前页面已经建好的影子根：开页只补登记一次（能枚举到的 open 根）
              if (!window.__tvShadowSeeded) {
                window.__tvShadowSeeded = 1;
                try {
                  var hosts = document.querySelectorAll('*');
                  for (var h = 0; h < hosts.length; h++) {
                    if (hosts[h].shadowRoot) shadowWatch(hosts[h].shadowRoot);
                  }
                } catch (e) {}
              }

              // 在页面（含 shadowRoot）里选面积最大的可播放 video
              // 影子根里的靠上面的钩子清单补进来；iframe 一律不管：那是另一个坐标系的文档。
              function pickVideo(exclude) {
                var allV = [], best = null, bestArea = -1;
                try {
                  var vs0 = document.querySelectorAll('video');
                  for (var i0 = 0; i0 < vs0.length; i0++) allV.push(vs0[i0]);
                } catch (e) {}
                for (var s0 = 0; s0 < SHADOW_VIDS.length; s0++) {
                  var sv0 = SHADOW_VIDS[s0];
                  if (!sv0 || sv0.isConnected === false) continue;  // 已经不在文档里的丢掉
                  if (allV.indexOf(sv0) >= 0) continue;
                  allV.push(sv0);
                }
                // 先面积最大的可播放 video（视频真的出画面了再全屏，避免拿广告/占位 video）
                // 分两档：duration 无限/未知的（直播）优先，有限时长的（贴片广告/点播）
                // 只在没有直播可选时才用；两路同时存在时，广告再大也不跟直播抢。
                var liveBest = null, liveArea = -1;
                for (var j = 0; j < allV.length; j++) {
                  var v = allV[j];
                  if (v === exclude) continue;  // 已经铺满的那个不参与比较
                  var ps = playableSrc(v);
                  if (!ps) continue;
                  if (isMp4Url(ps)) continue;  // 广告：mp4 直链跳过
                  var r = v.getBoundingClientRect();
                  var area = r.width * r.height;
                  if (area > bestArea) { bestArea = area; best = v; }
                  if (!finiteDur(v) && area > liveArea) { liveArea = area; liveBest = v; }
                }
                if (liveBest) return liveBest;
                if (best) return best;
                // 还没布局（面积 0）：退回第一个可播放的（同样跳过 mp4）
                for (var j2 = 0; j2 < allV.length; j2++) {
                  if (allV[j2] === exclude) continue;
                  var ps2 = playableSrc(allV[j2]);
                  if (ps2 && !isMp4Url(ps2)) return allV[j2];
                }
                return null;
              }
              // 有些播放器第一次 play() 不生效（源还没挂上/内部状态没就绪），得再点一次。
              // 没播起来就隔 100ms 再 play()，最多 5 次；链路已在跑时不重复起。
              function tryPlay(v) {
                if (v.__tvPlayTimer) return;
                var n = 0;
                var step = function () {
                  v.__tvPlayTimer = 0;
                  if (!v.paused) return;
                  if (n++ >= 5) return;
                  applyVolume(v);
                  try {
                    var p = v.play();
                    if (p && p.catch) p.catch(function () {});
                  } catch (e) {}
                  v.__tvPlayTimer = setTimeout(step, 100);
                };
                step();
              }
              // 全屏容器要铺满“看得见的那块”，而页面报的几种尺寸都可能是别的数：
              // 没有 viewport meta 的页面布局视口只有 980 宽（100vw 就按它算，只够半屏）；
              // 按设计稿反向缩放的页面 innerWidth 又比屏幕大一圈（铺满会溢出，画面被顶出
              // 屏幕、文档滚动区跟着变大）。visualViewport 报的才是可视区，两种页面都对得上；
              // 老 WebView 没有它时退回原来的 100vw 行为。
              // 屏幕上真正看得见的那块（CSS 像素）：
              // 原生侧每轮把 WebView 的设备像素尺寸放进 window.__tvDev，除以
              // devicePixelRatio * 页面缩放 就是可视区的 CSS 尺寸。
              // 不能只看页面自己报的数：innerWidth 可能是布局视口（比屏幕宽），
              // 换台那一瞬 visualViewport 也可能还没定型 —— 按错的那个值铺，
              // 容器就比屏幕大一圈，画面被推到屏幕外只剩一部分（切台快了才偶发）。
              function viewportSize() {
                var vv = window.visualViewport;
                var s = (vv && vv.scale > 0) ? vv.scale : 1;
                var k = (window.devicePixelRatio || 1) * s;
                var dev = window.__tvDev;
                // 向上取整：round 会算得比屏幕小不到 1px，那零点几像素是白底透出来的一条线；
                // ceil 最多多铺不到 1px，多出去的部分被屏幕/overflow 裁掉，看不见
                if (dev && dev.w > 0 && dev.h > 0 && k > 0) {
                  return { w: Math.ceil(dev.w / k), h: Math.ceil(dev.h / k) };
                }
                if (vv && vv.width > 0 && vv.height > 0) {
                  return { w: Math.ceil(vv.width), h: Math.ceil(vv.height) };
                }
                return null;
              }
              // 这个内核（本机 WebView）会把 position:fixed 的元素**跟着可视区偏移一起挪**：
              // 页面被滚了一下以后 visualViewport.offsetTop 是 73，写的是 top:0，
              // 实际却画在屏幕上方 73 CSS 处 —— 上边框直接跑出屏幕，下面露出一大块页面
              // 背景，就是“容器整体偏上”。
              // 对位：把可视区偏移加回容器的 left/top，让它重新贴住屏幕。
              // 偏移是运行时变的（页面滚动、播放器 scrollIntoView、聚焦输入框），
              // 所以每轮都重算一次，值没变就不写样式。
              function placeWrap(wrap) {
                var vv = window.visualViewport;
                var x = vv ? Math.round(vv.offsetLeft || 0) : 0;
                var y = vv ? Math.round(vv.offsetTop || 0) : 0;
                if (wrap.__tvOx === x && wrap.__tvOy === y) return;
                wrap.__tvOx = x; wrap.__tvOy = y;
                wrap.style.left = x + 'px';
                wrap.style.top = y + 'px';
                console.log('[tvfs] place ' + x + ',' + y);
              }
              // 光对位还不够：只要页面还能滚，偏移下一轮又变。全屏期间把页面钉住
              // （播放器 focus / scrollIntoView 一滚就回来了），退出全屏时原样还原。
              function lockPageScroll() {
                if (window.__tvScrollLock) return;
                var de = document.documentElement, bd = document.body;
                window.__tvScrollLock = {
                  html: de ? de.style.overflow : '',
                  body: bd ? bd.style.overflow : ''
                };
                try {
                  if (de) de.style.setProperty('overflow', 'hidden', 'important');
                  if (bd) bd.style.setProperty('overflow', 'hidden', 'important');
                  window.scrollTo(0, 0);
                } catch (e) {}
              }
              function fitWrap(wrap) {
                // 先对位再定尺寸：偏移可能在上两轮之间变过
                placeWrap(wrap);
                lockPageScroll();
                var size = viewportSize();
                if (!size) return;
                var w = size.w, h = size.h;
                // 尺寸没变就别重写样式：写一次就是一次布局，轮询里等于白抖
                if (wrap.__tvW === w && wrap.__tvH === h) return;
                wrap.__tvW = w; wrap.__tvH = h;
                // 记一笔“盒子刚改过尺寸”：页面缩放还在飘的时候，容器会按旧 scale 铺，
                // scale 一变就多撑出去一截，露出来就是画面被顶大/裁掉一下
                window.__tvFitAt = Date.now();
                wrap.style.width = w + 'px';
                wrap.style.height = h + 'px';
                console.log('[tvfs] fit ' + w + 'x' + h +
                  ' dev=' + (window.__tvDev ? window.__tvDev.w + 'x' + window.__tvDev.h : '-') +
                  ' dpr=' + (window.devicePixelRatio || 1) +
                  ' scale=' + ((window.visualViewport && window.visualViewport.scale) || 1));
              }
              // 把除当前铺满那一路以外的 video 全部静音（只看状态，不改播放/暂停）
              function muteOthers(cur) {
                var off = function (v) {
                  if (!v || v === cur) return;
                  try { if (!v.muted) v.muted = true; } catch (e) {}
                };
                try {
                  var all = document.querySelectorAll('video');
                  for (var i = 0; i < all.length; i++) off(all[i]);
                } catch (e) {}
                // 影子根里的那些不在 document 里（attachShadow 拦截时登记的清单）
                try {
                  var sh = window.__tvShadowVids || [];
                  for (var j = 0; j < sh.length; j++) off(sh[j]);
                } catch (e) {}
              }
              // 播放器可能会反复重写 video 的行内样式，每次都要重新落实
              function applyStyles(el) {
                el.style.setProperty('position', 'static', 'important');
                el.style.setProperty('left', 'auto', 'important');
                el.style.setProperty('top', 'auto', 'important');
                el.style.setProperty('right', 'auto', 'important');
                el.style.setProperty('bottom', 'auto', 'important');
                el.style.setProperty('transform', 'none', 'important');
                el.style.setProperty('margin', '0', 'important');
                el.style.setProperty('padding', '0', 'important');
                el.style.setProperty('display', 'block', 'important');
                el.style.setProperty('width', '100%', 'important');
                el.style.setProperty('height', '100%', 'important');
                el.style.setProperty('min-width', '0', 'important');
                el.style.setProperty('min-height', '0', 'important');
                el.style.setProperty('max-width', 'none', 'important');
                el.style.setProperty('max-height', 'none', 'important');
                el.style.setProperty('object-fit', 'contain', 'important');
                el.style.setProperty('background', '#000', 'important');
                // 把 video 从原来的容器里挪出来以后，页面自己针对它的样式（比如播放器
                // 挂在容器下的 `video{outline:none}`）就不再匹配了。而播放器通常会对
                // video 调 focus()，内核就按默认焦点框在画面外画一圈高亮（设备主题色，
                // 常见是黄色）——看着就是“全屏后多了一个黄边框”。这里一律抹平：不锁
                // 任何站点，只把所有可能描边的属性重置掉。
                el.style.setProperty('outline', 'none', 'important');
                el.style.setProperty('border', '0', 'important');
                el.style.setProperty('box-shadow', 'none', 'important');
                el.style.setProperty('border-radius', '0', 'important');
                el.style.setProperty('-webkit-tap-highlight-color', 'transparent', 'important');
                // 调试标记：画在 video 元素框内侧（不占布局）。青色是容器边界，
                // 粉色是 video 元素边界——两个不重合就知道是谁偏了。
                // object-fit:contain 时画面可能比元素框小一圈（上下或左右留黑），
                // 所以粉色框可以对不上图像边缘，那不算 bug。
                // 只在配置页「开发者调试」的几何调试打开时才画。
                if (DBG) el.style.setProperty('box-shadow', 'inset 0 0 0 4px #ff2d95', 'important');
                bindVolume(el);
                applyVolume(el);
              }
              // 边界标记是单独一层覆盖物：绝对定位不参与 flex，也不占 wrap 的内盒。
              // （以前用 wrap 自己的 border，把内盒缩掉一圈，测到的 video 矩形永远是缩过的）
              // 开关实时变：DBG 为假时把已有标记撤掉，不用切台
              function syncMark(wrap) {
                var old = wrap.querySelector('[data-tvfs-mark]');
                if (!DBG) { if (old) old.parentNode.removeChild(old); return; }
                if (old) return;
                var mk = document.createElement('div');
                mk.setAttribute('data-tvfs-mark', '1');
                mk.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;' +
                  'pointer-events:none;box-shadow:inset 0 0 0 4px #00e5ff;';
                wrap.appendChild(mk);
              }
              var cur = window.__tvFs;
              // 当前全屏那个还活着吗（地址还在、没被暂停、没放完）
              var curAlive = false;
              if (cur && cur.el && cur.wrap && cur.wrap.parentNode) {
                try { curAlive = !!playableSrc(cur.el) && !cur.el.paused && !cur.el.ended; }
                catch (e) { curAlive = false; }
              }
              // 不管它死没死，都把 wrap 里那个从候选里排除。
              // 它在 wrap 里量出来永远是满屏（851x393），不是它本来的尺寸：一旦参与比较，
              // 要么赖着不走，要么被一个大一圈的页面元素顶掉又顶回来 —— 两种都是来回跳。
              var v = pickVideo(cur && cur.el ? cur.el : null);
              if (!v) {
                if (!curAlive) return '0';
                // 没别的候选：继续用现在这个
                applyStyles(cur.el);
                fitWrap(cur.wrap);
                syncMark(cur.wrap);
                tryPlay(cur.el);
                return '1';
              }
              // 第一时间开声音：不等容器建好、不等下一轮轮询
              bindVolume(v);
              applyVolume(v);
              var candRect = v.getBoundingClientRect();
              var candArea = candRect.width * candRect.height;
              // 起播这几秒里可视区本身会反复变（播放器初始化、弹幕层、系统栏收起都改它），
              // 只靠一秒一轮的轮询，每轮都得带着错尺寸多撑一会儿，看着就是全屏“跳”两三下，
              // 而且跳不跳全看这些变化落在揭示前还是揭示后 —— 有时一打开就是对的。
              // 绑上 resize：可视区一变就立刻重铺，把误差压到一帧内。
              var vv = window.visualViewport;
              if (vv && !window.__tvFsBind) {
                window.__tvFsBind = 1;
                vv.addEventListener('resize', function () {
                  var s = window.__tvFs;
                  if (!s || !s.wrap || !s.wrap.parentNode) return;
                  fitWrap(s.wrap);
                  if (s.el) applyStyles(s.el);
                });
                // 平移（offsetTop/offsetLeft 变）：固定定位在这个内核上会跟着跑，重新对位
                vv.addEventListener('scroll', function () {
                  var s = window.__tvFs;
                  if (!s || !s.wrap || !s.wrap.parentNode) return;
                  placeWrap(s.wrap);
                });
              }
              // WebView 自己的尺寸变了（系统栏收起/弹出、横竖屏、多窗口）也要立刻重铺，
              // 不等每秒一轮的看门狗 —— 旧尺寸多停一秒，画面就是错的一秒
              if (!window.__tvFsBindWin) {
                window.__tvFsBindWin = 1;
                window.addEventListener('resize', function () {
                  var s = window.__tvFs;
                  if (!s || !s.wrap || !s.wrap.parentNode) return;
                  fitWrap(s.wrap);
                  if (s.el) applyStyles(s.el);
                });
              }
              if (cur && cur.el === v && cur.wrap && cur.wrap.parentNode) {
                applyStyles(v);
                fitWrap(cur.wrap);
                syncMark(cur.wrap);
                // 播放器可能把 video 暂停了：每轮都确保在播
                tryPlay(v);
                return '1';
              }

              // 铺满之外的其他 video（广告/预览流）一律静音：
              // 声音闸门在揭幕时就打开了，EARLY_MUTE_JS 那条不再管新出现的元素，
              // 而选片这边只要当前这路还活着就不换片，新插进来的广告会自己带着声音播。
              if (cur && cur.el) muteOthers(cur.el);

              // 当前这个还在正常播：死也不换。
              // 面积不能当判据——同一路视频量出来的面积跟桌面完全不同（广告容器在
              // WebView 里没缩，广告的 1280x720 反而比直播的 800x450 大）。
              // 只要现在这个画面是好的，就别让新冒出来的东西抢位；广告放完会自己停，
              // 到那时 curAlive 变假，下一段逻辑才把位置交给直播。
              // 不换就不会出现“广告 ↔ 直播”每秒来回跳、两路轮流出声。
              if (cur && cur.wrap && cur.wrap.parentNode && curAlive) {
                applyStyles(cur.el);
                fitWrap(cur.wrap);
                syncMark(cur.wrap);
                tryPlay(cur.el);
                return '1';
              }

              // 页面里换成了另一个更大的 video（典型情形：广告 → 正片换源）。
              // 复用现有全屏容器：把旧 video 放回原处、把新 video 移进同一个 #000 容器，
              // 不销毁容器 → 不会出现第二次“黑屏进入”闪烁。
              if (cur && cur.wrap && cur.wrap.parentNode) {
                var newParent = v.parentNode, newNext = v.nextSibling, newCss = v.style.cssText;
                try {
                  if (cur.el) {
                    // 旧的那一路只静音、不暂停：静音就没有两路声音，画面又被全屏容器盖着看不见。
                    // 暂停会让站点的广告系统认为“广告被打断”，转头再抢一次直播 ——
                    // 站点那种循环（切广告→暂停广告→抢直播→切回）就是这么来的
                    try { cur.el.muted = true; } catch (e) {}
                    cur.el.style.cssText = cur.css || '';
                    if (cur.parent) {
                      if (cur.next && cur.next.parentNode === cur.parent) {
                        cur.parent.insertBefore(cur.el, cur.next);
                      } else {
                        cur.parent.appendChild(cur.el);
                      }
                    }
                  }
                  cur.wrap.appendChild(v);
                  applyStyles(v);
                  fitWrap(cur.wrap);
                } catch (e) { return '0'; }
                window.__tvFs = { el: v, parent: newParent, next: newNext, css: newCss,
                  wrap: cur.wrap, origArea: candArea,
                  origW: candRect.width, origH: candRect.height };
                syncMark(cur.wrap);
                tryPlay(v);
                console.log('[tvfs] switched video inside existing wrap');
                return '1';
              }

              // 还没有全屏容器：新建一个
              var wrap = document.createElement('div');
              wrap.setAttribute('data-tvfs', '1');
              // 容器的尺寸、定位全交给 fitWrap()。调试用的边界标记不画在容器自己身上：
              // 容器自身的 border/box-shadow 会被铺满的 video 整个盖住（看不见），
              // 而真 border 又会占内盒、把 video 挤进去一圈（测到的几何就不真了）。
              wrap.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;' +
                'background:#000;z-index:2147483647;display:flex;align-items:center;' +
                'justify-content:center;overflow:hidden;box-sizing:border-box;';
              fitWrap(wrap);

              // 记下“进 wrap 之前”的面积：以后判断该不该换片，只能和这个比。
              // 进 wrap 以后它永远是满屏，拿满屏面积当基准的话谁都换不动它
              var preRect = v.getBoundingClientRect();
              var state = { el: v, parent: v.parentNode, next: v.nextSibling, css: v.style.cssText,
                origArea: preRect.width * preRect.height,
                origW: preRect.width, origH: preRect.height };
              try {
                wrap.appendChild(v);
                applyStyles(v);
                syncMark(wrap);
                document.documentElement.appendChild(wrap);
              } catch (e) { return '0'; }

              state.wrap = wrap;
              window.__tvFs = state;
              // 部分页面不自动播放：明确调 play()，没起来再重试
              tryPlay(v);
              console.log('[tvfs] wrap largest playable video');
              return '1';
            })();
        """

        // 几何调试：把容器/视频/视口的几何数字打进日志，用来定位"容器偏上、下方一大块黑"。
        // 只读不写，不改任何样式；开关是配置页「开发者调试」里的几何调试，默认关。
        val GEOM_PROBE_JS = """
            (function () {
              var s = window.__tvFs;
              if (!s || !s.wrap) return '';
              var w = s.wrap, de = document.documentElement, vv = window.visualViewport;
              var wr = w.getBoundingClientRect();
              var vr = s.el ? s.el.getBoundingClientRect() : null;
              var cw = getComputedStyle(w);
              var ch = getComputedStyle(de);
              var r = function (n) { return Math.round(n); };
              console.log('[tvgeom] wrapRect=' + r(wr.left) + ',' + r(wr.top) + ' ' + r(wr.width) + 'x' + r(wr.height) +
                ' wrapStyle=' + w.style.width + '/' + w.style.height +
                ' wrapComputed=' + cw.width + '/' + cw.height +
                ' videoRect=' + (vr ? r(vr.left) + ',' + r(vr.top) + ' ' + r(vr.width) + 'x' + r(vr.height) : '-') +
                ' inner=' + window.innerWidth + 'x' + window.innerHeight +
                ' doc=' + de.clientWidth + 'x' + de.clientHeight +
                ' vv=' + (vv ? r(vv.width) + 'x' + r(vv.height) + ' scale=' + vv.scale +
                  ' off=' + r(vv.offsetLeft) + ',' + r(vv.offsetTop) : '-') +
                ' dpr=' + (window.devicePixelRatio || 1) +
                ' dev=' + (window.__tvDev ? window.__tvDev.w + 'x' + window.__tvDev.h : '-') +
                ' htmlTransform=' + ch.transform + ' scrollY=' + r(window.scrollY));
              return '';
            })();
        """

        // 全屏容器里的视频是否真的出画面了（这一源连续交出 5 帧）
        // '1' = 可以揭幕；'2' = 画面到位但容器还在改尺寸，再等等，别把跳变露给用户；'0' = 还没出帧
        // 影子里的 video 也能成全屏对象（它被移进 wrap 之后也是个正常 video），
        // 因此 FRAME_READY_JS 不用改：只看 window.__tvFs.el 对应元素的帧/暂停状态。
        // 换句话说，这里不统计/搜索 video，只取 s.el —— 搜 video 的活儿在 APPLY_FS_JS 里已做过。
        val FRAME_READY_JS = """
            (function () {
              var s = window.__tvFs;
              var v = s && s.el ? s.el : null;
              if (!v) return '0';
              var hasQ = !!v.getVideoPlaybackQuality;
              var frames = hasQ ? v.getVideoPlaybackQuality().totalVideoFrames : 0;
              if (v.__tvBaseF === undefined) v.__tvBaseF = frames;
              // 帧数往回走 = 播放器换了源（起播时常先挂一个只蹦一两帧就不动的地址）：
              // 基准重来，黑幕继续压到新源真交画面 —— 不用去听 src 变化
              if (frames < v.__tvBaseF) v.__tvBaseF = frames;
              // 老 WebView 没有计帧接口时退回原来的“有当前数据就算”
              var ready = v.readyState >= 2 && !v.paused &&
                  (!hasQ || frames - v.__tvBaseF >= 5);
              if (!ready) return '0';
              var t = window.__tvFitAt;
              if (t && Date.now() - t < 400) return '2';
              return '1';
            })();
        """

        /** 进登录页：先停掉直播，回来再重播 */
        const val PAUSE_MEDIA_JS = """
            (function () {
              // 影子里的媒体也停掉：播放器把 video 藏在 open shadow 里时
              // document.querySelectorAll('video, audio') 够不着
              var paused = new WeakSet();
              function pauseAll(root) {
                if (!root) return;
                try {
                  var els = root.querySelectorAll('video, audio');
                  for (var i = 0; i < els.length; i++) {
                    var e = els[i];
                    if (!paused.has(e)) {
                      paused.add(e);
                      try { e.pause(); } catch (e2) {}
                    }
                  }
                } catch (e) {}
                try {
                  var kids = root.children;
                  for (var j = 0; j < kids.length; j++) {
                    var k = kids[j];
                    if (k.shadowRoot && !paused.has(k.shadowRoot)) {
                      paused.add(k.shadowRoot);
                      pauseAll(k.shadowRoot);
                    }
                    pauseAll(k);
                  }
                } catch (e) {}
              }
              pauseAll(document);
              pauseAll(document.documentElement);
              pauseAll(document.body);
              return '1';
            })();
        """

        // 退出 CSS 全屏：视频放回原位置、恢复原样式
        val EXIT_FS_JS = """
            (function () {
              var s = window.__tvFs;
              if (!s) return '0';
              window.__tvFs = null;
              try {
                // 还原全屏期间钉住的页面滚动
                var lock = window.__tvScrollLock;
                if (lock) {
                  window.__tvScrollLock = null;
                  if (document.documentElement) document.documentElement.style.overflow = lock.html;
                  if (document.body) document.body.style.overflow = lock.body;
                }

                if (s.el) s.el.style.cssText = s.css || '';
                if (s.wrap && s.wrap.parentNode) s.wrap.parentNode.removeChild(s.wrap);
                if (s.el && s.parent) {
                  if (s.next && s.next.parentNode === s.parent) {
                    s.parent.insertBefore(s.el, s.next);
                  } else {
                    s.parent.appendChild(s.el);
                  }
                }
              } catch (e) {}
              return '1';
            })();
        """

        // 诊断：报告页面 URL / 文字 / 深层 video 状态，并试一次全屏看返回结果
        val PROBE_JS = """
            (function () {
              var out = {
                url: location.href, n: 0, vids: [], text: '', tc: '',
                elems: 0, htmlLen: 0,
                fsEnabled: document.fullscreenEnabled, fe: !!document.fullscreenElement
              };
              try {
                out.text = (document.body ? document.body.innerText : '')
                  .replace(/\s+/g, ' ').slice(0, 240);
                out.tc = (document.body ? document.body.textContent : '')
                  .replace(/\s+/g, ' ').slice(0, 500);
                out.elems = document.querySelectorAll('*').length;
                out.htmlLen = document.documentElement.outerHTML.length;
                // 只统计主文档：iframe 里的 video 不参与选片，这里也不列
                var vs = document.querySelectorAll('video');
                for (var j = 0; j < vs.length; j++) {
                  var v = vs[j];
                  out.n++;
                  out.vids.push({
                    src: (v.getAttribute('src') || v.src || '').slice(0, 48),
                    paused: v.paused, w: v.videoWidth,
                    wk: typeof v.webkitEnterFullscreen, rs: typeof v.requestFullscreen
                  });
                }
              } catch (e) { out.err = '' + e; }
              // 影子根里的 video 也算进来：读 attachShadow 拦截时收集的那份清单
              (function () {
                var shadowVids = window.__tvShadowVids || [];
                for (var i2 = 0; i2 < shadowVids.length; i2++) {
                  var sv = shadowVids[i2];
                  if (!sv || sv.isConnected === false) continue;
                  out.n++;
                  out.vids.push({
                    src: (sv.getAttribute('src') || sv.src || '').slice(0, 48),
                    paused: sv.paused, w: sv.videoWidth,
                    wk: typeof sv.webkitEnterFullscreen, rs: typeof sv.requestFullscreen
                  });
                }
              })();
              var v0 = document.querySelector('video[src^="blob:"]') ||
                document.querySelector('video');
              if (v0 && v0.requestFullscreen) {
                return v0.requestFullscreen().then(function () {
                  out.attempt = 'OK';
                  return JSON.stringify(out);
                }, function (e) {
                  out.attempt = 'FAIL:' + e;
                  return JSON.stringify(out);
                });
              }
              return JSON.stringify(out);
            })();
        """

        // 配了脚本的页面：脚本跑完后再等这么久才允许揭幕（给 holdCover 一个到达的窗口）
        const val SCRIPT_GRACE_MS = 300L

        /**
         * 注入到页面里的 TV 接口。应用只提供这层通用能力，找元素/点按钮全在用户脚本里。
         * 所有上报都走 TVNative 一个出口，原生那边再用 scriptCode 拦一道：
         * 当前页没匹配到脚本时这些消息一律丢弃。
         */
        val TV_BOOT_JS = """
            (function () {
              var api = window.TV && window.TV.__tv ? window.TV : {};
              function post(o) { try { TVNative.send(JSON.stringify(o)); } catch (e) {} }
              api.__tv = 1;
              api.log = function (m) { post({ t: 'log', m: String(m) }); };
              api.toast = function (m) { post({ t: 'toast', m: String(m) }); };
              // 让应用按这个节奏重复执行脚本（页面渲染慢、SPA 换路由时用）
              api.repeat = function (ms) { post({ t: 'repeat', ms: ms | 0 }); };
              // 要开始页内切台了：先把黑幕压住，切台中间不露旧画面/半成品
              api.holdCover = function () { post({ t: 'holdCover' }); };
              // 切完了：放开黑幕，应用重新接管播放（新流有首帧才渐退）
              api.afterSwitch = function () { post({ t: 'afterSwitch' }); };
              // 只跑一次：脚本会被多次注入（DOM 就绪 / 播放器就绪 / 地址变化），
              // 有副作用的初始化放这里
              api.once = function (key, fn) {
                window.__tvOnce = window.__tvOnce || {};
                if (window.__tvOnce[key]) return;
                window.__tvOnce[key] = 1;
                try { fn(); } catch (e) { api.log('once 异常: ' + e); }
              };
              // 元素点击：滚到可见再点，失败只记日志，不抛给页面
              api.click = function (el) {
                if (!el) { api.log('click: 元素为空'); return false; }
                try { if (el.scrollIntoView) el.scrollIntoView({ block: 'center' }); } catch (e) {}
                try { el.click(); return true; } catch (e) { api.log('click 异常: ' + e); return false; }
              };
              window.TV = api;
            })();
        """

        // 无面板时按这几键呼出频道面板（MENU 是不少电视遥控器的“菜单”键）
        val PANEL_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU
        )

        // 直播网页里要屏蔽的按键（方向键/OK/Tab），音量键等系统键不受影响
        val BLOCKED_PAGE_KEYS = intArrayOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP
        )

        val FONT_EXTS = arrayOf(".woff", ".woff2", ".ttf", ".otf", ".eot")
        val IMAGE_EXTS = arrayOf(
            ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg",
            ".ico", ".bmp", ".apng", ".avif"
        )
    }
}
