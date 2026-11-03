package com.tvlive.remote

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject

/**
 * 电视遥控（手机 = 鼠标）：连接电视上的登录页后，
 * 触控板控制电视光标；文字用「输入框 + 替换」：点替换把本機内容整体写进电视聚焦输入框。
 */
class MainActivity : Activity(), TrackpadView.Listener {

    private val client = RemoteClient()
    private lateinit var ipEdit: EditText
    private lateinit var connectBtn: Button
    private lateinit var statusText: TextView
    private lateinit var textEdit: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        fun pad(p: Int) = (p * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad(10), pad(8), pad(10), pad(8))
        }

        val connRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ipEdit = EditText(this).apply {
            hint = "电视 IP（可带端口，如 192.168.1.5:8722）"
            setText(prefs().getString("ip", "") ?: "")
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
            maxWidth = Int.MAX_VALUE
        }
        connectBtn = Button(this).apply { text = "连接" }
        // 配置走电视下发的网页：加频道、管登录网址都在浏览器里做，不必等登录页开着
        val configBtn = Button(this).apply {
            text = "配置"
            setOnClickListener { openConfig() }
        }
        connRow.addView(ipEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        connRow.addView(connectBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        connRow.addView(configBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(connRow)

        statusText = TextView(this).apply {
            text = "未连接：在电视上按「菜单键」打开登录页，屏幕左上角有 IP"
            setTextColor(0xFF888888.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(pad(4), pad(4), pad(4), pad(4))
        }
        root.addView(statusText)

        val trackpad = TrackpadView(this).apply { listener = this@MainActivity }
        root.addView(trackpad, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 按键行：网页表单常用导航键
        val keyRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((label, key) in listOf("回车" to "enter", "Tab" to "tab", "删除" to "del",
                                    "返回" to "back", "刷新" to "refresh")) {
            keyRow.addView(Button(this).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(0, 0, 0, 0)
                setOnClickListener { sendKey(key) }
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(keyRow)

        // 文字输入：一个输入框 + 一个替换按钮（整体替换电视聚焦框内容）
        val textRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        textEdit = EditText(this).apply {
            hint = "要输入的内容"
            isSingleLine = true
            textSize = 14f
        }
        val replaceBtn = Button(this).apply {
            text = "替换"
            setOnClickListener { sendText() }
        }
        textRow.addView(textEdit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        textRow.addView(replaceBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(textRow)

        setContentView(root)

        connectBtn.setOnClickListener { toggleConnect() }
        client.callback = object : RemoteClient.Callback {
            override fun onConnected() {
                statusText.text = "已连接 ${ipEdit.text}"
                connectBtn.text = "断开"
                prefs().edit().putString("ip", ipEdit.text.toString()).apply()
            }

            override fun onDisconnected(err: String?) {
                statusText.text = if (err == null) "已断开" else "连接失败：$err"
                connectBtn.text = "连接"
            }
        }
    }

    private fun toggleConnect() {
        if (client.isConnected) {
            client.disconnect()
        } else {
            val ip = ipEdit.text.toString().trim()
            if (ip.isEmpty()) {
                statusText.text = "先填电视 IP"
                return
            }
            statusText.text = "连接中…"
            // 支持 "IP" 或 "IP:端口"；模拟器测试走中继端口，真电视默认 8721
            val host = ip.substringBefore(':')
            val port = ip.substringAfter(':', "").toIntOrNull() ?: TV_PORT
            client.connect(host, port)
        }
    }

    /** 用首页填的电视地址拼出配置页：端口固定，跟遥控触控板的 8721 互不相干 */
    private fun openConfig() {
        val ip = ipEdit.text.toString().trim()
        if (ip.isEmpty()) {
            statusText.text = "先填电视 IP"
            return
        }
        prefs().edit().putString("ip", ip).apply()
        val url = "http://${ip.substringBefore(':')}:$CONFIG_PORT/"
        startActivity(android.content.Intent(this, ConfigActivity::class.java).putExtra("url", url))
    }

    private fun sendText() {
        val text = textEdit.text.toString()
        if (text.isEmpty()) return
        client.send(JSONObject().put("t", "txt").put("s", text).put("r", true))
        hideKeyboard()
        statusText.text = "已替换电视输入框内容"
    }

    private fun sendKey(key: String) {
        client.send(JSONObject().put("t", "k").put("k", key))
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(textEdit.windowToken, 0)
        textEdit.clearFocus()
    }

    // ---------------- TrackpadView.Listener ----------------

    override fun onMove(dx: Float, dy: Float) {
        client.send(JSONObject().put("t", "m").put("dx", dx.toDouble()).put("dy", dy.toDouble()))
    }

    override fun onTap() {
        client.send(JSONObject().put("t", "c"))
    }

    override fun onDoubleTap() {
        client.send(JSONObject().put("t", "dc"))
    }

    override fun onDragStart() {
        client.send(JSONObject().put("t", "ds"))
    }

    override fun onDragEnd() {
        client.send(JSONObject().put("t", "de"))
    }

    override fun onScroll(dx: Float, dy: Float) {
        client.send(JSONObject().put("t", "s")
            .put("dx", dx.toDouble()).put("dy", dy.toDouble()))
    }

    override fun onDestroy() {
        client.disconnect()
        super.onDestroy()
    }

    private fun prefs() = getSharedPreferences("remote", Context.MODE_PRIVATE)

    private companion object {
        const val TV_PORT = 8721
        const val CONFIG_PORT = 8723
    }
}
