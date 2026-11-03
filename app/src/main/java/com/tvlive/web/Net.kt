package com.tvlive.web

import java.net.Inet4Address
import java.net.NetworkInterface

/** 局域网地址工具：配对页要把它拼进配置网址和二维码 */
object Net {
    /** 站点内网 IPv4（192.168.x.x / 10.x / 172.16-31.x）；没联网返回 null */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
