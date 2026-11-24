package com.tvlive.web

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 站点脚本：给某个网址配一段自己写的 JS，用来处理「页内换台」这类应用不该去猜的事。
 *
 * 应用里没有任何站点相关的逻辑：找元素、点按钮全在脚本里。应用只负责
 * ① 在页面加载后把脚本注入进去（以及按脚本要求的节奏重复注入）；
 * ② 提供 window.TV 这层通用接口（日志、提示、把选项交给遥控器、换流后重新接管）。
 *
 * 存储 filesDir/site_scripts.json：
 *
 *     { "scripts": [ { "id": "s1", "name": "江苏卫视", "code": "..." } ],
 *       "binds":   { "https://example.com/": "s1" } }
 *
 * scripts 是配置页里能新建/编辑/删除的条目；binds 是频道级的绑定（按频道地址记，
 * 不动 channels.txt）。没有任何自动规则：频道上选了哪条就跑哪条，没选就不跑。
 */
object SiteScripts {

    data class Script(
        val id: String,
        val name: String,
        val code: String
    )

    /** 绑定值里代表「这个频道明确不用脚本」，空串则是没绑定过 */
    const val NONE = "none"

    private const val FILE = "site_scripts.json"
    private const val LEGACY_FILE = "site_scripts.txt"

    private lateinit var file: File
    private lateinit var legacy: File
    private val lock = Any()

    private var scripts: List<Script> = emptyList()
    private var binds: MutableMap<String, String> = mutableMapOf()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, FILE)
        legacy = File(ctx.filesDir, LEGACY_FILE)
        load()
    }

    // ---------------- 读 ----------------

    fun all(): List<Script> = synchronized(lock) { scripts.toList() }

    fun byId(id: String?): Script? =
        if (id.isNullOrBlank()) null else synchronized(lock) { scripts.firstOrNull { it.id == id } }

    /** 频道上绑的脚本 id（没有则空串） */
    fun bindOf(url: String?): String =
        if (url.isNullOrBlank()) "" else synchronized(lock) { binds[url] ?: "" }

    /**
     * 这个地址该用哪段脚本：只看频道上的绑定，没有绑定就不跑。
     * 应用不猜网址、不做匹配，“用哪条”完全由用户在频道里选。
     */
    fun forUrl(url: String?): Script? {
        if (url.isNullOrBlank() || url.startsWith("about:") || url.startsWith("file:")) return null
        val b = bindOf(url)
        if (b.isEmpty() || b == NONE) return null
        return byId(b)
    }

    // ---------------- 写 ----------------

    fun add(name: String, code: String): Result<Script> = synchronized(lock) {
        val n = name.trim().ifEmpty { "未命名脚本" }
        if (code.isBlank()) return Result.failure(IllegalArgumentException("脚本内容不能为空"))
        val s = Script(nextId(), n, code)
        scripts = scripts + s
        persist()
        Result.success(s)
    }

    fun update(id: String, name: String, code: String): Result<Script> =
        synchronized(lock) {
            val old = scripts.firstOrNull { it.id == id }
                ?: return Result.failure(IllegalArgumentException("脚本不存在"))
            if (code.isBlank()) return Result.failure(IllegalArgumentException("脚本内容不能为空"))
            val s = old.copy(
                name = name.trim().ifEmpty { old.name },
                code = code
            )
            scripts = scripts.map { if (it.id == id) s else it }
            persist()
            Result.success(s)
        }

    fun remove(id: String): Result<Unit> = synchronized(lock) {
        if (scripts.none { it.id == id }) return Result.failure(IllegalArgumentException("脚本不存在"))
        scripts = scripts.filterNot { it.id == id }
        // 绑定到它的频道一起清掉，不然会指向一个不存在的脚本
        binds.entries.removeAll { it.value == id }
        persist()
        Result.success(Unit)
    }

    /** 把频道绑到某个脚本；id 传空串或 NONE 都表示这个频道不用脚本 */
    fun bind(url: String, id: String): Result<Unit> = synchronized(lock) {
        if (url.isBlank()) return Result.failure(IllegalArgumentException("频道地址为空"))
        if (id.isBlank()) {
            binds.remove(url)
        } else if (id == NONE) {
            binds[url] = NONE
        } else {
            if (scripts.none { it.id == id }) return Result.failure(IllegalArgumentException("脚本不存在"))
            binds[url] = id
        }
        persist()
        Result.success(Unit)
    }

    /** 兼容旧接口：整段读（配置页已改成逐条管理，这里只留给调试） */
    fun raw(): String = synchronized(lock) { dump().toString(2) }

    // ---------------- 落盘 ----------------

    private fun nextId(): String {
        var i = scripts.size + 1
        while (scripts.any { it.id == "s$i" }) i++
        return "s$i"
    }

    private fun dump(): JSONObject {
        val arr = JSONArray()
        for (s in scripts) {
            arr.put(JSONObject().put("id", s.id).put("name", s.name).put("code", s.code))
        }
        val b = JSONObject()
        for ((url, id) in binds) b.put(url, id)
        return JSONObject().put("scripts", arr).put("binds", b)
    }

    private fun persist() {
        runCatching { file.writeText(dump().toString()) }
    }

    private fun load() = synchronized(lock) {
        if (file.exists()) {
            val o = runCatching { JSONObject(file.readText()) }.getOrNull()
            if (o != null) {
                scripts = parseScripts(o.optJSONArray("scripts"))
                binds = parseBinds(o.optJSONObject("binds"))
                return
            }
        }
        // 旧版是纯文本「### 网址片段」格式，第一次升级时读进来
        if (legacy.exists()) {
            val parsed = parseLegacy(runCatching { legacy.readText() }.getOrDefault(""))
            if (parsed.isNotEmpty()) {
                scripts = parsed
                persist()
            }
            runCatching { legacy.delete() }
        }
    }

    private fun parseScripts(arr: JSONArray?): List<Script> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Script>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val code = o.optString("code", "")
            if (code.isBlank()) continue
            out += Script(
                id = o.optString("id", "").ifEmpty { "s${out.size + 1}" },
                // 老文件里 match 是匹配用的网址，现在当名字兜底
                name = o.optString("name", "").ifEmpty {
                    o.optString("match", "").ifEmpty { "脚本${out.size + 1}" }
                },
                code = code
            )
        }
        return out
    }

    private fun parseBinds(o: JSONObject?): MutableMap<String, String> {
        val m = mutableMapOf<String, String>()
        if (o == null) return m
        for (k in o.keys()) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) m[k] = v
        }
        return m
    }

    /** 旧格式：`### 网址片段` + 正文，直到下一个 ### */
    private fun parseLegacy(text: String): List<Script> {
        val out = mutableListOf<Script>()
        var match: String? = null
        val code = StringBuilder()
        fun flush() {
            val m = match ?: return
            val body = code.toString().trim()
            if (body.isNotEmpty() && m.isNotEmpty()) {
                out += Script("s${out.size + 1}", m, body)
            }
            code.setLength(0)
        }
        for (line in text.lineSequence()) {
            if (line.trimStart().startsWith("###")) {
                flush()
                match = line.trimStart().removePrefix("###").trim()
                continue
            }
            if (match != null) code.append(line).append('\n')
        }
        flush()
        return out
    }
}
