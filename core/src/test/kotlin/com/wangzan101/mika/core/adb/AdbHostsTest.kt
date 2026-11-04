package com.wangzan101.mika.core.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.NetworkInterface

class AdbHostsTest {
    @Test
    fun loopbackIsOwn() {
        assertTrue(AdbHosts.isOwnAddress("127.0.0.1"))
        assertTrue(AdbHosts.isOwnAddress("127.255.255.254"))
        assertTrue(AdbHosts.isOwnAddress("localhost"))
        assertTrue(AdbHosts.isOwnAddress("LOCALHOST."))
        assertTrue(AdbHosts.isOwnAddress(" 127.0.0.1 "))
        assertTrue(AdbHosts.isOwnAddress("::1"))
        assertTrue(AdbHosts.isOwnAddress("[::1]"))
        assertTrue(AdbHosts.isOwnAddress("0:0:0:0:0:0:0:1"))
    }

    /** 扫描给出的端口可能带着本机 Wi-Fi 地址(adbd 只绑那一个时),那种地址必须照收。 */
    @Test
    fun anAddressOnThisMachineIsOwn() {
        val own = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress }
        }.getOrNull()
        if (own != null) assertTrue(AdbHosts.isOwnAddress(own.hostAddress.substringBefore('%')))
    }

    @Test
    fun addressesThatAreNotThisMachineAreNotOwn() {
        assertFalse(AdbHosts.isOwnAddress("192.0.2.1")) // TEST-NET-1
        assertFalse(AdbHosts.isOwnAddress("203.0.113.9")) // TEST-NET-3
        assertFalse(AdbHosts.isOwnAddress("8.8.8.8"))
        assertFalse(AdbHosts.isOwnAddress("2001:db8::1")) // documentation prefix
        assertFalse(AdbHosts.isOwnAddress("256.1.1.1")) // 不合法的字面量,不解析也不收
        assertFalse(AdbHosts.isOwnAddress(""))
        assertFalse(AdbHosts.isOwnAddress("   "))
    }

    /** 域名不收:校验时解析的地址和真正去连的地址可能不是同一个。 */
    @Test
    fun namesAreNeverResolved() {
        assertFalse(AdbHosts.isOwnAddress("adb.example.com"))
        assertFalse(AdbHosts.isOwnAddress("localhost.example.com"))
        assertFalse(AdbHosts.isOwnAddress("my-mac.local"))
    }

    @Test
    fun connectionRefusesANonLocalHostBeforeOpeningASocket() {
        val tls = TestTls.tls(TestTls.client)
        val began = System.currentTimeMillis()
        val e = assertThrows(AdbConnectionException::class.java) {
            AdbConnection.open("192.0.2.1", 5555, tls)
        }
        assertEquals(AdbConnectionException.Reason.NOT_LOCAL, e.reason)
        // 一个 socket 都没建:没有连接超时要等
        assertTrue("took ${System.currentTimeMillis() - began} ms", System.currentTimeMillis() - began < 2_000)
    }

    @Test
    fun pairingRefusesANonLocalHostBeforeOpeningASocket() {
        val e = assertThrows(AdbPairingException::class.java) {
            AdbPairing(TestTls.tls(TestTls.client)).pair("192.0.2.1", 5555, "123456")
        }
        assertEquals(AdbPairingException.Reason.NOT_LOCAL, e.reason)
    }
}
