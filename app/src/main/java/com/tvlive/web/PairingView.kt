package com.tvlive.web

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 配对页：把配置网址画成二维码给手机扫，同时显示明文地址和 4 位配对码。
 *
 * 两种进法：一个频道都没有时自动顶上（否则用户开机对着黑屏无从下手），
 * 以及面板里的「设置 → 配对二维码」主动打开。
 * 二维码由 zxing 本地算成 Bitmap，不走任何出图接口，配置地址不会离开内网。
 *
 * 视觉按 tvOS 那套：纯黑底、大字号标题、次级文字压暗、主操作是白色胶囊。
 * 横屏电视矮，二维码和文字并排成一行，竖直堆叠会顶出屏幕。
 */
class PairingView(context: Context) : FrameLayout(context) {

    private val qrImage = ImageView(context)
    private val urlText = label(22f, Color.WHITE, mono = true)
    private val pinText = label(18f, 0xFF0A84FF.toInt(), mono = true)
    private val ipWarning = label(13f, 0xFFFF453A.toInt())

    /** 重新生成配对码：按遥控器 OK 触发，之后旧手机要重新输码 */
    var onRegenerate: (() -> Unit)? = null

    init {
        setBackgroundColor(Color.BLACK)
        isFocusable = true

        // 白卡 + 圆角，把二维码包起来：黑底上直接放黑码会显得脏，白卡也充当扫码对比度
        val qrCard = FrameLayout(context).apply {
            background = pill(0xFFFFFFFF.toInt(), dp(18))
            addView(qrImage, LayoutParams(dp(150), dp(150), Gravity.CENTER))
        }

        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(label(28f, Color.WHITE).apply {
                text = "手机配置"
                letterSpacing = -0.02f
            }, lpWrap(0, 0))
            addView(label(13f, 0xFF8E8E93.toInt()).apply {
                text = "添加频道 · 管理登录网址 · 远程切台"
            }, lpWrap(0, dp(14)))
            addView(urlText, lpWrap(0, dp(2)))
            addView(pinText, lpWrap(0, dp(2)))
            addView(ipWarning, lpWrap(0, dp(2)))
            addView(label(12f, 0xFF8E8E93.toInt()).apply {
                text = "手机浏览器扫码或照地址输入，在电视上登录网站请另装「电视遥控」App"
                setLineSpacing(0f, 1.3f)
                maxWidth = dp(360)
            }, lpWrap(dp(10), 0))
            // 主操作和退出提示并排：竖着排又要多占两行
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = "换一组配对码"
                    setTextColor(Color.BLACK)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    gravity = Gravity.CENTER
                    setPadding(dp(22), dp(9), dp(22), dp(9))
                    background = pill(Color.WHITE, dp(980))
                    isFocusable = true
                    // tvOS 的焦点语言：聚焦项提亮放大，而不是画边框
                    setOnFocusChangeListener { v, has ->
                        val s = if (has) 1.06f else 1f
                        v.scaleX = s
                        v.scaleY = s
                        v.alpha = if (has) 1f else 0.72f
                    }
                    setOnClickListener { onRegenerate?.invoke() }
                }, lpWrap(0, 0))
                addView(label(12f, 0xFF6C6C70.toInt()).apply {
                    text = "按返回键关闭"
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(16) })
            }, lpWrap(dp(16), 0))
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(20), dp(32), dp(20))
            addView(qrCard, LinearLayout.LayoutParams(dp(174), dp(174)))
            addView(
                texts,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(30) }
            )
        }

        addView(
            ScrollView(context).apply {
                addView(row, LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                ))
                isVerticalScrollBarEnabled = false
                overScrollMode = OVER_SCROLL_NEVER
            },
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER)
        )
        visibility = GONE
    }

    /** 刷新地址与配对码；电视换过网络后 IP 会变，这里跟着重画一次 */
    fun refresh(url: String?, pin: String) {
        urlText.text = url?.removePrefix("http://") ?: "未联网：连上 WiFi 后再试"
        pinText.text = "配对码  $pin"
        ipWarning.text = if (url == null) "" else "手机要和电视连同一个网络"
        // 只有真取不到地址才是报错，平时这句只是提示，别用红色吓人
        ipWarning.setTextColor(if (url == null) 0xFFFF453A.toInt() else 0xFF8E8E93.toInt())
        url?.let { qrImage.setImageBitmap(Qr.bitmap(it, 480)) }
    }

    private fun lpWrap(top: Int, bottom: Int) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = top; bottomMargin = bottom }

    private fun pill(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(sp: Float, color: Int, mono: Boolean = false) = TextView(context).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (mono) setTypeface(android.graphics.Typeface.MONOSPACE)
    }
}
