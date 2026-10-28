package com.tvlive.web

import android.app.Application

/**
 * 系统 WebView（Chromium）是唯一内核。
 * 设备上没有可用的腾讯 X5 核心（宿主不再共享、公共核心也不下发），所以不引 tbssdk，
 * 免得内核归属变成运行时才知道的事。
 */
class TvApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // 节目单：先读本地那份，过期再拉新的，顺带排好每天 00:25 的闹钟
        EpgManager.init(this)
    }
}
