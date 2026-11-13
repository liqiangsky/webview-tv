package com.tvlive.web

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/**
 * XMLTV 节目单：源每天 00:25 左右更新，闹钟到点拉一次 gz，过期/失败时沿用上一份。
 * 内存里只留“昨天 ~ 未来 26 小时”的切片，查询按频道名归一化后二分。
 */
object EpgManager {
    private const val TAG = "TvWeb"

    // 第一个是用户指定源（每天更新，覆盖 今天 ~ 后天）。
    // 前面的文件覆盖不到当前时间时，依次往后试公开镜像，直到拿到有当期节目的那份。
    private val SOURCES = listOf(
        "https://epg.zsdc.eu.org/t.xml.gz",
        "https://epg.erw.cc/e.xml.gz",
        "https://raw.githubusercontent.com/kuke31/xmlgz/main/e.xml.gz",
        "https://live.fanmingming.com/e.xml",
        "http://epg.112114.xyz/pp.xml.gz"
    )

    private const val UPDATE_HOUR = 0
    private const val UPDATE_MIN = 25
    private const val FETCH_AGAIN = 6 * 3600_000L
    private const val WINDOW = 30 * 60_000L
    private const val KEEP_BACK = 36 * 3600_000L
    private const val KEEP_AHEAD = 26 * 3600_000L

    data class Program(val start: Long, val stop: Long, val title: String)

    /** 节目单进度（标题为空表示该频道没对上节目单）；下一档的开播时间由界面自己从 next 取 */
    class Guide(
        val now: Program?, val next: Program?, val total: Int,
        val nowText: String, val timeText: String, val percent: Int
    )

