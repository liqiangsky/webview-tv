package com.tvlive.web

import android.content.Context
import java.io.File

/**
 * 网页 JS 的本地缓存（播放页 SERVE_FROM_SCRIPT_CACHE 那条链路写下来的文件）。
 *
 * 目录是 cache/<CACHE_DIR>/<频道域名>/<md5>.js|.meta，一个频道的脚本不管来自几个 CDN
 * 都落在同一个目录里；这里只做两件事：给配置页列出各频道的占用、按域名清或整清。
 * 写缓存那条链路在 MainActivity，不经这里，所以清完下次进台自然重新下载。
 */
object ScriptCache {

    private fun root(context: Context) = File(context.cacheDir, MainActivity.CACHE_DIR)

    /** 每个域名的条目数、占用字节、最近一次使用时间 */
    data class DomainStat(val host: String, val count: Int, val size: Long, val last: Long)

    fun stats(context: Context): List<DomainStat> {
        val out = ArrayList<DomainStat>()
        for (dir in root(context).listFiles()?.filter { it.isDirectory }.orEmpty()) {
            var count = 0
            var size = 0L
            var last = 0L
            for (f in dir.listFiles().orEmpty()) {
                if (!f.isFile) continue
                if (f.name.endsWith(".js")) {
                    count++
                    size += f.length()
                }
                if (f.lastModified() > last) last = f.lastModified()
            }
            if (count == 0 && size == 0L) continue
            out.add(DomainStat(dir.name, count, size, last))
        }
        // 最近用过的排前面：找「要清哪个台」时看的是时间，不是体积
        return out.sortedWith(compareByDescending<DomainStat> { it.last }.thenByDescending { it.size })
    }

    fun total(stats: List<DomainStat>): Long = stats.sumOf { it.size }

    /** host 为空就是整个清掉；返回是否真的删了东西 */
    fun clear(context: Context, host: String): Boolean {
        val root = root(context)
        return if (host.isBlank()) root.deleteRecursively()
        else File(root, host).deleteRecursively()
    }
}
