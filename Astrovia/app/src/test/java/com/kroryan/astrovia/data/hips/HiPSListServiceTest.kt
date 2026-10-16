package com.kroryan.astrovia.data.hips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiPSListServiceTest {
    @Test
    fun httpIsUpgradedToHttps() {
        assertEquals("https://a.b/c", HiPSListService.upgradeToHttps("http://a.b/c"))
        assertEquals("https://a.b/c", HiPSListService.upgradeToHttps("https://a.b/c"))
    }

    @Test
    fun latin1ResponseIsNotCorrupted() {
        val latin1 = "[{\"obs_title\":\"Copyright © CDS\"}]".toByteArray(Charsets.ISO_8859_1)
        assertEquals("[{\"obs_title\":\"Copyright © CDS\"}]", HiPSListService.decode(latin1))
        val utf8 = "[{\"obs_title\":\"café\"}]".toByteArray(Charsets.UTF_8)
        assertEquals("[{\"obs_title\":\"café\"}]", HiPSListService.decode(utf8))
        assertNull(HiPSListService.decode(null))
    }
}
