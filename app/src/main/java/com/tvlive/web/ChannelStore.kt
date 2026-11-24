package com.tvlive.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlin.random.Random

/**
 * 频道与登录网址的唯一数据源。
 *
 * 数据全部由配置网页（手机/浏览器）写入，电视端只读和展示，所以这里既负责
 * 落盘也负责解析/序列化。两个文件都在 getExternalFilesDir 下，卸载应用即清除，
 * adb 也能直接 push 调试：
 *   channels.txt —— DIYP txt 格式（“分组名,#genre#” + 每行“名称,地址[,台标,清晰度]”）
 *   logins.txt   —— 每行“名称,地址”
 *
 * 分组就是文件里的“分组名,#genre#”标题行，底下没有频道也照样成立，所以空分组
 * 存得下来，不必另加一张表。
 *
 * 出厂不带任何频道：首次运行两个文件都不存在，列表为空，引导用户去配置页添加。
 */
object ChannelStore {

    private const val CHANNEL_FILE = "channels.txt"
    private const val LOGIN_FILE = "logins.txt"
    private const val PREFS = "config"
    private const val KEY_PIN = "pin"
    private const val KEY_DEV = "dev"

    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    private lateinit var dir: File
    private lateinit var prefs: android.content.SharedPreferences

    /** 数据变更回调（主线程）：面板据此重画 */
    @Volatile var onChanged: (() -> Unit)? = null

    fun init(ctx: Context) {
        synchronized(lock) {
            if (::dir.isInitialized) return
            dir = ctx.getExternalFilesDir(null) ?: ctx.filesDir
            prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }
    }

    private fun channelsFile() = File(dir, CHANNEL_FILE)
    private fun loginFile() = File(dir, LOGIN_FILE)

    /** 分组顺序和频道一起读、一起写 */
    private class Db(val groups: MutableList<String>, val channels: MutableList<Channel>)

    private fun fail(msg: String): Result<Unit> = Result.failure(IllegalArgumentException(msg))

    // ---------------- 频道 ----------------

    fun channels(): List<Channel> = readDb().channels

    /** 分组以文件里的 #genre# 标题行为准，空分组也会出现在列表里 */
    fun groups(): List<Group> {
        val db = readDb()
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (name in db.groups) map[name] = mutableListOf()
        for (ch in db.channels) map.getOrPut(ch.group) { mutableListOf() }.add(ch)
        return map.map { Group(it.key, it.value) }
    }

    fun addGroup(name: String): Result<Unit> = mutateDb { db ->
        val n = name.trim()
        when {
            n.isEmpty() -> fail("分组名不能为空")
            db.groups.any { it == n } -> fail("分组已存在")
            else -> {
                db.groups += n
                Result.success(Unit)
            }
        }
    }

    fun addChannel(
        group: String, name: String, url: String, quality: String, source: String = ""
    ): Result<Unit> =
        mutateDb { db ->
            when {
                name.isBlank() || url.isBlank() -> fail("名称和地址不能为空")
                db.channels.any { it.name == name && it.url == url } -> fail("该频道已存在")
                else -> {
                    val g = group.ifBlank { "其他频道" }.trim()
                    if (db.groups.none { it == g }) db.groups += g
                    db.channels += Channel(name.trim(), url.trim(), -1, g, name.trim(),
                        quality.trim(), source.trim())
                    Result.success(Unit)
                }
            }
        }

    /** 按原行号定位（网页拿到的就是行号）；行号失效时退回“分组+名称+地址”匹配。空的新字段表示不改 */
    fun updateChannel(
        line: Int, group: String, name: String, url: String,
        newGroup: String, newName: String, newUrl: String, newQuality: String, newSource: String
    ): Result<Unit> = mutateDb { db ->
        val list = db.channels
        val i = indexOf(list, line, group, name, url)
        when {
            i < 0 -> fail("频道不存在，请刷新后重试")
            else -> {
                val old = list[i]
                val finalName = newName.ifBlank { old.name }.trim()
                val finalUrl = newUrl.ifBlank { old.url }.trim()
                if (finalName.isEmpty() || finalUrl.isEmpty()) return@mutateDb fail("名称和地址不能为空")
                val g = newGroup.ifBlank { old.group }.trim()
                if (db.groups.none { it == g }) db.groups += g
                list[i] = old.copy(
                    name = finalName, url = finalUrl,
                    group = g,
                    logo = if (newName.isBlank()) old.logo else finalName,
                    // 清晰度同样可以空：清空就真的清掉，面板上不显示
                    quality = newQuality.trim(),
                    // 来源是自由备注：清空就真的清掉，不沿用旧值
                    source = newSource.trim()
                )
                Result.success(Unit)
            }
        }
    }

