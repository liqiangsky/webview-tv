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
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.text.Editable
import android.text.TextWatcher
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Date
import java.util.concurrent.Executors

/**
 * Android TV 直播网页播放器（沉浸式频道面板 V2）：
 * 底层全屏 WebView 常驻播放，按 OK / 点屏呼出频道选择遮罩：
 * 顶部 Coverflow 分组栏（居中放大、两侧压暗、自动吸附）+ 下方分组频道列表，
 * 焦点位置持久化（与正在播放的频道解耦），编辑页为全屏覆盖原生页。
 * 遮罩打开时视频持续播放；OK 切台并收起；Back：列表 → 分组 → 收起。
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    // 频道选择遮罩（列表与介绍都在分组大面板内部）
    private lateinit var panelRoot: View
    private lateinit var groupScroll: CoverflowScrollView
    private lateinit var groupRow: LinearLayout

    // 左侧频道信息栏（台标 + 在播/预览状态 + 节目单，纯展示）
    private lateinit var infoPanel: View
    private lateinit var infoLogo: TextView
    private lateinit var infoLogoImage: ImageView
    private lateinit var infoState: TextView
    private lateinit var infoQuality: TextView
    private lateinit var infoDesc: TextView
    private lateinit var infoClock: TextView
    private lateinit var infoDate: TextView
    private lateinit var infoNow: TextView
    private lateinit var infoTime: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: View
    private var infoTarget: Channel? = null    // 信息栏当前展示的频道，节目单跟着它走
    private var infoFollowFocus = true        // 打开面板的首次聚焦不覆盖“在播频道”
    private val clockFmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
    private val dateFmt = java.text.SimpleDateFormat("M月d日 EEEE", java.util.Locale.CHINA)

    // 直播页 / 遮罩是否处于打开状态
    private var webOpen = false
    private var panelOpen = false

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

    // 进入直播网页后屏蔽页面操作（只看视频，不让人点网页）
    private var pageInputBlocked = false

    // 调试浮层：把页面里每个 <video> 的 src / 状态显示在屏幕上（排查广告 video）
    private var debugOverlay: TextView? = null
    private val debugWatch: Runnable = object : Runnable {
        override fun run() {
            val tv = debugOverlay ?: return
            if (!webOpen) {
                // 直播页没打开时不显示浮层（避免盖住原生首页）
                tv.visibility = View.GONE
                webView.postDelayed(this, 1000)
                return
            }
            tv.visibility = View.VISIBLE
            webView.evaluateJavascript(DEBUG_INFO_JS) { result ->
                if (result != null) {
                    runCatching {
                        tv.text = JSONTokener(result).nextValue() as String
                        tv.bringToFront()
                    }
                }
            }
            webView.postDelayed(this, 1000)
        }
    }

    // 轮询页面里的 blob 视频；一出现就把它铺满视口（不需要全屏 API，不需要用户手势）
    private val videoWatch: Runnable = object : Runnable {
        override fun run() {
            if (customView != null || transitionCover == null) return
            webView.evaluateJavascript(APPLY_FS_JS) { result ->
                if (result?.trim()?.trim('"') == "1") {
                    cssFullscreen = true
                    Log.d(TAG, "blob video fullscreen applied")
                    webView.removeCallbacks(videoWatch)
                    // 等视频真正出第一帧再褪黑幕（不拍脑袋定时间）
                    webView.removeCallbacks(frameWatch)
                    webView.postDelayed(frameWatch, 250)
                }
            }
            webView.postDelayed(this, 800)
        }
    }

    // 等首帧真正呈现（requestVideoFrameCallback），然后才淡入直播画面
    private val frameWatch: Runnable = object : Runnable {
        override fun run() {
            if (transitionCover == null) return
            webView.evaluateJavascript(FRAME_READY_JS) { result ->
                if (result?.trim()?.trim('"') == "1") {
                    Log.d(TAG, "first video frame ready")
                    hideTransitionCover()
                }
            }
            webView.postDelayed(this, 250)
        }
    }

    // 全屏后的看门狗：跟着切到正在播的 video，并把被播放器重写的样式重新落实
    private val fsWatch: Runnable = object : Runnable {
        override fun run() {
            if (!cssFullscreen) return
            webView.evaluateJavascript(APPLY_FS_JS, null)
            webView.postDelayed(this, 1000)
        }
    }

    // 兑底：40 秒还没全屏就收起过渡动画，避免一直转圈
    private val coverTimeout = Runnable {
        if (customView == null && transitionCover != null) {
            Log.d(TAG, "cover timeout, hide")
            hideTransitionCover()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)
        // 必须在 setContentView 之后：此时 DecorView 才存在
        hideSystemUi()

        panelRoot = findViewById(R.id.panelRoot)
        groupScroll = findViewById(R.id.groupScroll)
        groupRow = findViewById(R.id.groupRow)
        infoPanel = findViewById(R.id.infoPanel)
        infoLogo = findViewById(R.id.infoLogo)
        infoLogoImage = findViewById(R.id.infoLogoImage)
        infoState = findViewById(R.id.infoState)
        infoQuality = findViewById(R.id.infoQuality)
        infoDesc = findViewById(R.id.infoDesc)
        infoClock = findViewById(R.id.infoClock)
        infoDate = findViewById(R.id.infoDate)
        infoNow = findViewById(R.id.infoNow)
        infoTime = findViewById(R.id.infoTime)
        infoNext = findViewById(R.id.infoNext)
        infoProgress = findViewById(R.id.infoProgress)
        // 手机滑动换组（触屏模式没有焦点事件）：选中态跟随居中卡
        groupScroll.onCenterChanged = { idx -> onGroupCentered(idx) }

        // 点空白处收起遮罩
        panelRoot.setOnClickListener { hidePanel() }

        webView = findViewById(R.id.webView)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        setupWebView()

        // 底层常驻播放：直接播第一个频道，遮罩随时呼出
        reloadChannels()
        groups.firstOrNull()?.channels?.firstOrNull()?.let {
            playingLine = it.line
            refreshAllPanels()      // 面板在 playingLine 赋值前就建好了，补一次才有“播放中”
            openChannel(it.url)
        } ?: Toast.makeText(this, "暂无频道：请在 channels.txt 里按 \"分组名,#genre#\" 格式添加", Toast.LENGTH_LONG).show()
        // 节目单到货（闹钟或启动补拉）后重画信息栏
        EpgManager.onChanged = { renderGuide() }
        renderInfo(null)
        infoPanel.postDelayed(clockTick, 20_000)

        if (DEBUG_SHOW_VIDEO_INFO) {
            val tv = TextView(this).apply {
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(0xFFFFFFFF.toInt())
                setTypeface(android.graphics.Typeface.MONOSPACE)
                textSize = 14f
                setPadding(dp(10), dp(8), dp(10), dp(8))
            }
            addContentView(
                tv,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.START
                )
            )
            debugOverlay = tv
            webView.postDelayed(debugWatch, 1200)
        }
    }

    // ---------- 频道选择遮罩（Coverflow 分组 + 频道列表） ----------

    /** 读频道文件并按分组归类（“分组名,#genre#”标题行，或行尾“$分组”后缀） */
    private fun reloadChannels() {
        groups = groupChannels(parseChannels(loadChannelsText()))
        curGroupIdx = curGroupIdx.coerceIn(0, (groups.size - 1).coerceAtLeast(0))
        buildGroupRow()
    }

    private fun groupChannels(chs: List<Channel>): List<Group> {
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in chs) map.getOrPut(ch.group) { mutableListOf() }.add(ch)
        return map.map { Group(it.key, it.value) }
    }

    /** 构建 Coverflow 分组面板（标题独立悬浮在面板顶部，卡片内只有频道列表） */
    private fun buildGroupRow() {
        groupRow.removeAllViews()
        for (gi in groups.indices) {
            groupRow.addView(createGroupPanel(gi))
        }
        groupScroll.post { resizeGroupPanels() }
    }

    /** 面板宽度 = 屏宽 68%、高度 = 可视区 78%，保证两侧透出相邻分组、上下不顶满屏幕 */
    private fun resizeGroupPanels() {
        val w = groupScroll.width
        val h = groupScroll.height
        if (w == 0 || h == 0) {
            groupScroll.post { resizeGroupPanels() }
            return
        }
        val panelW = (w * 0.68f).toInt()
        val panelH = (h * 0.78f).toInt()
        val sidePad = ((w - panelW) / 2).coerceAtLeast(0)
        groupRow.setPadding(sidePad, 0, sidePad, 0)
        for (i in 0 until groupRow.childCount) {
            val child = groupRow.getChildAt(i)
            val lp = child.layoutParams
            if (lp.width != panelW || lp.height != panelH) {
                lp.width = panelW
                lp.height = panelH
                child.layoutParams = lp
            }
        }
        groupScroll.post { groupScroll.snapToNearest(false) }
    }

    private fun createGroupPanel(index: Int): View {
        val v = layoutInflater.inflate(R.layout.item_group_panel, groupRow, false)
        v.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                curGroupIdx = index
                groupScroll.smoothScrollToCenter(index)
                updateGroupSelection(index)
                renderChannelList(index)
                // 进组即落点：本组有在播频道就选中它，否则第一行
                focusListIndex(index, openListIndex(index))
                if (infoFollowFocus) renderInfo(focusedChannel())
            }
            groupScroll.applyDepth() // 选中卡全亮 / 两侧景深重新计算
        }
        v.setOnClickListener {
            // 触屏点侧边卡片：该分组成为选中组（电视走焦点，不需要这里处理）
            if (index != curGroupIdx) {
                activateGroup(index)
            } else {
                focusListIndex(index, openListIndex(index))
            }
        }
        v.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { switchGroup(index - 1); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { switchGroup(index + 1); true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    focusListIndex(index, openListIndex(index))
                    true
                }
                else -> false
            }
        }
        // 面板刚创建（还没入树）：直接在里面建频道行
        renderChannelList(index, v)
        return v
    }

    /** 左右切组（260ms 防抖）：焦点进该组卡片，由卡片焦点回调落到在播频道/首行 */
    private fun switchGroup(target: Int) {
        if (target !in 0 until groupRow.childCount) return
        val now = SystemClock.uptimeMillis()
        if (now - lastGroupNavAt < 260) return
        lastGroupNavAt = now
        groupRow.getChildAt(target)?.requestFocus()
    }

    /** 渲染指定分组面板里的频道列表（正在播放的行带声波角标） */
    private fun renderChannelList(groupIdx: Int) {
        val panel = groupRow.getChildAt(groupIdx) ?: return
        renderChannelList(groupIdx, panel)
    }

    private fun renderChannelList(groupIdx: Int, panel: View) {
        val list = panel.findViewById<LinearLayout>(R.id.panelList) ?: return
        list.removeAllViews()
        val g = groups.getOrNull(groupIdx) ?: return
        for ((ci, ch) in g.channels.withIndex()) {
            val row = layoutInflater.inflate(R.layout.item_panel_channel, list, false)
            row.findViewById<TextView>(R.id.rowName).text = ch.name
            val isPlaying = ch.line == playingLine
            if (isPlaying) {
                row.findViewById<View>(R.id.playBadge).visibility = View.VISIBLE
                styleRow(row, true)          // 里面会把声波动画拉起来
            }
            row.tag = ci
            row.setOnFocusChangeListener { v2, hasFocus ->
                if (groupIdx != curGroupIdx) {
                    // 侧边分组频道行意外抢焦（按 id 恢复焦点）：不显示光标，焦点还给当前组
                    if (hasFocus) panel.post { focusGroupCard(curGroupIdx) }
                    return@setOnFocusChangeListener
                }
                styleRow(v2, hasFocus || isPlaying)
                if (hasFocus) {
                    listIndexByGroup[groupIdx] = ci
                    if (infoFollowFocus) renderInfo(ch)
                    val scroll = panel.findViewById<ScrollView>(R.id.panelListScroll)
                    scroll?.post { smoothScrollToView(scroll, v2) }
                    persistFocus()
                }
            }
            row.setOnClickListener {
                // 触屏点的频道不属于当前激活分组：只切组，不播放（再点一次即播放）
                if (groupIdx != curGroupIdx) {
                    activateGroup(groupIdx)
                } else {
                    playChannel(ch)
                }
            }
            row.setOnKeyListener { v, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    // 列表第一行按上 → 光标上浮回分组面板
                    KeyEvent.KEYCODE_DPAD_UP ->
                        if ((v.tag as? Int) == 0) { focusGroupCard(groupIdx); true } else false
                    // 列表里左右键也切组，下键交给焦点系统上下移动
                    KeyEvent.KEYCODE_DPAD_LEFT -> { switchGroup(groupIdx - 1); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { switchGroup(groupIdx + 1); true }
                    else -> false
                }
            }
            list.addView(row)
        }
    }

    // ---------- 左侧信息栏（台标 / 在播·预览状态 / 频道介绍 / EPG / 系统时间） ----------

    /** 信息栏跟着焦点预览；ch 为 null 表示回到“在播频道” */
    private fun renderInfo(ch: Channel?) {
        val target = ch ?: playingChannel() ?: return
        infoTarget = target
        val playing = target.line == playingLine
        infoLogo.text = target.name
        // 有图压图，没图（或还在下）就显示文字台标，位置不跳
        LogoLoader.into(infoLogoImage, target.logo) { has ->
            infoLogoImage.visibility = if (has) View.VISIBLE else View.INVISIBLE
            infoLogo.visibility = if (has) View.INVISIBLE else View.VISIBLE
        }
        infoQuality.text = target.quality
        // 电视只有光标没有鼠标，左侧必须说清这是“在播的台”还是“正在预览的台”
        infoState.text = if (playing) "正在播放" else "预览中"
        infoState.setBackgroundResource(
            if (playing) R.drawable.info_state_playing else R.drawable.info_state_preview
        )
        infoState.setTextColor(if (playing) 0xFF0E0E10.toInt() else 0xE6FFFFFF.toInt())
        val host = runCatching { Uri.parse(target.url).host }.getOrNull()
        infoDesc.text = if (host == null) target.group else "${target.group} · $host"
        updateInfoClock()
        renderGuide()
    }

    /** 节目单三行：没对上频道就退回占位文案，不影响其它信息 */
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
            infoTime.text = "--:-- ~ --:--"
            infoNext.text = "--"
            setGuideProgress(0)
            return
        }
        infoNow.text = guide.nowText
        infoTime.text = guide.timeText
        infoNext.text = guide.nextText
        setGuideProgress(guide.percent)
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

    private fun playingChannel(): Channel? =
        groups.asSequence().flatMap { it.channels.asSequence() }.firstOrNull { it.line == playingLine }

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

    /** 分组不再做描边高亮：居中卡靠景深（全亮/放大）天然突出，这里只同步景深基准 */
    private fun updateGroupSelection(idx: Int) {
        groupScroll.selectedIndex = idx
    }

    /** 手机滑动换组：选中态跟随居中卡（频道光标=在播频道，不随分组切换消失） */
    private fun onGroupCentered(idx: Int) {
        if (idx == curGroupIdx) return
        curGroupIdx = idx
        updateGroupSelection(idx)
        listIndexByGroup[idx] = openListIndex(idx)
        if (infoFollowFocus) renderInfo(focusedChannel())
        persistFocus()
    }

    /** 触屏激活分组：requestFocus 在触摸模式下不生效，显式切居中/选中/列表 */
    private fun activateGroup(idx: Int) {
        if (idx !in groups.indices) return
        curGroupIdx = idx
        groupScroll.smoothScrollToCenter(idx)
        updateGroupSelection(idx)
        renderChannelList(idx)
        listIndexByGroup[idx] = openListIndex(idx)
        if (infoFollowFocus) renderInfo(focusedChannel())
        persistFocus()
        groupScroll.applyDepth()
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

    private fun focusGroupCard(groupIdx: Int) {
        groupRow.getChildAt(groupIdx.coerceIn(0, groupRow.childCount - 1))?.requestFocus()
    }

    private fun focusListIndex(groupIdx: Int, index: Int) {
        val panel = groupRow.getChildAt(groupIdx) ?: return
        val g = groups.getOrNull(groupIdx) ?: return
        if (g.channels.isEmpty()) return
        val list = panel.findViewById<LinearLayout>(R.id.panelList) ?: return
        val idx = index.coerceIn(0, g.channels.size - 1)
        val row = list.getChildAt(idx) ?: return
        row.requestFocus()
        panel.findViewById<ScrollView>(R.id.panelListScroll)?.let { scroll ->
            scroll.post { smoothScrollToView(scroll, row) }
        }
    }

    private fun smoothScrollToView(scroll: ScrollView, v: View) {
        val target = v.top - scroll.height / 3
        scroll.smoothScrollTo(0, target.coerceAtLeast(0))
    }

    /** 选中频道：切台后面板保持打开（可连续换台）；点的正是在播频道则只收起面板 */
    private fun playChannel(ch: Channel) {
        if (ch.line == playingLine) {
            hidePanel()
            return
        }
        playingLine = ch.line
        refreshAllPanels()
        renderInfo(ch)
        persistFocus()
        openChannel(ch.url)
    }

    /** 切台后全量刷新：旧分组残留的“播放中”角标/光标全部清掉（渲染时只认当前在播频道） */
    private fun refreshAllPanels() {
        val gi = groups.indexOfFirst { g -> g.channels.any { it.line == playingLine } }
        for (i in 0 until groupRow.childCount) if (i != gi) renderChannelList(i)
        // 最后重渲染在播分组：声波角标动画不被后续面板的重渲染停掉
        if (gi >= 0) renderChannelList(gi)
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
        updateGroupSelection(gi)
        val li = openListIndex(gi)
        panelRoot.visibility = View.VISIBLE
        panelRoot.alpha = 0f
        panelRoot.animate().alpha(1f).setDuration(250).start()
        renderChannelList(gi)
        // 刚打开时左侧先给“在播频道”，等恢复焦点的那一下过去再跟着焦点走
        infoFollowFocus = false
        renderInfo(null)
        groupScroll.post {
            groupScroll.smoothScrollToCenter(gi)
            focusGroupCard(gi)
            focusListIndex(gi, li)
            groupScroll.post { infoFollowFocus = true }
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
        showTransitionCover()
        webView.visibility = View.VISIBLE
        webView.requestFocus()
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
        // 上一个台子的 40 秒兜底也要清：留着会在新页面加载中途把黑幕掀开
        old.removeCallbacks(coverTimeout)
        (customView?.parent as? ViewGroup)?.removeView(customView)
        customView = null
        customViewCallback = null
        cssFullscreen = false
        docUrl = null
        parent.removeView(old)
        old.destroy()
        val fresh = WebView(this)
        parent.addView(fresh, index, lp)
        fresh.isFocusable = true
        fresh.isFocusableInTouchMode = true
        webView = fresh
        setupWebView()
    }

    // ---------- 频道文件（txt，分组格式：分组名,#genre#） ----------

    private fun channelsFile(): File? = getExternalFilesDir(null)?.let { File(it, CHANNEL_FILE) }

    /** 读频道文本：外部文件优先，没有就用 assets 里的默认频道 */
    private fun loadChannelsText(): String {
        val f = channelsFile()
        if (f != null && f.isFile) {
            runCatching { return f.readText(Charsets.UTF_8) }
        }
        return runCatching {
            assets.open(CHANNEL_FILE).bufferedReader(Charsets.UTF_8).readText()
        }.getOrDefault("")
    }

    /**
     * 解析频道文本：
     *  - “分组名,#genre#”开一个分组（DIYP txt 格式）
     *  - “频道名称,频道地址,台标名称,清晰度”一行一个，后两列可省略（台标名默认取频道名称，清晰度默认 HD），地址后可加 “$分组名”
     *  - “#”开头为注释；分组之前的内容归“其他频道”
     */
    private fun parseChannels(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var group = ""
        text.lineSequence().forEachIndexed { idx, raw ->
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEachIndexed
            val parts = t.split(',')
            val name = parts[0].trim()
            if (name.isEmpty() || parts.size < 2) return@forEachIndexed
            if (parts[1].trim() == "#genre#") {
                group = name
                return@forEachIndexed
            }
            val urlField = parts[1].trim()
            val dollar = urlField.indexOf('$')
            val url = (if (dollar >= 0) urlField.substring(0, dollar) else urlField).trim()
            val g = (if (dollar >= 0) urlField.substring(dollar + 1).trim() else group)
                .ifEmpty { "其他频道" }
            val logo = parts.getOrElse(2) { "" }.trim().ifEmpty { name }
            val quality = parts.getOrElse(3) { "" }.trim().ifEmpty { "HD" }
            if (url.isNotEmpty()) {
                out += Channel(name, url, idx, g, logo, quality)
            }
        }
        return out
    }

    private data class Channel(
        val name: String,
        val url: String,
        val line: Int,
        val group: String,
        val logo: String,
        val quality: String
    )

    private data class Group(val name: String, val channels: List<Channel>)

    /** 沉浸式全屏：隐藏状态栏和导航栏 */
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
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
                    Log.d(TAG, "onShowCustomView")
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

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "console: " + message.message())
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
                    // 打开外部页面：先盖上过渡动画
                    showTransitionCover()
                    false
                } else if (scheme == "file") {
                    false // 本地错误页，留在应用内打开
                } else if (scheme == "tvhome") {
                    // 错误页里的“回到首页”：回到第一个频道
                    val first = groups.asSequence().flatMap { it.channels.asSequence() }.firstOrNull()
                    if (first != null) {
                        playingLine = first.line
                        openChannel(first.url)
                    }
                    true
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
             * 不加载网页的 CSS / 图片 / 字体等样式类资源（只拦 http/https 的网络请求，
             * 本地 file:// 首页资源不受影响）。视频/JS 等一律放行。
             * 另外整域拦截统计/广告脚本，以及 sourcemap / manifest 这类对播放无用的资源。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (!BLOCK_STYLE_RESOURCES) return null
                val url = request?.url ?: return null
                if (url.scheme?.lowercase() != "http" && url.scheme?.lowercase() != "https") {
                    return null
                }

                val host = url.host?.lowercase()
                if (host != null && TRACKING_HOSTS.any { host == it || host.endsWith(".$it") }) {
                    Log.d(TAG, "block tracking: $url")
                    return emptyResponse("script")
                }

                val dest = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
                    ?.value?.lowercase()

                val kind = when {
                    dest == "style" || dest == "image" || dest == "font" ||
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
                    if (staticAsset && plainFile && SERVE_FROM_SCRIPT_CACHE && request.method == "GET") {
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
                if (isLivePage) {
                    showTransitionCover()
                } else {
                    // 回到本地首页 / 错误页：不再需要过渡动画，也不屏蔽操作
                    hideTransitionCover()
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (url != null && url.startsWith("about:")) return
                // 兜底：视频全屏还没触发时，过渡动画一直盖着
                if (customView == null && url != null && url.startsWith("http")) {
                    showTransitionCover()
                }
                // 诊断：报告页面里 video 元素状态与全屏调用结果
                view?.evaluateJavascript(PROBE_JS) { result ->
                    Log.d(TAG, "probe: $result")
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

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // 遮罩里：光标在频道列表 → 退回分组卡片；已在分组卡片 → 收起遮罩
            if (panelOpen) {
                val focused = currentFocus
                val panel = groupRow.getChildAt(curGroupIdx)
                if (panel != null && focused != null && focused !== panel &&
                    isDescendantOf(panel, focused)
                ) {
                    focusGroupCard(curGroupIdx)
                } else {
                    hidePanel()
                }
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
        // 无遮罩时按 OK / 上下键 → 呼出遮罩（网页按键本来就被屏蔽）
        if (!panelOpen &&
            (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER ||
                keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_UP ||
                keyCode == KeyEvent.KEYCODE_DPAD_DOWN)
        ) {
            showPanel()
            return true
        }
        // 直播网页里屏蔽一切页面操作（方向键/OK/Tab），只看视频
        if (pageInputBlocked && keyCode in BLOCKED_PAGE_KEYS) {
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun isDescendantOf(parent: View, v: View): Boolean {
        var p: View? = v.parent as? View
        while (p != null) {
            if (p === parent) return true
            p = p.parent as? View
        }
        return false
    }

    /**
     * 直播画面里屏蔽网页触摸；手机/电视点一下屏幕即可呼出遮罩
     * （遮罩打开时触摸全部交给原生面板，点分组卡片以外的空白处关闭）
     */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        if (!pageInputBlocked || panelOpen) {
            if (panelOpen) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        tapX = ev.x
                        tapY = ev.y
                        tapDownAt = SystemClock.uptimeMillis()
                    }
                    MotionEvent.ACTION_UP ->
                        if (isTap(ev.x, ev.y) && !inGroupCard(ev.x, ev.y) && !inInfoPanel(ev.x, ev.y)) {
                            hidePanel()
                            return true
                        }
                }
            }
            return super.dispatchTouchEvent(ev)
        }
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

    /** 触点是否落在左侧信息栏：它是面板的一部分，点它不该收起面板 */
    private fun inInfoPanel(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        infoPanel.getLocationInWindow(loc)
        return x >= loc[0] && x <= loc[0] + infoPanel.width &&
            y >= loc[1] && y <= loc[1] + infoPanel.height
    }

    /** 触点是否落在某张分组卡片上（卡片被 coverflow 缩放过，按实际绘制范围算） */
    private fun inGroupCard(x: Float, y: Float): Boolean {
        val loc = IntArray(2)
        for (i in 0 until groupRow.childCount) {
            val ch = groupRow.getChildAt(i)
            ch.getLocationInWindow(loc)
            if (x >= loc[0] && x <= loc[0] + ch.width * ch.scaleX &&
                y >= loc[1] && y <= loc[1] + ch.height * ch.scaleY
            ) return true
        }
        return false
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
        // 必须重新起轮询并续上兜底超时，否则新页面能播也永远等不到首帧探测，黑幕一直盖着
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
        // 开始盯页面里的 blob 视频（先清掉可能在旧实例上排队的同一轮询，避免叠成多份）
        webView.removeCallbacks(videoWatch)
        webView.removeCallbacks(frameWatch)
        webView.postDelayed(videoWatch, 400)
        webView.removeCallbacks(coverTimeout)
        webView.postDelayed(coverTimeout, 40_000)
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
        Log.d(TAG, "cover fade out")
        webView.removeCallbacks(videoWatch)
        webView.removeCallbacks(frameWatch)
        webView.removeCallbacks(coverTimeout)
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
            else -> "image/png"
        }
        return WebResourceResponse(mime, "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private fun kindByExtension(path: String?): String? {
        if (path == null) return null
        return when {
            path.endsWith(".css") -> "style"
            path.endsWith(".map") -> "map"
            path.endsWith(".webmanifest") || path.endsWith(".manifest") -> "manifest"
            FONT_EXTS.any { path.endsWith(it) } -> "font"
            IMAGE_EXTS.any { path.endsWith(it) } -> "image"
            else -> null
        }
    }

    // ---------- 静态脚本磁盘缓存（同站多频道复用，二次进台免下载 JS） ----------

    private fun cacheRoot(): File = File(cacheDir, "webstatic").apply { mkdirs() }

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
            val bytes = conn.inputStream.use { it.readBytes() }
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
            Log.d(TAG, "cache store: $urlStr ttl=$ttl size=${bytes.size}")
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

    private companion object {
        const val TAG = "TvWeb"
        const val ERROR_URL = "file:///android_asset/error.html"

        // 频道列表文件（txt）：“分组名,#genre#” + 每行 “频道名称,频道地址”
        const val CHANNEL_FILE = "channels.txt"

        // 焦点记忆存储名
        const val PREFS = "panel_focus"

        // PC 桌面浏览器 UA：网站才会下发电脑版网页布局而不是 app/手机版
        const val TV_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        // 总开关：是否拦截网页的 CSS / 图片 / 字体资源
        const val BLOCK_STYLE_RESOURCES = true

        // 统计/广告/埋点域名黑名单：整域（含子域）返回空响应，播放器不感知
        val TRACKING_HOSTS = arrayOf(
            "google-analytics.com", "googletagmanager.com", "googlesyndication.com",
            "googleadservices.com", "doubleclick.net", "adnxs.com",
            "hm.baidu.com", "cnzz.com", "umeng.com", "umeng.co",
            "mmstat.com", "51.la", "growingio.com", "sensorsdata.cn",
            "talkingdata.com", "bugly.qq.com"
        )

        // 静态脚本磁盘缓存（按域名分组）：单文件上限 6MB、总预算 80MB
        const val CACHE_MAX_OBJECT = 6L * 1024 * 1024
        const val CACHE_BUDGET = 80L * 1024 * 1024
        // 总开关：脚本请求走本地缓存（未命中当场下载并留存，下次直接秒回）
        const val SERVE_FROM_SCRIPT_CACHE = true
        // 无 Cache-Control 但文件名带内容哈希/版本号的 JS：按不可变资源缓存 7 天
        const val CACHE_DEFAULT_TTL = 7 * 24 * 3600L
        val VERSIONED_URL = Regex("\\.[0-9a-f]{8,}\\.js|ver=[0-9a-z]+")

        // 回灌给内核的服务端响应头：缺了 CORS 头，crossorigin 脚本会被判加载失败
        val CORS_HEADERS = listOf(
            "Access-Control-Allow-Origin", "Access-Control-Allow-Credentials",
            "Access-Control-Allow-Headers", "Access-Control-Expose-Headers",
            "Cache-Control", "ETag", "Last-Modified", "Vary"
        )

        // 调试浮层开关：显示页面里每个 video 的 src（排查广告 video 用）
        const val DEBUG_SHOW_VIDEO_INFO = true

        // 列出页面所有 video 的 src 与状态
        val DEBUG_INFO_JS = """
            (function () {
              var out = [];
              var vs = document.querySelectorAll('video');
              for (var i = 0; i < vs.length; i++) {
                var v = vs[i];
                var s = v.getAttribute('src') || v.currentSrc || v.src || '';
                var r = v.getBoundingClientRect();
                out.push('v' + i + ': ' + s.slice(0, 90));
                out.push('   paused=' + v.paused + ' size=' +
                  Math.round(r.width) + 'x' + Math.round(r.height) +
                  ' t=' + v.currentTime.toFixed(1) + (v.muted ? ' muted' : ''));
              }
              return out.length ? out.join('\n') : '(no video)';
            })();
        """

        // 页面一启动就监听 DOM：新出现的 video[src^="blob:"] 立刻全屏（包括同源 iframe 里）。
        // keydown/click 处理器里调用才带“用户手势”，部分 WebView 全屏必须要有手势。
        // 把 blob 视频铺满视口（CSS 全屏，不依赖全屏 API / 用户手势）。
        // 页面上可能有多个 video（广告/正片）：选面积最大且带可播放 src 的那个
        // （blob: 或 m3u8/flv/mp4 等地址），覆盖样式铺满 100% 后全屏；每轮复选并尝试 play()。
        val APPLY_FS_JS = """
            (function () {
              // src 是可播放地址：blob: 或 m3u8/mpd/flv/mp4 等媒体后缀
              function playableSrc(v) {
                var s = v.getAttribute('src') || v.currentSrc || v.src || '';
                if (!s) return null;
                if (s.indexOf('blob:') === 0) return s;
                if (/\.(m3u8|mpd|flv|mp4|webm|ogg|ogv|ts|mp3|m4a|aac|wav)(\?|#|&|$)/i.test(s)) return s;
                return null;
              }
              // 在页面（含同源 iframe）里选面积最大的可播放 video
              function pickVideo() {
                var docs = [document], best = null, bestArea = -1;
                for (var i = 0; i < docs.length; i++) {
                  var d = docs[i];
                  var vs = d.querySelectorAll('video');
                  for (var j = 0; j < vs.length; j++) {
                    var v = vs[j];
                    if (!playableSrc(v)) continue;
                    var r = v.getBoundingClientRect();
                    var area = r.width * r.height;
                    if (area > bestArea) { bestArea = area; best = v; }
                  }
                  var fs = d.querySelectorAll('iframe');
                  for (var k = 0; k < fs.length; k++) {
                    try { if (fs[k].contentDocument) docs.push(fs[k].contentDocument); } catch (e) {}
                  }
                }
                if (best) return best;
                // 还没布局（面积 0）：退回第一个可播放的
                for (var i2 = 0; i2 < docs.length; i2++) {
                  var vs2 = docs[i2].querySelectorAll('video');
                  for (var j2 = 0; j2 < vs2.length; j2++) {
                    if (playableSrc(vs2[j2])) return vs2[j2];
                  }
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
                  try {
                    var p = v.play();
                    if (p && p.catch) p.catch(function () {});
                  } catch (e) {}
                  v.__tvPlayTimer = setTimeout(step, 100);
                };
                step();
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
              }
              var v = pickVideo();
              if (!v) return '0';
              var cur = window.__tvFs;
              if (cur && cur.el === v && cur.wrap && cur.wrap.parentNode) {
                applyStyles(v);
                // 播放器可能把 video 暂停了：每轮都确保在播
                tryPlay(v);
                return '1';
              }

              // 页面重渲染换掉了 video 元素：把旧的放回，换第一个进全屏容器
              if (cur) {
                try {
                  if (cur.el) cur.el.style.cssText = cur.css || '';
                  if (cur.el && cur.parent) {
                    if (cur.next && cur.next.parentNode === cur.parent) {
                      cur.parent.insertBefore(cur.el, cur.next);
                    } else {
                      cur.parent.appendChild(cur.el);
                    }
                  }
                  if (cur.wrap && cur.wrap.parentNode) {
                    cur.wrap.parentNode.removeChild(cur.wrap);
                  }
                } catch (e) {}
                window.__tvFs = null;
              }

              var wrap = document.createElement('div');
              wrap.setAttribute('data-tvfs', '1');
              wrap.style.cssText = 'position:fixed;left:0;top:0;width:100vw;height:100vh;' +
                'background:#000;z-index:2147483647;display:flex;align-items:center;' +
                'justify-content:center;overflow:hidden;';

              var state = { el: v, parent: v.parentNode, next: v.nextSibling, css: v.style.cssText };
              try {
                wrap.appendChild(v);
                applyStyles(v);
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

        // 全屏容器里的视频是否真的出画面了（首帧呈现 / 兜底播发状态）
        val FRAME_READY_JS = """
            (function () {
              var s = window.__tvFs;
              var v = s && s.el ? s.el : null;
              if (!v) return '0';
              if (v.requestVideoFrameCallback) {
                if (!v.__tvFrameGot) {
                  try {
                    v.requestVideoFrameCallback(function () { v.__tvFrameGot = 1; });
                  } catch (e) {}
                }
                if (v.__tvFrameGot) return '1';
              }
              if (v.videoWidth > 0 && v.readyState >= 2 && !v.paused) return '1';
              return '0';
            })();
        """

        // 退出 CSS 全屏：视频放回原位置、恢复原样式
        val EXIT_FS_JS = """
            (function () {
              var s = window.__tvFs;
              if (!s) return '0';
              window.__tvFs = null;
              try {
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

        // 诊断：报告页面 URL / 文字 / iframe / 深层 video 状态，并试一次全屏看返回结果
        val PROBE_JS = """
            (function () {
              var out = {
                url: location.href, n: 0, vids: [], iframes: [], text: '', tc: '',
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
                var fs = document.querySelectorAll('iframe');
                for (var k = 0; k < fs.length; k++) {
                  out.iframes.push((fs[k].getAttribute('src') || '').slice(0, 100));
                }
                var docs = [document];
                for (var i = 0; i < docs.length; i++) {
                  var d = docs[i];
                  var vs = d.querySelectorAll('video');
                  for (var j = 0; j < vs.length; j++) {
                    var v = vs[j];
                    out.n++;
                    out.vids.push({
                      src: (v.getAttribute('src') || v.src || '').slice(0, 48),
                      paused: v.paused, w: v.videoWidth,
                      wk: typeof v.webkitEnterFullscreen, rs: typeof v.requestFullscreen
                    });
                  }
                  var fr = d.querySelectorAll('iframe');
                  for (var m = 0; m < fr.length; m++) {
                    try { if (fr[m].contentDocument) docs.push(fr[m].contentDocument); } catch (e) {}
                  }
                }
              } catch (e) { out.err = '' + e; }
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
