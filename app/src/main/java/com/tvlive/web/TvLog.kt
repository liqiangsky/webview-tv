package com.tvlive.web

import android.util.Log

/**
 * 运行日志：写 logcat 的同时留一份在内存里，配置网页上的「日志」按钮能直接看。
 *
 * 只留最近 [MAX] 行（环形），单行截到 [MAX_LINE] 字符 —— 手机上看的是一屏，
 * 留太多只会拖慢页面也不会有人翻到底。进程退出即丢弃，不落盘。
 */
object TvLog {

    private const val MAX = 400
    private const val MAX_LINE = 300

    private val lines = ArrayDeque<String>(MAX)
    private val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    @Synchronized
    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        append('D', msg)
    }

    @Synchronized
    fun w(tag: String, msg: String) {
        Log.w(tag, msg)
        append('W', msg)
    }

    @Synchronized
    fun e(tag: String, msg: String) {
        Log.e(tag, msg)
        append('E', msg)
    }

    /** 只进内存不写 logcat（页面 console 这类本来就由调用方决定要不要打） */
    @Synchronized
    fun record(msg: String) = append('D', msg)

    @Synchronized
    fun recent(limit: Int = MAX): List<String> {
        if (lines.isEmpty()) return emptyList()
        val n = limit.coerceIn(1, lines.size)
        return lines.toList().takeLast(n)
    }

    @Synchronized
    fun clear() = lines.clear()

    private fun append(level: Char, msg: String) {
        val body = if (msg.length > MAX_LINE) msg.take(MAX_LINE) + "…" else msg
        lines.addLast(stamp.format(java.util.Date()) + " " + level + " " + body)
        while (lines.size > MAX) lines.removeFirst()
    }
}
