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
 * 出厂不带任何频道：首次运行两个文件都不存在，列表为空，引导用户去配置页添加。
 */
object ChannelStore {

    private const val CHANNEL_FILE = "channels.txt"
    private const val LOGIN_FILE = "logins.txt"
    private const val PREFS = "config"
    private const val KEY_PIN = "pin"

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

    // ---------------- 频道 ----------------

    fun channels(): List<Channel> = readChannels()

    fun groups(): List<Group> {
        val map = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in readChannels()) map.getOrPut(ch.group) { mutableListOf() }.add(ch)
        return map.map { Group(it.key, it.value) }
    }

    fun addChannel(group: String, name: String, url: String, quality: String): Result<Unit> =
        mutate { list ->
            when {
                name.isBlank() || url.isBlank() ->
                    Result.failure(IllegalArgumentException("名称和地址不能为空"))
                list.any { it.name == name && it.url == url } ->
                    Result.failure(IllegalArgumentException("该频道已存在"))
                else -> {
                    list += Channel(name.trim(), url.trim(), -1,
                        group.ifBlank { "其他频道" }.trim(), name.trim(),
                        quality.ifBlank { "HD" }.trim())
                    Result.success(Unit)
                }
            }
        }

    /** 按原行号定位（网页拿到的就是行号）；行号失效时退回“分组+名称+地址”匹配。空的新字段表示不改 */
    fun updateChannel(
        line: Int, group: String, name: String, url: String,
        newGroup: String, newName: String, newUrl: String, newQuality: String
    ): Result<Unit> = mutate { list ->
        val i = indexOf(list, line, group, name, url)
        when {
            i < 0 -> Result.failure(IllegalArgumentException("频道不存在，请刷新后重试"))
            else -> {
                val old = list[i]
                val finalName = newName.ifBlank { old.name }.trim()
                val finalUrl = newUrl.ifBlank { old.url }.trim()
                if (finalName.isEmpty() || finalUrl.isEmpty())
                    return@mutate Result.failure(IllegalArgumentException("名称和地址不能为空"))
                list[i] = old.copy(
                    name = finalName, url = finalUrl,
                    group = newGroup.ifBlank { old.group }.trim(),
                    logo = if (newName.isBlank()) old.logo else finalName,
                    quality = newQuality.ifBlank { old.quality }.trim()
                )
                Result.success(Unit)
            }
        }
    }

    fun deleteChannel(line: Int, group: String, name: String, url: String): Result<Unit> =
        mutate { list ->
            val i = indexOf(list, line, group, name, url)
            if (i < 0) Result.failure(IllegalArgumentException("频道不存在，请刷新后重试"))
            else {
                list.removeAt(i)
                Result.success(Unit)
            }
        }

    /** 整段导入：append 追加到现有列表之后，replace 覆盖全部 */
    fun importChannels(text: String, replace: Boolean): Result<Int> {
        val parsed = parse(text)
        if (parsed.isEmpty()) return Result.failure(IllegalArgumentException("没有解析出任何频道"))
        return mutateImported { list ->
            if (replace) list.clear()
            val seen = list.mapTo(HashSet()) { it.name + '\u0000' + it.url }
            var added = 0
            for (ch in parsed) {
                val key = ch.name + '\u0000' + ch.url
                if (seen.add(key)) {
                    list += ch
                    added++
                }
            }
            Result.success(added)
        }
    }

    fun clearChannels(): Result<Unit> = mutate { list ->
        list.clear()
        Result.success(Unit)
    }

    fun renameGroup(old: String, new: String): Result<Unit> {
        if (new.isBlank()) return Result.failure(IllegalArgumentException("分组名不能为空"))
        return mutate { list ->
            var hit = false
            for (i in list.indices) if (list[i].group == old) {
                list[i] = list[i].copy(group = new.trim()); hit = true
            }
            if (hit) Result.success(Unit) else Result.failure(IllegalArgumentException("分组不存在"))
        }
    }

    fun deleteGroup(name: String): Result<Unit> = mutate { list ->
        val before = list.size
        list.removeAll { it.group == name }
        if (list.size == before) Result.failure(IllegalArgumentException("分组不存在"))
        else Result.success(Unit)
    }

    private fun indexOf(list: List<Channel>, line: Int, group: String, name: String, url: String): Int {
        if (line >= 0) {
            val byLine = list.indexOfFirst { it.line == line }
            if (byLine >= 0) return byLine
        }
        return list.indexOfFirst { it.group == group && it.name == name && it.url == url }
    }

    /** 读→改→写全在同一把锁里，避免两个 HTTP 请求并发覆盖 */
    private fun mutate(block: (MutableList<Channel>) -> Result<Unit>): Result<Unit> =
        synchronized(lock) {
            val list = readChannels().toMutableList()
            val r = block(list)
            if (r.isSuccess) {
                writeText(channelsFile(), serialize(list))
                notifyChanged()
            }
            r
        }

    private fun mutateImported(block: (MutableList<Channel>) -> Result<Int>): Result<Int> =
        synchronized(lock) {
            val list = readChannels().toMutableList()
            val r = block(list)
            if (r.isSuccess) {
                writeText(channelsFile(), serialize(list))
                notifyChanged()
            }
            r
        }

    private fun readChannels(): List<Channel> =
        parse(runCatching { channelsFile().readText(Charsets.UTF_8) }.getOrDefault(""))

    /**
     * 解析频道文本：
     *  - “分组名,#genre#”开一个分组（DIYP txt 格式）
     *  - “频道名称,频道地址,台标名称,清晰度”一行一个，后两列可省略（台标名默认取频道名称，清晰度默认 HD），地址后可加 “$分组名”
     *  - “#”开头为注释；分组之前的内容归“其他频道”
     */
    private fun parse(text: String): List<Channel> {
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
                return@forEachIndexed
            }
            val urlField = parts[1].trim()
            val dollar = urlField.indexOf('$')
            val url = (if (dollar >= 0) urlField.substring(0, dollar) else urlField).trim()
            val g = (if (dollar >= 0) urlField.substring(dollar + 1).trim() else group)
                .ifEmpty { "其他频道" }
            val logo = parts.getOrElse(2) { "" }.trim().ifEmpty { name }
            val quality = parts.getOrElse(3) { "" }.trim().ifEmpty { "HD" }
            if (url.isNotEmpty()) out += Channel(name, url, idx, g, logo, quality)
        }
        return out
    }

    /**
     * 按分组重新落盘。台标与频道同名、清晰度又是 HD 时只写两列，保持文件干净；
     * 否则必须写满四列——第三列是台标、第四列是清晰度，跳着写会串位。
     */
    private fun serialize(list: List<Channel>): String {
        val sb = StringBuilder()
        var group: String? = null
        for (ch in list) {
            if (ch.group != group) {
                group = ch.group
                sb.append(group).append(",#genre#\n")
            }
            sb.append(ch.name).append(',').append(ch.url)
            val customLogo = ch.logo.isNotEmpty() && ch.logo != ch.name
            val customQuality = ch.quality.isNotEmpty() && ch.quality != "HD"
            if (customLogo || customQuality) {
                sb.append(',').append(if (customLogo) ch.logo else ch.name)
                sb.append(',').append(if (customQuality) ch.quality else "HD")
            }
            sb.append('\n')
        }
        return sb.toString()
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
            if (list.any { it.url == u }) Result.failure(IllegalArgumentException("该地址已存在"))
            else {
                list += LoginEntry(name.trim().ifEmpty { hostOf(u) }, u)
                Result.success(Unit)
            }
        }
    }

    fun deleteLogin(url: String): Result<Unit> = mutateLogins { list ->
        val before = list.size
        list.removeAll { it.url == url }
        if (list.size == before) Result.failure(IllegalArgumentException("地址不存在"))
        else Result.success(Unit)
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
