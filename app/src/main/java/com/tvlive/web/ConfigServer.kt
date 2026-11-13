package com.tvlive.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger

/**
 * 配置服务：电视端起一个极简 HTTP 服务器，手机浏览器扫码打开就能管频道和登录网址。
 *
 * 做五类事：下发 assets/config.html、回显当前数据、执行增删改、让电视切台/开登录页、
 * 以及一条 /remote 的 WebSocket 供网页遥控持续送光标指令。
 * 不是通用服务器——不解析 MIME 协商、不分块传输、不支持 keep-alive，每个请求一个连接，
 * 响应头统一 Connection: close，够用且不容易被边角情况搞崩。
 * 只有 WebSocket 是长连接：触控板每帧几十个事件走「一次请求一个连接」的 HTTP 会把服务压垮，
 * 也更没法跟手。
 *
 * 门禁：api 接口必须带 X-Pin 请求头（或 ?pin=）且与电视屏幕显示的 4 位配对码一致，
 * 否则 401。页面本身不校验，否则连输 PIN 的界面都打不开。
 */
class ConfigServer(
    private val port: Int,
    private val context: Context,
    private val hostProvider: () -> ConfigHost?,
    private val remoteProvider: () -> RemoteSink?,
    private val remoteCount: (Int) -> Unit,
    private val zoomProvider: () -> Int,
    private val pinProvider: () -> String
) {

    /** 服务端要落到界面上的动作，由前台 Activity 实现（谁在台上听谁的） */
    interface ConfigHost {
        fun playing(): Channel?
        fun play(group: String, name: String, url: String): Boolean
        fun openLogin(url: String)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null

    /** 在连的网页遥控数：登录页左上角那行状态字要跟着报数 */
    private val remoteClients = AtomicInteger(0)

    /** 在连遥控页的输出流：电视这边改了缩放要主动推回去，不能让页面猜 */
    private val remoteStreams = java.util.concurrent.CopyOnWriteArrayList<OutputStream>()

    fun start() {
        if (server != null) return
        Thread {
            try {
                val ss = ServerSocket(port)
                server = ss
                while (!ss.isClosed) {
                    val socket = try {
                        ss.accept()
                    } catch (_: Exception) {
                        break
                    }
                    handleAsync(socket)
                }
            } catch (e: Exception) {
                Log.w(TAG, "ConfigServer 启动失败: $e")
            }
        }.apply { isDaemon = true; name = "config-server"; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun handleAsync(socket: Socket) {
        Thread {
            try {
                socket.tcpNoDelay = true
                socket.soTimeout = 8000
                serve(socket)
            } catch (e: Exception) {
                // 手机提前关连接是常态，但解析/写库出错也要能看见
                Log.d(TAG, "config 请求中断: $e")
            } finally {
                runCatching { socket.close() }
            }
        }.apply { isDaemon = true; start() }
    }

    // ---------------- 请求处理 ----------------

    private fun serve(socket: Socket) {
        val input = socket.getInputStream()
        val out = socket.getOutputStream()
        val head = readHeaderBlock(input) ?: return
        val lines = head.split("\r\n")
        if (lines.isEmpty()) return
        val parts = lines[0].split(' ')
        if (parts.size < 2) return
        val method = parts[0]
        val rawTarget = parts[1]
        val path = rawTarget.substringBefore('?').trimStart('/')
        val query = rawTarget.substringAfter('?', "")

        var contentLength = 0
        var headerPin: String? = null
        var wsKey: String? = null
        var wantUpgrade = false
        for (i in 1 until lines.size) {
            val c = lines[i].indexOf(':')
            if (c <= 0) continue
            val name = lines[i].substring(0, c).trim().lowercase()
            val value = lines[i].substring(c + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: 0
                "x-pin" -> headerPin = value
                "upgrade" -> wantUpgrade = value.equals("websocket", ignoreCase = true)
                "sec-websocket-key" -> wsKey = value
            }
        }
        val body = if (contentLength > 0) readBody(input, contentLength) else ""
        val pin = pinOf(headerPin, query)

        when {
            method == "GET" && path == "remote" ->
                // 网页遥控：先验码再握手，配错就当没这门
                when {
                    !wantUpgrade || wsKey.isNullOrEmpty() ->
                        sendText(out, 400, "text/plain; charset=utf-8", "需要 WebSocket 连接")
                    pin != pinProvider() -> sendText(out, 401, "text/plain; charset=utf-8", "配对码不正确")
                    else -> serveRemote(socket, input, out, wsKey)
                }
            method == "GET" && (path.isEmpty() || path == "config.html") -> sendPage(out)
            method == "GET" && path == "api/state" -> sendApi(out, pin) { stateJson() }
            // 运行日志：配置页「日志」按钮直接用
            method == "GET" && path == "api/log" -> sendApi(out, pin) { logJson() }
            method == "POST" && path.startsWith("api/") ->
                sendApi(out, pin) { dispatch(path.removePrefix("api/"), parseJson(body)) }
            else -> sendText(out, 404, "text/plain; charset=utf-8", "not found")
        }
    }

    /**
     * 一条网页遥控长连接：握手完只做帧收发，每条消息一个 JSON 指令，投到主线程给登录页。
     *
     * 读带 1s 超时，超时的这几拍顺手干两件事：登录页开没开变了就推一条给页面；
     * 半天收不到任何字节（手机被系统冻结、断网后残留的半开连接）就先探一次、再断开，
     * 不然工作线程会一直挂着。
     */
    private fun serveRemote(socket: Socket, input: InputStream, out: OutputStream, key: String) {
        out.write(handshake101(key))
        out.flush()
        socket.soTimeout = REMOTE_TICK_MS
        val n = remoteClients.incrementAndGet()
        main.post { remoteCount(n) }
        var ready = remoteProvider() != null
        remoteStreams.add(out)
        sendFrame(out, JSONObject().put("t", "ready").put("v", ready).toString())
        sendFrame(out, JSONObject().put("t", "z").put("v", zoomProvider()).toString())
        val msg = ByteArrayOutputStream(256)
        var msgOp = -1
        var quiet = 0
        while (!socket.isClosed) {
            val frame = try {
                readFrame(input) ?: break
            } catch (e: java.net.SocketTimeoutException) {
                quiet++
                if (quiet % 2 == 0) {
                    val now = remoteProvider() != null
                    if (now != ready) {
                        ready = now
                        sendFrame(out, JSONObject().put("t", "ready").put("v", ready).toString())
                    }
                }
                if (quiet == REMOTE_PING_TICKS) writeFrame(out, OP_PING, EMPTY_BYTES)
                if (quiet >= REMOTE_DROP_TICKS) break
                null
            } catch (e: Exception) {
                Log.d(TAG, "遥控连接断开: $e")
                break
            } ?: continue
            quiet = 0
            if (frame.opcode == OP_CLOSE) {
                writeFrame(out, OP_CLOSE, frame.data)
                break
            }
            if (frame.opcode == OP_PING) {
                writeFrame(out, OP_PONG, frame.data)
                continue
            }
            if (frame.opcode == OP_PONG) continue
            if (frame.opcode == OP_CONT) msg.write(frame.data) else {
                msg.reset()
                msg.write(frame.data)
                msgOp = frame.opcode
            }
            if (!frame.fin || msgOp != OP_TEXT) continue
            val obj = runCatching { JSONObject(String(msg.toByteArray(), Charsets.UTF_8)) }
                .getOrNull()
            msg.reset()
            if (obj != null) main.post { runCatching { remoteProvider()?.invoke(obj) } }
        }
        val left = remoteClients.decrementAndGet()
        main.post { remoteCount(left) }
        remoteStreams.remove(out)
    }

    /**
     * 把电视这边的缩放读数推给所有在连的遥控页。
     * 走一条串行队列：两次快按各起一个线程写，帧的先后顺序就可能翻过来。
     */
    fun pushRemoteState() {
        if (remoteStreams.isEmpty()) return
        val texts = listOf(
            JSONObject().put("t", "z").put("v", zoomProvider()).toString()
        )
        pushExec.execute {
            remoteStreams.forEach { s -> texts.forEach { sendFrame(s, it) } }
        }
    }

    private val pushExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "remote-push").apply { isDaemon = true }
    }

    /** 服务端 → 客户端一律不掩码，指令帧都小，走不到扩展长度以外 */
    private fun sendFrame(out: OutputStream, text: String) =
        writeFrame(out, OP_TEXT, text.toByteArray(Charsets.UTF_8))

    private fun writeFrame(out: OutputStream, opcode: Int, payload: ByteArray) {
        // 同一个连接可能有两个写入方（收指令的那条线程 + 推送队列），一帧拆开会废掉连接
        synchronized(out) {
            runCatching {
                out.write(0x80 or opcode)
                when {
                    payload.size < 126 -> out.write(payload.size)
                    payload.size <= 0xFFFF -> {
                        out.write(126)
                        out.write(payload.size shr 8 and 0xFF)
                        out.write(payload.size and 0xFF)
                    }
                    else -> {
                        out.write(127)
                        for (i in 7 downTo 0) out.write((payload.size.toLong() shr (8 * i)).toInt() and 0xFF)
                    }
                }
                out.write(payload)
                out.flush()
            }
        }
    }

    private fun handshake101(key: String): ByteArray {
        val digest = java.security.MessageDigest.getInstance("SHA-1")
            .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII))
        val accept = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP)
        return ("HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII)
    }

    private class Frame(val fin: Boolean, val opcode: Int, val data: ByteArray)

    /** 读一帧：掩码解掉、扩展长度算好；对端断开返回 null */
    private fun readFrame(input: InputStream): Frame? {
        val b0 = input.read()
        if (b0 < 0) return null
        val b1 = input.read()
        if (b1 < 0) return null
        val len0 = b1 and 0x7F
        var len = len0
        if (len0 == 126) len = (readOne(input) shl 8) or readOne(input)
        else if (len0 == 127) {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or readOne(input).toLong()
            len = v.toInt()
        }
        if (len > MAX_FRAME) throw IllegalArgumentException("帧过大: $len")
        val mask = if (b1 and 0x80 != 0) readBytes(input, 4) else null
        val data = readBytes(input, len)
        if (mask != null)
            for (i in data.indices) data[i] = (data[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0F, data)
    }

    private fun readOne(input: InputStream): Int =
        input.read().also { if (it < 0) throw java.io.EOFException() }

    private fun readBytes(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(buf, got, n - got)
            if (r < 0) throw java.io.EOFException()
            got += r
        }
        return buf
    }

    /**
     * 逐字节读到空行为止，返回不含结尾的请求头文本。
     * 不能用 BufferedReader 包住 input 再回头读同一个流取 body：
     * 它会预读一大块，把 body 吞进自己的缓冲，后面 readBody 只能读到空。
     */
    private fun readHeaderBlock(input: InputStream): String? {
        val sb = StringBuilder(512)
        while (true) {
            val b = input.read()
            if (b < 0) return null
            sb.append(b.toChar())
            if (sb.length > 64 * 1024) return null
            val n = sb.length
            if (n >= 4 && sb[n - 4] == '\r' && sb[n - 3] == '\n' &&
                sb[n - 2] == '\r' && sb[n - 1] == '\n'
            ) return sb.substring(0, n - 4)
        }
    }

    private fun pinOf(header: String?, query: String): String? {
        header?.let { return it }
        val q = query.split('&').firstOrNull { it.startsWith("pin=") } ?: return null
        return runCatching { URLDecoder.decode(q.removePrefix("pin="), "UTF-8") }.getOrNull()
    }

    private fun sendApi(out: OutputStream, pin: String?, build: () -> String) {
        if (pin != pinProvider()) {
            sendText(out, 401, "application/json; charset=utf-8",
                JSONObject().put("ok", false).put("err", "配对码不正确").toString())
            return
        }
        val (status, json) = runCatching { 200 to build() }
            .getOrElse {
                Log.w(TAG, "config api 失败: $it")
                500 to JSONObject().put("ok", false).put("err", it.message ?: "内部错误").toString()
            }
        sendText(out, status, "application/json; charset=utf-8", json)
    }

    private fun sendPage(out: OutputStream) {
        val html = pageCache ?: runCatching {
            context.assets.open("config.html").use { it.readBytes() }
        }.getOrNull()?.also { pageCache = it }
        if (html == null) {
            sendText(out, 500, "text/plain; charset=utf-8", "config.html 缺失")
            return
        }
        out.write(
            ("HTTP/1.1 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(html)
        out.flush()
    }

    private fun sendText(out: OutputStream, status: Int, contentType: String, text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Server Error"
        }
        out.write(
            ("HTTP/1.1 $status $reason\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        )
        out.write(bytes)
        out.flush()
    }

    private fun readBody(input: InputStream, length: Int): String {
        val buf = ByteArrayOutputStream(length.coerceAtMost(1 shl 16))
        val chunk = ByteArray(4096)
        var left = length
        while (left > 0) {
            val n = input.read(chunk, 0, minOf(left, chunk.size))
            if (n <= 0) break
            buf.write(chunk, 0, n)
            left -= n
        }
        return String(buf.toByteArray(), Charsets.UTF_8)
    }

    private fun parseJson(body: String): JSONObject =
        runCatching { JSONObject(body) }.getOrDefault(JSONObject())

    // ---------------- 数据回显 ----------------

    private fun stateJson(added: Int = -1): String {
        val gs = JSONArray()
        for (g in ChannelStore.groups()) {
            val list = JSONArray()
            for (ch in g.channels) {
                list.put(
                    JSONObject()
                        .put("line", ch.line).put("name", ch.name)
                        .put("url", ch.url).put("logo", ch.logo)
                        .put("quality", ch.quality).put("source", ch.source)
                )
            }
            gs.put(JSONObject().put("name", g.name).put("channels", list))
        }
        val logins = JSONArray()
        for (l in ChannelStore.logins()) {
            logins.put(JSONObject().put("name", l.name).put("url", l.url))
        }
        val playing = hostProvider()?.playing()?.let {
            JSONObject().put("group", it.group).put("name", it.name).put("url", it.url)
        }
        // 站点脚本条目 + 频道绑定，配置页一次拿到就够渲染了
        val scriptsJson = JSONArray()
        for (s in SiteScripts.all()) {
            scriptsJson.put(
                JSONObject().put("id", s.id).put("name", s.name).put("code", s.code)
            )
        }
        val bindsJson = JSONObject()
        for (ch in ChannelStore.groups().flatMap { it.channels }) {
            val id = SiteScripts.bindOf(ch.url)
            if (id.isNotEmpty()) bindsJson.put(ch.url, id)
        }
        return JSONObject()
            .put("ok", true)
            .put("groups", gs)
            .put("logins", logins)
            .put("scripts", scriptsJson)
            .put("binds", bindsJson)
            .put("playing", playing ?: JSONObject.NULL)
            .put("remoteReady", remoteProvider() != null)
            .put("dev", ChannelStore.devFlags())
            .put("device", android.os.Build.MODEL)
            .let { if (added >= 0) it.put("added", added) else it }
            .toString()
    }

    /** 最近的运行日志（内存环形区，进程退出即丢） */
    private fun logJson(): String {
        val arr = JSONArray()
        for (line in TvLog.recent(300)) arr.put(line)
        return JSONObject().put("ok", true).put("lines", arr).toString()
    }

    // ---------------- 写操作 ----------------

    /** 所有落到界面的动作都回主线程做，存储操作留在当前工作线程 */
    private fun dispatch(op: String, arg: JSONObject): String {
        fun fail(msg: String) = JSONObject().put("ok", false).put("err", msg).toString()
        fun ok(extra: JSONObject = JSONObject()) =
            extra.put("ok", true).toString()

        when (op) {
            "ch.add" -> return ChannelStore
                .addChannel(
                    arg.str("group"), arg.str("name"), arg.str("url"), arg.str("quality"),
                    arg.str("source")
                )
                .fold({ stateJson() }, { fail(it.message ?: "添加失败") })
            "ch.upd" -> return ChannelStore.updateChannel(
                arg.optInt("line", -1), arg.str("group"), arg.str("name"), arg.str("url"),
                arg.str("newGroup"), arg.str("newName"), arg.str("newUrl"), arg.str("newQuality"),
                arg.str("newSource")
            ).fold({ stateJson() }, { fail(it.message ?: "修改失败") })
            "ch.del" -> return ChannelStore.deleteChannel(
                arg.optInt("line", -1), arg.str("group"), arg.str("name"), arg.str("url")
            ).fold({ stateJson() }, { fail(it.message ?: "删除失败") })
            "ch.import" -> return ChannelStore
                .importChannels(arg.opt("text")?.toString() ?: "", arg.optBoolean("replace"))
                .fold({ stateJson(it) }, { fail(it.message ?: "导入失败") })
            "ch.clear" -> return ChannelStore.clearChannels()
                .fold({ stateJson() }, { fail(it.message ?: "清空失败") })
            "group.add" -> return ChannelStore.addGroup(arg.str("name"))
                .fold({ stateJson() }, { fail(it.message ?: "新建失败") })
            "group.rename" -> return ChannelStore.renameGroup(arg.str("old"), arg.str("new"))
                .fold({ stateJson() }, { fail(it.message ?: "改名失败") })
            "group.del" -> return ChannelStore.deleteGroup(arg.str("name"))
                .fold({ stateJson() }, { fail(it.message ?: "删除失败") })
            "login.add" -> return ChannelStore.addLogin(arg.str("name"), arg.str("url"))
                .fold({ stateJson() }, { fail(it.message ?: "添加失败") })
            "login.del" -> return ChannelStore.deleteLogin(arg.str("url"))
                .fold({ stateJson() }, { fail(it.message ?: "删除失败") })
            "login.upd" -> return ChannelStore.updateLogin(arg.str("oldUrl"), arg.str("name"), arg.str("url"))
                .fold({ stateJson() }, { fail(it.message ?: "修改失败") })
            "pin.regenerate" -> return ok(JSONObject().put("pin", ChannelStore.regeneratePin()))
            // 站点脚本：逐条增删改（配置页列表式管理），以及频道 → 脚本的绑定
            "script.add" -> return SiteScripts
                .add(arg.str("name"), arg.str("code"))
                .fold({ stateJson() }, { fail(it.message ?: "新建失败") })
            "script.upd" -> return SiteScripts
                .update(arg.str("id"), arg.str("name"), arg.str("code"))
                .fold({ stateJson() }, { fail(it.message ?: "保存失败") })
            "script.del" -> return SiteScripts.remove(arg.str("id"))
                .fold({ stateJson() }, { fail(it.message ?: "删除失败") })
            "script.bind" -> return SiteScripts.bind(arg.str("url"), arg.str("id"))
                .fold({ stateJson() }, { fail(it.message ?: "绑定失败") })
            // 开发者调试开关：改完回整份状态，配置页的开关跟着服务端值走，不在前端猜
            "dev.set" -> return runCatching {
                ChannelStore.setDevFlag(arg.str("name"), arg.optBoolean("value"))
            }.fold({ stateJson() }, { fail(it.message ?: "保存失败") })
            "play" -> {
                val ch = Channel(
                    arg.str("name"), arg.str("url"), arg.optInt("line", -1),
                    arg.str("group"), arg.str("name"), "HD"
                )
                // 回最新状态：配置页靠它把「播放中」标到刚点的这一行
                return if (onMain { hostProvider()?.play(ch.group, ch.name, ch.url) == true })
                    stateJson() else fail("电视当前不在播放页")
            }
            "cache.list" -> {
                val stats = ScriptCache.stats(context)
                val arr = JSONArray()
                for (s in stats) {
                    arr.put(
                        JSONObject().put("host", s.host).put("count", s.count)
                            .put("size", s.size).put("last", s.last)
                    )
                }
                return ok(JSONObject().put("domains", arr).put("total", ScriptCache.total(stats)))
            }
            "cache.clear" -> {
                // host 为空 = 全部清；本来就没有也算清好了，别让页面弹个没意义的错
                ScriptCache.clear(context, arg.str("host"))
                return ok()
            }
            "login.open" -> {
                val url = arg.str("url")
                if (!url.startsWith("http")) return fail("地址要以 http 开头")
                return if (onMain { hostProvider()?.openLogin(url); true }) ok()
                else fail("电视当前不在播放页")
            }
            else -> return fail("未知操作：$op")
        }
    }

    /**
     * 把要落到界面上的动作投递到主线程并等结果。
     * 不能 post 完就读共享变量：主线程还没跑，拿到的永远是初始值。
     */
    private fun onMain(action: () -> Boolean): Boolean {
        val latch = java.util.concurrent.CountDownLatch(1)
        var result = false
        main.post {
            result = runCatching(action).getOrDefault(false)
            latch.countDown()
        }
        return runCatching { latch.await(4, java.util.concurrent.TimeUnit.SECONDS) }.isSuccess && result
    }

    private fun JSONObject.str(key: String): String = optString(key, "").trim()

    companion object {
        private const val TAG = "TvWeb"
        @Volatile private var pageCache: ByteArray? = null

        // WebSocket 帧
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OP_CONT = 0x0
        private const val OP_TEXT = 0x1
        private const val OP_CLOSE = 0x8
        private const val OP_PING = 0x9
        private const val OP_PONG = 0xA
        private const val MAX_FRAME = 64 * 1024
        private val EMPTY_BYTES = ByteArray(0)

        /** 遥控连接的读取节拍：1s 没动静算一拍，20 拍探一次活，90 拍还没消息就断开 */
        private const val REMOTE_TICK_MS = 1_000
        private const val REMOTE_PING_TICKS = 20
        private const val REMOTE_DROP_TICKS = 90
    }
}
