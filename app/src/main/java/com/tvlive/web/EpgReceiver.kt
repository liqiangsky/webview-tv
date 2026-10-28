package com.tvlive.web

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 节目单更新闹钟（每天 00:25 附近的窗口）与开机补排。
 * 广播接收器只有十秒左右寿命，所以 goAsync 挂住结果，等线程池里的下载/解析真正跑完再 finish。
 */
class EpgReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "epg: trigger ${intent.action}")
        val pending = goAsync()
        EpgManager.refreshNow(context.applicationContext) { pending.finish() }
    }

    private companion object {
        const val TAG = "TvWeb"
    }
}
