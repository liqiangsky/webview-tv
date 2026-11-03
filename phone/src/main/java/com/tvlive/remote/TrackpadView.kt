package com.tvlive.remote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * 触控板：手机 = 鼠标。只产生相对位移和动作，不带任何屏幕坐标。
 * - 单指滑动：光标移动（累计到 ~40ms 合并发送一次）
 * - 单指轻点：单击；300ms 内二连点：双击
 * - 单指按住 400ms 不滑：进入拖动（DOWN），继续滑动 = 拖，抬手 = UP
 * - 双指滑动：滚轮
 */
class TrackpadView(context: Context) : View(context) {

    interface Listener {
        fun onMove(dx: Float, dy: Float)
        fun onTap()
        fun onDoubleTap()
        fun onDragStart()
        fun onDragEnd()
        fun onScroll(dx: Float, dy: Float)
    }

    var listener: Listener? = null

    private val density = resources.displayMetrics.density
    private val slop = 12f * density
    private val main = Handler(Looper.getMainLooper())

    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var moved = false
    private var dragging = false
    private var multiFinger = false
    private var lastMidX = 0f
    private var lastMidY = 0f

    private var pendDx = 0f
    private var pendDy = 0f
    private var flushAt = 0L
    private var lastUpTime = 0L

    private val dragDetector = Runnable {
        if (!moved && !multiFinger) {
            dragging = true
            listener?.onDragStart()
        }
    }

    private val flush = Runnable {
        flushAt = 0L
        val dx = pendDx; val dy = pendDy
        pendDx = 0f; pendDy = 0f
        if (dx != 0f || dy != 0f) listener?.onMove(dx, dy)
    }

    private val bg = Paint().apply { color = 0xFFF2F3F5.toInt() }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA0A6.toInt()
        textSize = 13f * density
        textAlign = Paint.Align.CENTER
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF2B7FFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, height.toFloat(), width.toFloat(), height.toFloat(), bg)
        val r = RectF(2f, 2f, width - 2f, height - 2f)
        canvas.drawRoundRect(r, 12f * density, 12f * density, ring)
        val text = when {
            dragging -> "拖 动 中 · 抬手结束"
            multiFinger -> "滚 动"
            else -> "单指移动 · 轻点单击 · 长按拖动 · 双指滚动"
        }
        canvas.drawText(text, width / 2f, height / 2f, hint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x; lastY = event.y
                downX = event.x; downY = event.y
                downTime = event.downTime
                moved = false; dragging = false; multiFinger = false
                main.postDelayed(dragDetector, 400)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount == 2) {
                    multiFinger = true
                    main.removeCallbacks(dragDetector)
                    if (dragging) { dragging = false; listener?.onDragEnd() }
                    flushNow()
                    lastMidX = (event.getX(0) + event.getX(1)) / 2f
                    lastMidY = (event.getY(0) + event.getY(1)) / 2f
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (multiFinger && event.pointerCount >= 2) {
                    val midX = (event.getX(0) + event.getX(1)) / 2f
                    val midY = (event.getY(0) + event.getY(1)) / 2f
                    val dyDp = (lastMidY - midY) / density // 手指上滑 = 页面向下滚
                    val dxDp = (midX - lastMidX) / density // 手指右滑 = 页面向右滚
                    lastMidX = midX
                    lastMidY = midY
                    if (abs(dyDp) > 0.5f || abs(dxDp) > 0.5f)
                        listener?.onScroll(dxDp * SCROLL_STEP, dyDp * SCROLL_STEP)
                    invalidate()
                } else if (event.pointerCount == 1) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    lastX = event.x; lastY = event.y
                    if (!moved && hypot(event.x - downX, event.y - downY) > slop) {
                        moved = true
                        main.removeCallbacks(dragDetector)
                        main.removeCallbacks(flush)
                    }
                    if (moved) {
                        pendDx += dx / density
                        pendDy += dy / density
                        scheduleFlush()
                        if (dragging) invalidate()
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 双指里抬起一根：回到单指模式，剩余手指重新定位避免跳变
                if (event.pointerCount <= 2) {
                    val keep = if (event.actionIndex == 0) 1 else 0
                    lastX = event.getX(keep)
                    lastY = event.getY(keep)
                    multiFinger = false
                    invalidate()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(dragDetector)
                flushNow()
                val dt = event.eventTime - downTime
                when {
                    dragging -> { dragging = false; listener?.onDragEnd() }
                    event.actionMasked == MotionEvent.ACTION_UP && !moved && dt < 300 -> {
                        val now = System.currentTimeMillis()
                        if (now - lastUpTime < 300) {
                            listener?.onDoubleTap()
                            lastUpTime = 0
                        } else {
                            listener?.onTap()
                            lastUpTime = now
                        }
                    }
                }
                invalidate()
            }
        }
        return true
    }

    private fun scheduleFlush() {
        if (flushAt == 0L) {
            flushAt = android.os.SystemClock.uptimeMillis() + FLUSH_MS
            main.postDelayed(flush, FLUSH_MS)
        }
    }

    private fun flushNow() {
        main.removeCallbacks(flush)
        flushAt = 0L
        val dx = pendDx; val dy = pendDy
        pendDx = 0f; pendDy = 0f
        if (dx != 0f || dy != 0f) listener?.onMove(dx, dy)
    }

    private fun hypot(a: Float, b: Float) = kotlin.math.sqrt(a * a + b * b)

    private companion object {
        const val FLUSH_MS = 40L
        const val SCROLL_STEP = 0.35f
    }
}
