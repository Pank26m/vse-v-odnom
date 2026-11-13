package com.pank.vsevodnom

import com.pank.vsevodnom.data.device.BatteryLog
import com.pank.vsevodnom.data.device.Dev
import com.pank.vsevodnom.data.device.DevConnect
import com.pank.vsevodnom.data.device.DevCpu
import com.pank.vsevodnom.data.device.DevHardware
import com.pank.vsevodnom.data.device.DevSystem
import com.pank.vsevodnom.data.device.NetTools
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Расчёты анализатора устройства (0.44): батарея, аттестация, ядра, Wi-Fi, объёмы. */
class DeviceTest {
    @Test fun regressionFindsTrend() {
        val pts = (0 until 30).map { d -> d.toDouble() to 95.0 - 0.02 * d }
        val (a, b, r2) = BatteryLog.regression(pts)!!
        assertEquals(95.0, a, 1e-6); assertEquals(-0.02, b, 1e-6); assertEquals(1.0, r2, 1e-6)
    }

    @Test fun capacityFromCharge() {
        val s = BatteryLog.Session(true, 0, 3_600_000, 20, 80, mah = 3000.0)
        assertEquals(5000.0, s.capacity!!, 1e-6)
        assertEquals(0.6, s.cycles, 1e-9)
        // маленькая зарядка — ёмкость не считаем (слишком неточно)
        assertEquals(null, BatteryLog.Session(true, 0, 1000, 50, 55, mah = 300.0).capacity)
    }

    @Test fun dischargeRates() {
        val now = System.currentTimeMillis()
        val s = BatteryLog.Session(false, now - 4 * 3_600_000L, now, 100, 60, mah = 2000.0, onMs = 2 * 3_600_000L, offMs = 2 * 3_600_000L, onMah = 1500.0, offMah = 500.0)
        val r = BatteryLog.rates(listOf(s), 5000.0)
        assertEquals(1, r.sessions)
        assertEquals(15.0, r.onPctH!!, 1e-6)    // 1500 мА·ч из 5000 за 2 ч = 30 % / 2 ч
        assertEquals(5.0, r.offPctH!!, 1e-6)
        assertEquals(10.0, r.mixedPctH!!, 1e-6)
        assertEquals(750.0, r.onMa!!, 1e-6)
    }

    @Test fun derParsesAttestationShape() {
        // SEQUENCE { INTEGER 300, ENUM 1, [704] { SEQUENCE { OCTET STRING 0xAB 0xCD, BOOLEAN TRUE, ENUM 0 } }, [706] { INTEGER 202604 } }
        val rot = byteArrayOf(0x30, 10, 0x04, 2, 0xAB.toByte(), 0xCD.toByte(), 0x01, 1, 0xFF.toByte(), 0x0A, 1, 0)
        val t704 = byteArrayOf(0xBF.toByte(), 0x85.toByte(), 0x40, rot.size.toByte()) + rot
        val lvl = byteArrayOf(0x02, 3, 0x03, 0x17, 0x6C)   // 202604
        val t706 = byteArrayOf(0xBF.toByte(), 0x85.toByte(), 0x42, lvl.size.toByte()) + lvl
        val body = byteArrayOf(0x02, 2, 0x01, 0x2C, 0x0A, 1, 1) + t704 + t706
        val der = byteArrayOf(0x30, body.size.toByte()) + body
        val top = DevSystem.Der.parse(der).children
        assertEquals(300, top[0].int); assertEquals(1, top[1].int)
        assertEquals(704, top[2].tag)
        val r = top[2].children.first().children
        assertTrue(r[1].bool); assertEquals(0, r[2].int)
        assertEquals(706, top[3].tag); assertEquals(202604, top[3].children.first().int)
    }

    @Test fun cpuCoreNames() {
        assertEquals("Cortex-X3", DevCpu.coreName("0x41", "0xd4e"))
        assertEquals("Cortex-A715", DevCpu.coreName("0x41", "0xd4d"))
        assertEquals("Cortex-A510", DevCpu.coreName("0x41", "0xd46"))
        assertEquals("ARMv9.0-A", DevCpu.archOf("Cortex-X3"))
        assertEquals("ARMv8.2-A", DevCpu.archOf("Cortex-A55"))
    }

    @Test fun wifiChannelsAndDistance() {
        assertEquals(6, DevConnect.channelOf(2437)); assertEquals(48, DevConnect.channelOf(5240)); assertEquals(153, DevConnect.channelOf(5765)); assertEquals(5, DevConnect.channelOf(5975))
        val d = NetTools.distanceM(-42, 5240); assertTrue(d in 0.3..1.5, "~0,6 м, а не $d")
        assertTrue(NetTools.distanceM(-80, 5180) > NetTools.distanceM(-60, 5180))
        assertEquals("WPA2/WPA3", DevConnect.security("[WPA2-PSK-CCMP][RSN-SAE+PSK-CCMP][ESS]"))
    }

    @Test fun marketingSizes() {
        assertEquals("12 ГБ", DevHardware.marketing(11_572L * 1024 * 1024))
        assertEquals("256 ГБ", DevHardware.marketing(245_000_000_000L))
        assertEquals("8 ГБ", DevHardware.marketing(7_700_000_000L))
        assertNotNull(Dev.bytes(1536)); assertEquals("1,50 КБ", Dev.bytes(1536))
    }
}