    fun deleteChannel(line: Int, group: String, name: String, url: String): Result<Unit> =
        mutateDb { db ->
            val i = indexOf(db.channels, line, group, name, url)
            if (i < 0) fail("频道不存在，请刷新后重试")
            else {
                db.channels.removeAt(i)
                Result.success(Unit)
            }
        }

    /** 整段导入：append 追加到现有列表之后，replace 覆盖全部 */
    fun importChannels(text: String, replace: Boolean): Result<Int> {
        val parsed = parse(trimImportColumns(text))
        if (parsed.channels.isEmpty())
            return Result.failure(IllegalArgumentException("没有解析出任何频道"))
        return mutateDb { db ->
            if (replace) {
                db.groups.clear()
                db.channels.clear()
            }
            for (g in parsed.groups) if (db.groups.none { it == g }) db.groups += g
            val seen = db.channels.mapTo(HashSet()) { it.name + '\u0000' + it.url }
            var added = 0
            for (ch in parsed.channels) {
                if (seen.add(ch.name + '\u0000' + ch.url)) {
                    db.channels += ch
                    added++
                }
            }
            Result.success(added)
        }
    }

    /**
     * 导入只认“名称,地址”：后面就算还带着别名、清晰度，也一律当没有。
     * 补两个空列而不是直接截成两列，是为了让解析走到“台标缺省=名称、清晰度=空”，
     * 不会因为少列而被当成 HD。
     */
    private fun trimImportColumns(text: String): String =
        text.lineSequence().joinToString("\n") { raw ->
            val t = raw.trim()
            val parts = t.split(',')
            if (t.isEmpty() || t.startsWith("#") || parts.size < 2 ||
                parts[1].trim() == "#genre#"
            ) raw
            else parts[0] + "," + parts[1] + ",,"
        }

    /** 只清频道，分组留着：空分组是用户自己建的，不该被顺手删掉 */
    fun clearChannels(): Result<Unit> = mutateDb { db ->
        db.channels.clear()
        Result.success(Unit)
    }

    fun renameGroup(old: String, new: String): Result<Unit> = mutateDb { db ->
        val n = new.trim()
        val i = db.groups.indexOf(old)
        when {
            n.isEmpty() -> fail("分组名不能为空")
            i < 0 -> fail("分组不存在")
            db.groups.any { it == n } -> fail("分组已存在")
            else -> {
                db.groups[i] = n
                for (k in db.channels.indices)
                    if (db.channels[k].group == old) db.channels[k] = db.channels[k].copy(group = n)
                Result.success(Unit)
            }
        }
    }

    fun deleteGroup(name: String): Result<Unit> = mutateDb { db ->
        val i = db.groups.indexOf(name)
        if (i < 0 && db.channels.none { it.group == name }) fail("分组不存在")
        else {
            if (i >= 0) db.groups.removeAt(i)
            db.channels.removeAll { it.group == name }
            Result.success(Unit)
        }
    }

    private fun indexOf(list: List<Channel>, line: Int, group: String, name: String, url: String): Int {
        if (line >= 0) {
            val byLine = list.indexOfFirst { it.line == line }
            if (byLine >= 0) return byLine
        }
        return list.indexOfFirst { it.group == group && it.name == name && it.url == url }
    }

    /** 读→改→写全在同一把锁里，避免两个 HTTP 请求并发覆盖 */
    private fun <T> mutateDb(block: (Db) -> Result<T>): Result<T> =
        synchronized(lock) {
            val db = readDb()
            val r = block(db)
            if (r.isSuccess) {
                writeText(channelsFile(), serialize(db))
                notifyChanged()
            }
            r
        }

    private fun readDb(): Db =
        parse(runCatching { channelsFile().readText(Charsets.UTF_8) }.getOrDefault(""))

