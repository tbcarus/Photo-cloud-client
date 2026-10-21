package ru.tbcarus.photo_cloud_client.core.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerAddressTest {

    @Test
    fun validIpAndPortBuildBaseUrl() {
        assertEquals("http://192.168.1.10:8080/", buildBaseUrl("192.168.1.10", "8080"))
        assertEquals("http://192.168.1.10:8080/", buildBaseUrl(" 192.168.1.10 ", " 8080 "))
    }

    @Test
    fun missingValuesGiveNoUrl() {
        assertNull(buildBaseUrl(null, "8080"))
        assertNull(buildBaseUrl("192.168.1.10", null))
        assertNull(buildBaseUrl("", ""))
    }

    @Test
    fun invalidIpGivesNoUrl() {
        assertNull(buildBaseUrl("999.1.1.1", "8080"))
        assertNull(buildBaseUrl("server.local", "8080"))
        assertNull(buildBaseUrl("10.0.0", "8080"))
    }

    @Test
    fun invalidPortGivesNoUrl() {
        assertNull(buildBaseUrl("192.168.1.10", "abc"))
        assertNull(buildBaseUrl("192.168.1.10", "0"))
        assertNull(buildBaseUrl("192.168.1.10", "65536"))
        assertNull(buildBaseUrl("192.168.1.10", "+80"))
        assertNull(buildBaseUrl("192.168.1.10", "-1"))
    }
}
