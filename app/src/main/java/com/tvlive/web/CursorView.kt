package com.tvlive.web

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View

/**
 * 遥控光标：一个悬浮在登录 WebView 之上的指示点。
 * 手机是鼠标（只发相对位移 + 行为），光标位置即点击落点，与 WebView 同坐标系（都是全屏 root）。
 */
class CursorView(context: Context) : View(context) {

    private val density = resources.displayMetrics.density
    private var cx = 0f
    private var cy = 0f
    private var initialized = false

    /** 阴影圈：浅底页面（白背景、白输入框）上把光标压出来 */
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x80000000.toInt()
        style = Paint.Style.FILL
    }
    /** 半透明白底：深底视频画面上也留得下一块亮区 */
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x40FFFFFF
        style = Paint.Style.FILL
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    /** 中心实心点：真正的点击落点 */
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    init {
        isClickable = false
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (!initialized) {
            cx = w / 2f
            cy = h / 2f
            initialized = true
        }
    }

    /** 按相对位移移动光标，钳制在屏幕内 */
    fun moveBy(dx: Float, dy: Float) {
        cx = (cx + dx).coerceIn(0f, width.toFloat())
        cy = (cy + dy).coerceIn(0f, height.toFloat())
        invalidate()
    }

    fun curX(): Float = cx
    fun curY(): Float = cy

    override fun onDraw(canvas: Canvas) {
        val r = 15f * density
        canvas.drawCircle(cx, cy, r + 3f * density, shadow)
        canvas.drawCircle(cx, cy, r, fill)
        canvas.drawCircle(cx, cy, r, ring)
        canvas.drawCircle(cx, cy, 3.5f * density, dot)
    }

    // 本层不吃任何触摸，全部交给底下的 WebView（触屏调试时也能直接点页面）
    override fun onTouchEvent(event: MotionEvent): Boolean = false
}
