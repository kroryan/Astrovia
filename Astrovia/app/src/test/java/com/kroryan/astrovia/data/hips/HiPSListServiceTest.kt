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

    @Test
    fun downloadedHipsAreListedAsOffline() {
        val root = java.nio.file.Files.createTempDirectory("hips").toFile()
        val d = java.io.File(root, "CDS_P_DSS2_color").also { it.mkdirs() }
        java.io.File(d, "properties").writeText("obs_title = DSS colored\nhips_frame = equatorial\nhips_tile_format = jpeg\n")
        java.io.File(root, "Other_Survey").also { it.mkdirs() }.let { java.io.File(it, "properties").writeText("hips_frame = galactic\n") }
        java.io.File(root, "empty").mkdirs()
        val l = HiPSListService.scanLocal(listOf(root))
        assertEquals(listOf("DSS colored (offline)", "Other Survey (offline)"), l.map { it.title })
        assertEquals("galactic", l[1].frame)
        assertEquals("CDS_P_DSS2_color_local", l[0].id)
        assertEquals(true, l.all { HiPSListService.isOffline(it) && it.description.contains("Offline") })
        assertEquals(true, l[0].baseUrl.startsWith("file://"))
        root.deleteRecursively()
    }
}
