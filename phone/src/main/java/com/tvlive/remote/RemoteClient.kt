package com.tvlive.remote

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * 遥控 TCP 客户端：newline 分隔 JSON，与电视端 RemoteServer 对应。
 * 发送走单线程队列，断线回调回主线程。
 */
class RemoteClient {

    interface Callback {
        fun onConnected()
        fun onDisconnected(err: String?)
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var socket: Socket? = null
    @Volatile private var out: OutputStream? = null
    var callback: Callback? = null
    val isConnected get() = socket?.isConnected == true && socket?.isClosed == false

    fun connect(host: String, port: Int) {
        exec.execute {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(host, port), 4000)
                s.tcpNoDelay = true
                socket = s
                out = s.getOutputStream()
                main.post { callback?.onConnected() }
            } catch (e: Exception) {
                main.post { callback?.onDisconnected(e.message) }
            }
        }
    }

    fun disconnect() {
        exec.execute {
            runCatching { socket?.close() }
            socket = null
            out = null
            main.post { callback?.onDisconnected(null) }
        }
    }

    fun send(json: JSONObject) {
        val line = (json.toString() + "\n").toByteArray(Charsets.UTF_8)
        exec.execute {
            try {
                out?.write(line)
                out?.flush()
            } catch (e: Exception) {
                runCatching { socket?.close() }
                socket = null
                out = null
                main.post { callback?.onDisconnected(e.message) }
            }
        }
    }
}
