package cz.starenka.jiffy

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WifiEndpointsTest {
    @Test fun bindCandidateMustBeAnIndividualIPv4Address() {
        val wifi = WifiEndpoints()
        val invalid = listOf("0.0.0.0", "127.0.0.1", "ff02::1", "224.0.0.1", "2001:db8::1").map(InetAddress::getByName)
        wifi.update(1, invalid)
        assertNull(wifi.selected)
        val host = InetAddress.getByName("192.0.2.1")
        wifi.update(1, invalid + host)
        assertEquals(WifiEndpoint(1, "192.0.2.1"), wifi.selected)
        wifi.update(1, listOf(InetAddress.getByName("192.0.2.2"), host))
        assertEquals(WifiEndpoint(1, "192.0.2.1"), wifi.selected)
        wifi.update(1, listOf(InetAddress.getByName("192.0.2.2")))
        assertEquals(WifiEndpoint(1, "192.0.2.2"), wifi.selected)
    }

    @Test fun replacingNetworkWithSameAddressChangesEndpoint() {
        val wifi = WifiEndpoints()
        val addresses = listOf(InetAddress.getByName("192.0.2.1"))
        wifi.update(1, addresses)
        wifi.update(2, addresses)
        assertEquals(WifiEndpoint(1, "192.0.2.1"), wifi.selected)
        wifi.update(1, emptyList())
        assertEquals(WifiEndpoint(2, "192.0.2.1"), wifi.selected)
        wifi.update(2, emptyList())
        assertNull(wifi.selected)
    }
}