    private val main = Handler(Looper.getMainLooper())
    private val timeFmt = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US)
    private val hhmm = SimpleDateFormat("HH:mm", Locale.US)
    private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    private lateinit var app: Context
    private lateinit var work: ExecutorService

    @Volatile private var schedule: Map<String, List<Program>> = emptyMap()
    @Volatile private var keys: Map<String, String> = emptyMap()
    @Volatile private var coverageEnd = 0L
    @Volatile private var fetching = false

    /** 解析成功过一份文件（哪怕当前时刻没有节目） */
    @Volatile var loaded = false
        private set

    /** 本轮启动至少试过一个源 */
    @Volatile var attempted = false
        private set

    /** 真正供给了当期节目的源，界面/日志用 */
    @Volatile var source: String? = null
        private set

    val coverageText: String get() =
        if (coverageEnd == 0L) "" else dayFmt.format(Date(coverageEnd))

    /** 数据换过后回主线程通知，界面重画 */
    var onChanged: (() -> Unit)? = null

    // ---------------- 节目源管理（配置页） ----------------

    // 用户自己加的源存在这里，排在内置源前面先试；内置的都是公开镜像，不给删
    private const val KEY_SOURCES = "custom"

    private val prefs get() = app.getSharedPreferences("epg_source", Context.MODE_PRIVATE)

    fun customSources(): List<String> =
        prefs.getString(KEY_SOURCES, "").orEmpty().split('\n')
            .map { it.trim() }.filter { it.isNotEmpty() }

    fun builtinSources(): List<String> = SOURCES

    /** 实际尝试顺序：自定义的在前，内置的在后（去重） */
    private fun allSources(): List<String> = (customSources() + SOURCES).distinct()

    /** 加一个自定义源；地址不合法或已经存在就返回 false */
    fun addSource(url: String): Boolean {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val list = customSources()
        if (list.contains(u)) return false
        prefs.edit().putString(KEY_SOURCES, (list + u).joinToString("\n")).apply()
        return true
    }

    fun removeSource(url: String): Boolean {
        val list = customSources()
        if (!list.contains(url)) return false
        prefs.edit().putString(KEY_SOURCES, list.filter { it != url }.joinToString("\n")).apply()
        return true
    }

    /** 当前状态：配置页那几行字都用它 */
    data class Status(
        val loaded: Boolean, val attempted: Boolean, val source: String?, val coverage: String,
        val channels: Int, val names: Int, val stamp: Long, val fetching: Boolean
    )

    fun status(): Status = Status(
        loaded, attempted, source, coverageText, schedule.size, keys.size, lastStamp(), fetching
    )

    private fun lastStamp(): Long =
        runCatching { File(dir, "stamp.txt").readText().trim().toLong() }.getOrDefault(0L)

    /** 这个频道名对没对上节目单 */
    fun hasGuide(name: String): Boolean = !schedule[lookupKey(name)].isNullOrEmpty()

    fun init(context: Context) {
        if (!::app.isInitialized) {
            app = context.applicationContext
            work = Executors.newSingleThreadExecutor { r -> Thread(r, "epg").apply { isDaemon = true } }
        }
        work.execute {
            parse()
            if (needFetch()) fetch()
            scheduleNextUpdate()
        }
    }

    /** 闹钟/开机回调：立即拉一次，跑完（成功失败都要）再排下一天的闹钟 */
    fun refreshNow(context: Context, onDone: () -> Unit) {
        init(context)
        work.execute {
            runCatching { fetch() }
            runCatching { scheduleNextUpdate() }
            onDone()
        }
    }

    private val dir get() = File(app.filesDir, "epg")
    private val target get() = File(dir, "tvxml.xml.gz")

    private fun needFetch(): Boolean {
        val stamp = runCatching { File(dir, "stamp.txt").readText().toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (stamp == 0L) return true
        val age = System.currentTimeMillis() - stamp
        if (age > FETCH_AGAIN) return true
        // 最后一天都播完了属于真过期，补拉一次；但最多每小时一次，别每次启动都重下
        return coverageEnd < System.currentTimeMillis() && age > 3600_000L
    }

    private fun fetch(): Boolean {
        if (fetching) return false
        fetching = true
        try {
            for (url in allSources()) {
                if (!download(url)) continue
                runCatching { File(dir, "stamp.txt").writeText(System.currentTimeMillis().toString()) }
                parse()
                attempted = true
                // 停更的源也会“下载成功”，但最后一天早已播完：换下一个源，别当成有节目单
                if (coverageEnd <= System.currentTimeMillis()) {
                    Log.d(TAG, "epg: nothing around now from $url (ends $coverageText)")
                    continue
                }
                source = url
                // 记下来：重启后日志里“这份数据是哪个源来的”还能说清楚
                runCatching { File(dir, "source.txt").writeText(url) }
                main.post { onChanged?.invoke() }
                return true
            }
            Log.d(TAG, "epg: no source has current schedule, keep the last good copy")
            return false
        } finally {
            fetching = false
        }
    }

    /** 只认 gzip 响应：拿到 HTML（登录页/验证页）就当失败，绝不覆盖旧数据 */
    private fun download(url: String): Boolean {
        val tmp = File(dir, "tvxml.tmp")
        var saved = false
        try {
            dir.mkdirs()
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
            }
            try {
                if (conn.responseCode != 200) {
                    Log.d(TAG, "epg: http ${conn.responseCode} from $url")
                    return false
                }
                conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out) } }
            } finally {
                conn.disconnect()
            }
            if (!tmp.gzipped() || tmp.length() < 4096) {
                Log.d(TAG, "epg: not a gzip payload (${tmp.length()} bytes) from $url")
                return false
            }
            val dst = target
            saved = true
            if (!tmp.renameTo(dst)) {
                tmp.copyTo(dst, overwrite = true)
            }
            Log.d(TAG, "epg: updated from $url (${dst.length()} bytes)")
            return true
        } catch (e: Exception) {
            Log.d(TAG, "epg: fetch failed ${e.javaClass.simpleName} ${e.message}")
            return false
        } finally {
            if (!saved) tmp.delete()
        }
    }

    private fun parse() {
        val src = target
        if (!src.exists()) {
            schedule = emptyMap(); keys = emptyMap(); coverageEnd = 0L; loaded = false
            return
        }
        try {
            val progs = HashMap<String, MutableList<Program>>()
            val index = HashMap<String, String>()
            var channelKey: String? = null
            var progKey: String? = null
            var startAt = 0L
            var stopAt = 0L
            var end = 0L
            val now = System.currentTimeMillis()
            BufferedInputStream(FileInputStream(src), 1 shl 16).use { bin ->
                val body = if (bin.gzipped()) GZIPInputStream(bin) else bin
                val parser = XmlPullParserFactory.newInstance().newPullParser()
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                parser.setInput(body, "UTF-8")
                var event = parser.eventType
                while (event != XmlPullParser.END_DOCUMENT) {
                    if (event == XmlPullParser.START_TAG) {
                        when (parser.name) {
                            "channel" -> channelKey = parser.getAttributeValue(null, "id")?.let(::norm)
                            "display-name" -> {
                                val text = parser.nextText()
                                val key = channelKey
                                if (key != null) index.putIfAbsent(norm(text), key)
                            }
                            "programme" -> {
                                progKey = parser.getAttributeValue(null, "channel")?.let(::norm)
                                startAt = at(parser.getAttributeValue(null, "start"))
                                stopAt = at(parser.getAttributeValue(null, "stop"))
                                if (stopAt > end) end = stopAt
                            }
                            "title" -> {
                                val key = progKey
                                if (key != null && stopAt >= now - KEEP_BACK && startAt <= now + KEEP_AHEAD) {
                                    progs.getOrPut(key) { ArrayList() } += Program(startAt, stopAt, parser.nextText().trim())
                                }
                            }
                        }
                    }
                    event = parser.next()
                }
            }
            progs.values.forEach { it.sortBy { p -> p.start } }
            keys = index
            coverageEnd = end
            schedule = progs
            loaded = true
            if (source == null) {
                source = runCatching { File(dir, "source.txt").readText().trim() }.getOrNull()
                    ?.ifEmpty { null }
            }
            Log.d(TAG, "epg: loaded channels=${progs.size} names=${index.size}")
        } catch (e: Exception) {
            Log.d(TAG, "epg: parse failed ${e.javaClass.simpleName} ${e.message}")
            loaded = false
        }
    }

    fun guideFor(name: String): Guide {
        val list = schedule[lookupKey(name)]
        if (list.isNullOrEmpty()) return Guide(null, null, 0, "节目单未收录", "--:-- ~ --:--", 0)
        val now = System.currentTimeMillis()
        var lo = 0
        var hi = list.size - 1
        var hit = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].stop > now) { hit = mid; hi = mid - 1 } else lo = mid + 1
        }
        if (hit < 0) return Guide(null, null, list.size, "节目单已结束", "--:-- ~ --:--", 0)
        val first = list[hit]
        val cur = if (first.start > now) null else first
        val next = if (cur == null) first else list.getOrNull(hit + 1)
        val pct = if (cur == null) 0 else {
            val span = cur.stop - cur.start
            (((now - cur.start) * 100) / if (span > 0) span else 1).coerceIn(0, 100).toInt()
        }
        return Guide(
            cur, next, list.size,
            cur?.title ?: "即将开始",
            if (cur == null) "--:-- ~ --:--" else "${hhmm.format(cur.start)} ~ ${hhmm.format(cur.stop)}",
            pct
        )
    }

    fun ready(): Boolean = schedule.isNotEmpty()

    private fun lookupKey(name: String): String? {
        val n = norm(name)
        keys[n]?.let { return it }
        if (schedule.containsKey(n)) return n
        val loose = stripTail(n)
        if (loose != n) {
            keys[loose]?.let { return it }
            if (schedule.containsKey(loose)) return loose
        }
        return null
    }

    private fun scheduleNextUpdate() {
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, UPDATE_HOUR)
            set(Calendar.MINUTE, UPDATE_MIN)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        val pi = PendingIntent.getBroadcast(
            app, 0, Intent(app, EpgReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        runCatching { am.setWindow(AlarmManager.RTC_WAKEUP, next, WINDOW, pi) }
            .onFailure { Log.d(TAG, "epg: alarm not scheduled ${it.javaClass.simpleName} ${it.message}") }
        Log.d(TAG, "epg: next update in ${(next - System.currentTimeMillis()) / 60000} min")
    }

    private fun at(text: String?): Long =
        if (text.isNullOrBlank()) 0L else runCatching { timeFmt.parse(text.trim())?.time ?: 0L }.getOrDefault(0L)

    private fun File.gzipped(): Boolean = runCatching {
        FileInputStream(this).use { it.read() == 0x1f && it.read() == 0x8b }
    }.getOrDefault(false)

    private fun InputStream.gzipped(): Boolean = runCatching {
        if (!markSupported()) return false
        mark(2)
        val a = read(); val b = read()
        reset()
        a == 0x1f && b == 0x8b
    }.getOrDefault(false)

    private fun norm(text: String): String {
        val sb = StringBuilder(text.length)
        for (c in text) {
            val low = c.lowercaseChar()
            if (low.isLetterOrDigit()) sb.append(low)
        }
        return sb.toString()
    }

    /** 名字里的小尾巴在节目单里通常不带，退一步再匹配一次 */
    private fun stripTail(text: String): String {
        val tails = listOf("高清", "超清", "标清", "频道", "hd", "sd", "4k", "8k")
        var t = text
        while (true) {
            val hit = tails.firstOrNull { t.length > it.length && t.endsWith(it) } ?: return t
            t = t.removeSuffix(hit)
        }
    }
}
