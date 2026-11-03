package com.tvlive.web

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机遥控 TCP 服务：newline 分隔的 JSON 行，逐条解析后投递到主线程。
 * 协议见 README（move/tap/drag/scroll/text/key）。
 */
class RemoteServer(
    private val port: Int,
    private val onCommand: (JSONObject) -> Unit,
    private val onClientsChanged: (Int) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var server: ServerSocket? = null
    private val clients = AtomicInteger(0)

    fun start() {
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
                    clients.incrementAndGet()
                    main.post { onClientsChanged(clients.get()) }
                    Thread {
                        try {
                            socket.tcpNoDelay = true
                            val reader =
                                BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
                            while (true) {
                                val line = reader.readLine() ?: break
                                val obj = runCatching { JSONObject(line) }.getOrNull() ?: continue
                                main.post { onCommand(obj) }
                            }
                        } catch (_: Exception) {
                            // 手机断开是常态
                        } finally {
                            runCatching { socket.close() }
                            val n = clients.decrementAndGet()
                            main.post { onClientsChanged(n) }
                        }
                    }.apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                Log.w(TAG, "RemoteServer 启动失败: $e")
            }
        }.apply { isDaemon = true; start() }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private companion object {
        const val TAG = "TvWeb"
    }
}
