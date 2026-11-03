package com.tvlive.web

/**
 * 频道与分组：line 是频道在 channels.txt 里的行号，用作“正在播放”的稳定标识。
 * 面板浏览状态与播放状态都靠它对齐，所以重新落盘后行号会变，切台需重新取值。
 */
data class Channel(
    val name: String,
    val url: String,
    val line: Int,
    val group: String,
    val logo: String,
    val quality: String
)

data class Group(val name: String, val channels: List<Channel>)

/** 登录网址条目：电视端只负责打开它（手机遥控当鼠标），增删改都在配置网页做 */
data class LoginEntry(val name: String, val url: String)
