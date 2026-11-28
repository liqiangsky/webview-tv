package com.inweb.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份与恢复：把用户自己配出来的东西整份导出成一份 JSON，再整份写回去。
 *
 * 备这些：频道与分组（含空分组、清晰度/来源）、网站登录、站点脚本与频道绑定、
 * 节目单源、开发者开关、上次在看的频道与列表位置。
 *
 * 不备这些：
 *   - 脚本缓存（cache/ 下下载来的那些 JS）：派生物，进台会自己重新下载；
 *   - 节目单数据本身：几 MB 的 XMLTV，源还在就能再拉；
 *   - 配对码：它是这台设备的身份，跟备份文件走只会让人以为换机也能用。
 *
 * 恢复是整份覆盖，不做合并：半份配置比没恢复更难受。版本号读不懂就整份拒绝。
 */
object Backup {

    /** 当前格式版本；只在字段含义变了时才 +1，加字段不用 */
    private const val VERSION = 1
    private const val APP = "InWeb"

    /** 上次在看的频道与列表位置存在 MainActivity 的这份 prefs 里 */
    private const val PREFS_FOCUS = "panel_focus"

    private val app: Context get() = TvApp.instance

    // ---------------- 导出 ----------------

    fun export(): String {
        val groups = JSONArray()
        for (g in ChannelStore.groups()) {
            val list = JSONArray()
            for (ch in g.channels) {
                list.put(
                    JSONObject()
                        .put("name", ch.name).put("url", ch.url)
                        .put("quality", ch.quality).put("source", ch.source)
                )
            }
            // 空分组也写出来：用户自己建的分组不该因为备份丢了
            groups.put(JSONObject().put("name", g.name).put("channels", list))
        }

        val logins = JSONArray()
        for (l in ChannelStore.logins()) {
            logins.put(JSONObject().put("name", l.name).put("url", l.url))
        }

        val scripts = JSONArray()
        for (s in SiteScripts.all()) {
            scripts.put(JSONObject().put("id", s.id).put("name", s.name).put("code", s.code))
        }
        // 绑定只写还存在的频道：删掉的频道留下的孤儿绑定写进去也没用
        val binds = JSONObject()
        for (ch in ChannelStore.channels()) {
            val id = SiteScripts.bindOf(ch.url)
            if (id.isNotEmpty()) binds.put(ch.url, id)
        }

        val sources = JSONArray()
        for (u in EpgManager.customSources()) sources.put(u)

        val focus = app.getSharedPreferences(PREFS_FOCUS, Context.MODE_PRIVATE)

        return JSONObject()
            .put("app", APP)
            .put("version", VERSION)
            .put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
            .put("device", android.os.Build.MODEL)
            .put("groups", groups)
            .put("logins", logins)
            .put("scripts", JSONObject().put("items", scripts).put("binds", binds))
            .put("epg", JSONObject().put("sources", sources))
            .put("dev", ChannelStore.devFlags())
            .put("ui", JSONObject()
                .put("lastChannel", focus.getString("channelUrl", "") ?: "")
                .put("group", focus.getInt("group", 0)))
            .toString(2)
    }

    // ---------------- 恢复 ----------------

    /** 整份覆盖；成功返回一句摘要（页面拿它当提示），失败时说清哪儿不对 */
    fun restore(text: String): Result<String> {
        if (text.isBlank()) return fail("备份内容是空的")
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return fail("不是有效的 JSON")
        if (o.optString("app") != APP) return fail("这不是本应用的备份文件")
        val version = o.optInt("version", 0)
        if (version < 1 || version > VERSION) return fail("备份文件版本是 $version，这个版本的应用读不了")
        // 只有外壳没有内容的文件不算备份：宁可拒绝，也不要拿它把现有配置清空
        if (!o.has("groups") && !o.has("logins") && !o.has("scripts") &&
            !o.has("epg") && !o.has("dev")
        ) return fail("备份文件里没有可恢复的内容")
        // 类型不对也整份拒绝：不猜，也不只读能读的那半份
        for (key in listOf("groups", "logins")) {
            if (o.has(key) && o.optJSONArray(key) == null) return fail("备份文件格式不对：$key")
        }
        for (key in listOf("scripts", "epg", "dev", "ui")) {
            if (o.has(key) && o.optJSONObject(key) == null) return fail("备份文件格式不对：$key")
        }

        // 先把整份解析完再动手：写了一半发现格式错，比什么都没写更糟
        val groups = mutableListOf<String>()
        val channels = mutableListOf<Channel>()
        val groupArr = o.optJSONArray("groups") ?: JSONArray()
        for (i in 0 until groupArr.length()) {
            val g = groupArr.optJSONObject(i) ?: continue
            val gname = g.optString("name").trim().ifEmpty { "其他频道" }
            if (groups.none { it == gname }) groups += gname
            val chArr = g.optJSONArray("channels") ?: JSONArray()
            for (j in 0 until chArr.length()) {
                val c = chArr.optJSONObject(j) ?: continue
                val name = c.optString("name").trim()
                val url = c.optString("url").trim()
                if (name.isEmpty() || url.isEmpty()) continue
                channels += Channel(
                    name = name, url = url, line = -1, group = gname,
                    quality = c.optString("quality", "HD").trim(),
                    source = c.optString("source").trim()
                )
            }
        }

        val logins = mutableListOf<LoginEntry>()
        val loginArr = o.optJSONArray("logins") ?: JSONArray()
        for (i in 0 until loginArr.length()) {
            val l = loginArr.optJSONObject(i) ?: continue
            val url = l.optString("url").trim()
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue
            logins += LoginEntry(l.optString("name").trim(), url)
        }

        val scriptsObj = o.optJSONObject("scripts")

        val sources = mutableListOf<String>()
        val sourceArr = o.optJSONObject("epg")?.optJSONArray("sources") ?: JSONArray()
        for (i in 0 until sourceArr.length()) {
            val u = sourceArr.optString(i, "").trim()
            if (u.startsWith("http://") || u.startsWith("https://")) sources += u
        }

        // 落盘：频道先写，它失败就没必要往下走
        val chErr = ChannelStore.replaceAll(groups, channels).exceptionOrNull()
        if (chErr != null) return fail("频道写入失败：${chErr.message}")
        ChannelStore.replaceLogins(logins)
        val scriptCount = SiteScripts
            .replaceAll(scriptsObj?.optJSONArray("items"), scriptsObj?.optJSONObject("binds"))
            .getOrElse { return fail("脚本写入失败：${it.message}") }
        EpgManager.replaceSources(sources)

        // 开关只认当前版本认识的那几项，备份里多出来的忽略
        val dev = o.optJSONObject("dev")
        if (dev != null) {
            for (key in dev.keys()) {
                runCatching { ChannelStore.setDevFlag(key, dev.optBoolean(key)) }
            }
        }

        // 上次在看的频道：只影响下次启动落在哪，不用重启就已经播着的那台
        val ui = o.optJSONObject("ui")
        if (ui != null) {
            app.getSharedPreferences(PREFS_FOCUS, Context.MODE_PRIVATE).edit()
                .putString("channelUrl", ui.optString("lastChannel", ""))
                .putInt("group", ui.optInt("group", 0))
                .apply()
        }

        return Result.success(
            "${groups.size} 个分组 · ${channels.size} 个频道 · ${logins.size} 个登录网址 · " +
                "$scriptCount 段脚本 · ${sources.size} 个节目源"
        )
    }

    private fun <T> fail(msg: String): Result<T> = Result.failure(IllegalArgumentException(msg))
}
