package com.wangzan101.mika.core.adb

import java.net.InetAddress
import java.net.NetworkInterface

/**
 * adb 只可能在本机自己身上:回环,或本机某块网卡上的地址(adbd 可能只绑 Wi-Fi 那个,见 [LocalPortScan])。
 *
 * 这层是不校验证书的([AdbTls] 的 `TrustAll`):连接期间的身份不靠证书链,靠配对时绑到导出密钥上的公钥。
 * 于是「连的是不是本机」就是 TLS 唯一还没被代码守住的前提——目前它只活在调用方([AdbConnectPort] 的 localHost、
 * 扫描给出的地址),而不是活在依赖它的那两层里。哪天支持「连用户手输的远程地址」,少这一道就是 MITM 入口,
 * 所以在这里立一道,让前提变成代码。
 *
 * 和 [com.wangzan101.mika.core.net.LocalHosts.isLocal] 不是一回事:那边问「是不是本机或局域网」(判断明文 http 安不安全),
 * 这边问「是不是本机自己」——同网段的另一台机器不算本机。
 *
 * 只看地址本身,**不做域名解析**:除 localhost 及其子域外,任何域名一律不收。校验时解析出来的地址和真正去连的
 * 地址可能不是同一个(DNS 在这两步之间可以变),所以不解析、只收字面量。
 */
object AdbHosts {
    /** [host] 是不是本机自己的地址:回环,或本机某块网卡上的地址。 */
    fun isOwnAddress(host: String): Boolean {
        val h = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost")) return true
        if (h == "::1" || h.startsWith("127.")) return true
        if (!looksNumeric(h)) return false
        val addr = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return false
        if (addr.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .any { nic -> nic.inetAddresses.toList().any { it == addr } }
        }.getOrDefault(false)
    }

    /** 字面量地址:数字与点(IPv4)或带冒号(IPv6)。域名在这里就否掉,不去解析它。 */
    private fun looksNumeric(h: String) = h.all { it.isDigit() || it == '.' } || ':' in h
}
