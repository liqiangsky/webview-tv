package com.tvlive.web

import android.app.Application

/**
 * 应用级装配：频道数据 + 配置服务常驻。
 *
 * 配置服务挂在 Application 而不是某个 Activity，因为手机浏览器随时可能来连：
 * 电视停在播放页、开着登录页、甚至屏幕刚点亮，网页都应该打得开。
 * Activity 只在前台时把自己注册成 host，负责“切台”“打开登录页”这类要落到界面上的动作；
 * 没有 host 时纯数据的增删改照样能成功。
 *
 * 系统 WebView（Chromium）是唯一内核。
 * 设备上没有可用的腾讯 X5 核心（宿主不再共享、公共核心也不下发），所以不引 tbssdk，
 * 免得内核归属变成运行时才知道的事。
 */
class TvApp : Application() {

    lateinit var config: ConfigServer
        private set

    /** 当前在台上、能执行界面动作的 Activity */
    var host: ConfigServer.ConfigHost? = null

    /** 遥控指令的去向：只有登录页在前台时挂着，不在就是没人能收 */
    @Volatile var remoteSink: RemoteSink? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ChannelStore.init(this)
        config = ConfigServer(
            CONFIG_PORT, this,
            hostProvider = { host },
            remoteProvider = { remoteSink },
            pinProvider = { ChannelStore.pin }
        )
        config.start()
        // 节目单：先读本地那份，过期再拉新的，顺带排好每天 00:25 的闹钟
        EpgManager.init(this)
    }

    companion object {
        /** 配置网页端口：和遥控触控板的 8721 分开，互不影响 */
        const val CONFIG_PORT = 8723
        lateinit var instance: TvApp
            private set
    }
}
