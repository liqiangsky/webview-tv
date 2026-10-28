package com.tvlive.web

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors

/**
 * 台标图：按频道名取 {base}/{name}.png。
 * vircloud/TVLogo 的图统一是 300x180 画布，各台视觉大小一致，所以优先用它；
 * 这个库缺的台（名字对不上）再退回 fanmingming。
 * 内存 LRU + filesDir/logos 落盘，取不到不报错（界面继续用文字台标），
 * 同一个台只试一次，避免来回移动光标反复打网络。
 */
object LogoLoader {
    private const val TAG = "TvWeb"
    private val BASES = listOf(
        "https://v4.gh-proxy.org/https://raw.githubusercontent.com/vircloud/TVLogo/main/",
        "https://v4.gh-proxy.org/https://raw.githubusercontent.com/fanmingming/live/main/tv/",
        "https://live.fanmingming.com/tv/"
    )
    private const val MEM_MAX = 48

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newFixedThreadPool(2)
    private val memory: MutableMap<String, Bitmap?> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Bitmap?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap?>?) =
                size > MEM_MAX
        }
    )

    /** onResult 在主线程回调：true=有台标图，false=该用文字台标 */
    fun into(view: ImageView, name: String, onResult: (Boolean) -> Unit) {
        view.tag = name
        if (memory.containsKey(name)) {
            deliver(view, name, memory[name], onResult)
            return
        }
        val app = view.context.applicationContext
        work.execute {
            val bmp = loadBytes(app, name)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            memory[name] = bmp
            main.post { deliver(view, name, bmp, onResult) }
        }
    }

    private fun deliver(view: ImageView, name: String, bmp: Bitmap?, onResult: (Boolean) -> Unit) {
        if (view.tag != name) return                      // 光标已经切到别的台，丢掉这次结果
        if (bmp == null) {
            view.setImageDrawable(null)
            onResult(false)
            return
        }
        view.setImageBitmap(bmp)
        onResult(true)
    }

    private fun loadBytes(app: Context, name: String): ByteArray? {
        val file = File(logosDir(app), hash(name) + ".png")
        if (file.isFile && file.length() > 32) runCatching { return file.readBytes() }
        for (base in BASES) {
            val bytes = fetch(base + Uri.encode(name) + ".png") ?: continue
            if (bytes.size < 64) continue
            runCatching { file.writeBytes(bytes) }
            return bytes
        }
        Log.d(TAG, "logo: none available for $name")
        return null
    }

    private fun fetch(url: String): ByteArray? = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 3_000
            readTimeout = 8_000
            instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) {
                Log.d(TAG, "logo: http ${conn.responseCode}")
                null
            } else conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()


    private fun logosDir(app: Context): File = File(app.filesDir, "logos").apply { mkdirs() }
    private fun hash(name: String): String =
        MessageDigest.getInstance("MD5").digest(name.toByteArray()).joinToString("") { "%02x".format(it) }
}