    /**
     * 解析频道文本：
     *  - “分组名,#genre#”开一个分组（DIYP txt 格式），后面没有频道也算分组存在
     *  - “频道名称,频道地址,台标名称,清晰度,来源”一行一个，后三列可省略（台标名默认取频道名称，清晰度默认 HD，来源默认空），地址后可加 “$分组名”
     *  - “#”开头为注释；分组之前的内容归“其他频道”
     */
    private fun parse(text: String): Db {
        val groups = mutableListOf<String>()
        val out = mutableListOf<Channel>()
        var group = ""
        text.lineSequence().forEachIndexed { idx, raw ->
            val t = raw.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEachIndexed
            val parts = t.split(',')
            val name = parts[0].trim()
            if (name.isEmpty() || parts.size < 2) return@forEachIndexed
            if (parts[1].trim() == "#genre#") {
                group = name
                if (groups.none { it == name }) groups += name
                return@forEachIndexed
            }
            val urlField = parts[1].trim()
            val dollar = urlField.indexOf('$')
            val url = (if (dollar >= 0) urlField.substring(0, dollar) else urlField).trim()
            val g = (if (dollar >= 0) urlField.substring(dollar + 1).trim() else group)
                .ifEmpty { "其他频道" }
            if (groups.none { it == g }) groups += g
            val logo = parts.getOrElse(2) { "" }.trim().ifEmpty { name }
            // 第四列缺省当 HD（老文件都是这么写的）；写了但留空就是空，不强塞默认值
            val quality = if (parts.size > 3) parts[3].trim() else "HD"
            val source = parts.getOrElse(4) { "" }.trim()
            if (url.isNotEmpty()) out += Channel(name, url, idx, g, logo, quality, source)
        }
        return Db(groups, out)
    }

    /**
     * 按分组顺序落盘：每个分组一行标题，后面跟它的频道，空分组只留标题。
     * 台标与频道同名、清晰度又是 HD、又没有来源时只写两列，保持文件干净；
     * 否则从第三列起必须一路写满——第三列台标、第四列清晰度、第五列来源，跳着写会串位。
     */
    private fun serialize(db: Db): String {
        val sb = StringBuilder()
        val left = db.channels.toMutableList()
        for (group in db.groups) {
            sb.append(group).append(",#genre#\n")
            for (ch in left.filter { it.group == group }) {
                appendChannel(sb, ch)
                left.remove(ch)
            }
        }
        // 没登记标题的分组（手工 push 进来的文件、旧数据）照原顺序补上，不能丢频道
        var extra: String? = null
        for (ch in left) {
            if (ch.group != extra) {
                extra = ch.group
                sb.append(extra).append(",#genre#\n")
            }
            appendChannel(sb, ch)
        }
        return sb.toString()
    }

    private fun appendChannel(sb: StringBuilder, ch: Channel) {
        sb.append(ch.name).append(',').append(ch.url)
        val customLogo = ch.logo.isNotEmpty() && ch.logo != ch.name
        if (customLogo || ch.quality != "HD" || ch.source.isNotEmpty()) {
            sb.append(',').append(if (customLogo) ch.logo else ch.name)
            // 清晰度原样写（可能是空的）：空值写成空列，读回时才是空的
            sb.append(',').append(ch.quality)
            if (ch.source.isNotEmpty()) sb.append(',').append(ch.source)
        }
        sb.append('\n')
    }

    // ---------------- 登录网址 ----------------

