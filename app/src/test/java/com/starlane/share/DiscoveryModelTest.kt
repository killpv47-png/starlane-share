package com.starlane.share

import com.starlane.core.link.LinkKind
import com.starlane.share.net.NearbyDevice
import com.starlane.share.net.Txt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM tests for the discovery record format and device merging (no Android runtime needed). */
class DiscoveryModelTest {
    @Test
    fun txtRoundTripAndLengthLimits() {
        val m = Txt.encode("0123456789abcdef", "A".repeat(50), "nick", "Pixel 7", 3, 47821)
        assertEquals(24, m["u"]!!.length)
        val p = Txt.decode(m)!!
        assertEquals("0123456789abcdef", p.id)
        assertEquals(47821, p.port)
        assertEquals(3, p.i)
    }

    @Test
    fun txtRejectsMalformedIdsAndPorts() {
        assertNull(Txt.decode(mapOf("id" to "../../etc")))
        assertNull(Txt.decode(mapOf("u" to "x")))
        val p = Txt.decode(mapOf("id" to "0123456789abcdef", "p" to "99999"))!!
        assertEquals(Txt.DEFAULT_PORT, p.port) // out-of-range port falls back to the default
    }

    @Test
    fun mergeCombinesDiscoverySources() {
        val lan = NearbyDevice("0123456789abcdef", "sara", "", "Galaxy A12", 1, lanHost = "192.168.1.5", lanPort = 47821)
        val p2p = NearbyDevice("0123456789abcdef", "", "phone", "", 0, p2pAddress = "aa:bb:cc:dd:ee:ff", p2pPort = 47821)
        val m = lan.merge(p2p)
        assertEquals("sara", m.username)
        assertEquals("phone", m.nickname)
        assertEquals("Galaxy A12", m.model)
        assertTrue(m.via.containsAll(listOf(LinkKind.LAN, LinkKind.WIFI_DIRECT)))
        assertEquals("sara", m.displayName)
    }
}
