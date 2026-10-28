package com.tvlive.web

import android.content.Context
import android.graphics.drawable.Animatable
import android.util.AttributeSet
import android.widget.ImageView

/**
 * 声波角标控件：AnimatedVectorDrawable 自己不会开播，这里把生命周期绑到可见性上——
 * 面板露出来就播、收起来就停，外部不用再管 animator 的启停
 */
class BadgeIconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : ImageView(context, attrs, defStyle) {

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        play(isVisible)
    }

    fun play(on: Boolean) {
        (drawable as? Animatable)?.let { if (on) it.start() else it.stop() }
    }
}
