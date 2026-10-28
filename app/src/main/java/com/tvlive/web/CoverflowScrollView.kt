package com.tvlive.web

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.HorizontalScrollView
import kotlin.math.abs

/**
 * Coverflow 横向滚动容器：
 * - 正中卡片：不透明 + 聚焦高亮描边（背景由外部切换），不放大避免越界裁剪
 * - 两侧卡片：按距离渐进降透明度（最远 ~50%）+ 高斯模糊（API31+；低版本只降透明度），
 *   明确暗示“两边还能横滚”
 * - 手指拖动 / 遥控左右键 / fling 后都自动吸附（Snap）到最近卡片居中
 */
class CoverflowScrollView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : HorizontalScrollView(context, attrs, defStyle) {

    /** fling 后由自身接管吸附，抬手时不再重复 snap */
    private var handledFling = false

    /** 手势起始卡片：一次拖拽最多切换一个分组，避免快速滑动连跳好几个 */
    private var touchStartIndex = -1

    /** 当前选中的分组卡片：始终全亮（即使焦点已下沉到卡片内的频道行） */
    var selectedIndex = -1
        set(value) {
            field = value
            if (width > 0) post { applyDepth() }
        }

    /** 居中卡片变化回调：触屏滑动时没有焦点事件，外部靠它同步“当前分组” */
    var onCenterChanged: ((Int) -> Unit)? = null
    private var lastCenter = -1

    /** 记录每张卡片当前模糊半径，避免每帧重复 setRenderEffect */
    private val appliedBlur = HashMap<Int, Float>()

    private var centerAnim: ValueAnimator? = null

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
    }

    private fun container(): ViewGroup? = getChildAt(0) as? ViewGroup

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        applyDepth()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) post { applyDepth() }
    }

    /** 景深：中心最大最亮最清晰；越远越小、越暗、越模糊 */
    fun applyDepth() {
        val c = container() ?: return
        if (width == 0 || c.childCount == 0) return
        val center = scrollX + width / 2f
        for (i in 0 until c.childCount) {
            val ch = c.getChildAt(i)
            val cx = ch.left + ch.width / 2f
            val d = (abs(cx - center) / width.toFloat()).coerceIn(0f, 1f)
            var scale = 1f - 0.24f * d
            var alpha = 1f - 0.34f * d
            if (ch.isFocused || i == selectedIndex) {
                alpha = 1f
            }
            ch.pivotX = ch.width / 2f
            ch.pivotY = ch.height / 2f
            ch.scaleX = scale
            ch.scaleY = scale
            // 两侧卡片朝中心一侧补偿平移：贴近中心卡不留大缝，且过中线时平移量归零、滑动连续不跳变
            val nudge = (1f - scale) / 2f * ch.width
            ch.translationX = if (cx < center) nudge else -nudge
            ch.alpha = alpha
            ch.translationZ = (1f - d) * 4f
            applyBlur(i, ch, d)
        }
        val n = nearestIndex()
        if (n != lastCenter) {
            lastCenter = n
            onCenterChanged?.invoke(n)
        }
    }

    /** 两侧卡片高斯模糊（按距离渐进，最多 ~6dp），中心无模糊 */
    private fun applyBlur(index: Int, ch: android.view.View, depth: Float) {
        val radius = (depth * 6f - 0.6f).coerceAtLeast(0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val applied = appliedBlur[index] ?: -1f
            if (abs(applied - radius) < 0.5f) return
            appliedBlur[index] = radius
            if (radius <= 0f) {
                ch.setRenderEffect(null)
            } else {
                ch.setRenderEffect(
                    RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
                )
            }
        }
    }

    /** 平滑滚动到指定卡片居中：时长自己按距离算 */
    fun smoothScrollToCenter(index: Int) {
        val c = container() ?: return
        if (index !in 0 until c.childCount || width == 0) return
        val ch = c.getChildAt(index)
        val target = (ch.left + ch.width / 2 - width / 2)
            .coerceIn(0, (c.width - width).coerceAtLeast(0))
        scrollToAnimated(target)
    }

    /**
     * 内核的 smoothScrollTo 会把时长夹到 ~250ms，跨两三个分组（1600px+）时快得看不清，
     * 像直接瞬移过去；这里按距离给时长，让切换过程始终可见
     */
    private fun scrollToAnimated(target: Int) {
        if (target == scrollX) return
        centerAnim?.cancel()
        val distance = abs(target - scrollX)
        centerAnim = ValueAnimator.ofInt(scrollX, target).apply {
            duration = (220L + distance * 25L / 100L).coerceAtMost(600L)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { scrollTo(it.animatedValue as Int, 0) }
            start()
        }
    }

    private fun nearestIndex(): Int {
        val c = container() ?: return -1
        if (c.childCount == 0) return -1
        val center = scrollX + width / 2
        var best = 0
        var bestD = Int.MAX_VALUE
        for (i in 0 until c.childCount) {
            val ch = c.getChildAt(i)
            val d = abs(ch.left + ch.width / 2 - center)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    /** 吸附到最近卡片居中（不允许停在半中间）；吸附后居中标题卡自动聚焦，频道行不聚焦 */
    fun snapToNearest(animate: Boolean = true, maxMove: Int = Int.MAX_VALUE) {
        val c = container() ?: return
        var idx = nearestIndex()
        if (idx < 0) return
        if (touchStartIndex >= 0 && maxMove < Int.MAX_VALUE) {
            idx = idx.coerceIn(touchStartIndex - maxMove, touchStartIndex + maxMove)
        }
        if (animate) {
            smoothScrollToCenter(idx)
        } else {
            val ch = c.getChildAt(idx)
            scrollTo((ch.left + ch.width / 2 - width / 2).coerceAtLeast(0), 0)
        }
        c.getChildAt(idx)?.requestFocus()
    }

    override fun fling(velocityX: Int) {
        val dir = when {
            velocityX > 600 -> 1
            velocityX < -600 -> -1
            else -> 0
        }
        val max = (container()?.childCount ?: 1) - 1
        // 以手势起始卡为基准，一次 fling 最多切一个分组
        val base = if (touchStartIndex >= 0) touchStartIndex else nearestIndex()
        if (base < 0) return
        val target = (base + dir).coerceIn(0, max)
        handledFling = true
        smoothScrollToCenter(target)
        container()?.getChildAt(target)?.requestFocus()
    }

    /** 子 View（频道行/卡片）会先消费 DOWN，真正开始拖动时在这里补记起始卡 */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_MOVE && touchStartIndex < 0) {
            touchStartIndex = nearestIndex()
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            centerAnim?.cancel()
            touchStartIndex = nearestIndex()
        }
        val handled = super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!handledFling) snapToNearest(maxMove = 1)
                handledFling = false
                touchStartIndex = -1
            }
        }
        return handled
    }
}