    fun logins(): List<LoginEntry> = synchronized(lock) {
        runCatching { loginFile().readText(Charsets.UTF_8) }.getOrDefault("")
            .lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val i = line.indexOf(',')
                if (i > 0) LoginEntry(line.substring(0, i).trim(), line.substring(i + 1).trim())
                else LoginEntry(hostOf(line), line)
            }.filter { it.url.startsWith("http") }
            .toList()
    }

    fun addLogin(name: String, url: String): Result<Unit> {
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://"))
            return Result.failure(IllegalArgumentException("地址要以 http:// 或 https:// 开头"))
        return mutateLogins { list ->
            if (list.any { it.url == u }) fail("该地址已存在")
            else {
                list += LoginEntry(name.trim().ifEmpty { hostOf(u) }, u)
                Result.success(Unit)
            }
        }
    }

    fun updateLogin(oldUrl: String, name: String, url: String): Result<Unit> {
        val o = oldUrl.trim()
        val u = url.trim()
        if (!u.startsWith("http://") && !u.startsWith("https://"))
            return Result.failure(IllegalArgumentException("地址要以 http:// 或 https:// 开头"))
        return mutateLogins { list ->
            val i = list.indexOfFirst { it.url == o }
            when {
                i < 0 -> fail("地址不存在，请刷新后重试")
                u != o && list.any { it.url == u } -> fail("该地址已存在")
                else -> {
                    list[i] = LoginEntry(name.trim().ifEmpty { hostOf(u) }, u)
                    Result.success(Unit)
                }
            }
        }
    }

    fun deleteLogin(url: String): Result<Unit> = mutateLogins { list ->
        val before = list.size
        list.removeAll { it.url == url }
        if (list.size == before) fail("地址不存在") else Result.success(Unit)
    }

    private fun mutateLogins(block: (MutableList<LoginEntry>) -> Result<Unit>): Result<Unit> =
        synchronized(lock) {
            val list = logins().toMutableList()
            val r = block(list)
            if (r.isSuccess) {
                writeText(loginFile(), list.joinToString("\n") { "${it.name},${it.url}" } + "\n")
                notifyChanged()
            }
            r
        }

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    // ---------------- 配对码 ----------------

    /** 4 位数字配对码：首次访问随机生成并持久化，电视屏幕展示，配置网页请求头带回校验 */
    val pin: String
        get() = synchronized(lock) {
            prefs.getString(KEY_PIN, null) ?: Random.nextInt(1000, 10000).toString().also {
                prefs.edit().putString(KEY_PIN, it).apply()
            }
        }

    fun regeneratePin(): String = synchronized(lock) {
        Random.nextInt(1000, 10000).toString().also {
            prefs.edit().putString(KEY_PIN, it).apply()
        }
    }

    // ---------------- 开发者调试开关 ----------------

    /**
     * 配置页「开发者调试」里的几个开关。默认值就是原来的固定行为：
     * 拦截和缓存一直是开的，调试浮层一直是关的。
     * 存成一份 JSON 而不是一串布尔 key：加开关不用再动存储代码。
     */
    private val DEV_DEFAULTS = linkedMapOf(
        // 不加载网页的图片 / 字体（CSS 照常加载）
        "blockStyle" to true,
        // 网页脚本走本地磁盘缓存
        "scriptCache" to true,
        // 屏幕上叠一层 video 状态浮层
        "videoOverlay" to false,
        // 全屏容器的边界标记 + 每秒一行几何日志（排查没铺满 / 画面偏移）
        "frameMarkers" to false
    )

    private fun devPrefs(): org.json.JSONObject = runCatching {
        org.json.JSONObject(prefs.getString(KEY_DEV, null) ?: "{}")
    }.getOrDefault(org.json.JSONObject())

    /** 读一个开关（播放页每次请求都按当前值走，改了不用重启） */
    fun devFlag(name: String): Boolean = synchronized(lock) {
        val def = DEV_DEFAULTS[name] ?: false
        if (::prefs.isInitialized) devPrefs().optBoolean(name, def) else def
    }

    /** 写一个开关：只覆盖这一项，其余保持原样 */
    fun setDevFlag(name: String, value: Boolean): Unit = synchronized(lock) {
        require(name in DEV_DEFAULTS) { "未知开关：$name" }
        prefs.edit().putString(KEY_DEV, devPrefs().put(name, value).toString()).apply()
    }

    /** 全部开关（缺项补默认值），配置页回显用 */
    fun devFlags(): org.json.JSONObject = synchronized(lock) {
        val cur = if (::prefs.isInitialized) devPrefs() else org.json.JSONObject()
        org.json.JSONObject().also { out ->
            for ((k, v) in DEV_DEFAULTS) out.put(k, cur.optBoolean(k, v))
        }
    }

    // ---------------- 落盘工具 ----------------

    private fun notifyChanged() {
        val cb = onChanged ?: return
        main.post(cb)
    }

    /** 先写临时文件再改名：中途断电/崩溃也不会留下半截频道表 */
    private fun writeText(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        if (target.exists()) target.delete()
        tmp.renameTo(target)
    }
}
