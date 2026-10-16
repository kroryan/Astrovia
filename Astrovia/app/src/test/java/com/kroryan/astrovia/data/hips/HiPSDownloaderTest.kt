package com.kroryan.astrovia.data.hips

import com.kroryan.astrovia.data.vizier.CatalogDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HiPSDownloaderTest {
    @Test
    fun wholeSkyEstimateIsTheGeometricSeries() {
        // 12 * (4^(n+1) - 1) / 3 tiles down to order n
        assertEquals(12L, HiPSDownloader.estimate(0, null, "jpg").tiles)
        assertEquals(12L * 85, HiPSDownloader.estimate(3, null, "jpg").tiles)
        assertEquals(12L * ((1L shl 20) - 1) / 3, HiPSDownloader.estimate(9, null, "jpg").tiles)
    }

    @Test
    fun theWholeSkyToOrderNineIsHugeAndARegionIsNot() {
        val all = HiPSDownloader.estimate(9, null, "jpg")
        assertTrue("whole sky: ${HiPSDownloader.human(all.bytes)}", all.bytes > 100L * 1024 * 1024 * 1024)
        val region = HiPSDownloader.estimate(9, SkyRegion(83.8, -5.4, 1.0), "jpg")
        assertTrue("region: ${HiPSDownloader.human(region.bytes)}", region.bytes < 1024L * 1024 * 1024)
        assertTrue(region.tiles < all.tiles / 1000)
    }

    @Test
    fun regionEstimateNeverExceedsTheWholeSkyAndGrowsWithRadius() {
        val small = HiPSDownloader.estimate(6, SkyRegion(0.0, 0.0, 1.0), "png").tiles
        val big = HiPSDownloader.estimate(6, SkyRegion(0.0, 0.0, 30.0), "png").tiles
        val all = HiPSDownloader.estimate(6, null, "png").tiles
        assertTrue(small < big && big <= all)
        assertEquals(all, HiPSDownloader.estimate(6, SkyRegion(0.0, 0.0, 180.0), "png").tiles)
    }

    @Test
    fun fitsAndPngTilesAreHeavierThanJpeg() {
        val j = HiPSDownloader.estimate(4, null, "jpg").bytes
        assertTrue(HiPSDownloader.estimate(4, null, "png").bytes > j)
        assertTrue(HiPSDownloader.estimate(4, null, "fits").bytes > j)
    }

    @Test
    fun humanSizes() {
        assertEquals("512 B", HiPSDownloader.human(512))
        assertEquals("2 KB", HiPSDownloader.human(2048))
        assertEquals("1.5 MB", HiPSDownloader.human((1.5 * 1048576).toLong()))
        assertEquals("2.0 GB", HiPSDownloader.human(2L * 1073741824))
    }

    @Test
    fun tileReachShrinksWithOrder() {
        assertTrue(HiPSDownloader.tileReachRad(5) < HiPSDownloader.tileReachRad(4))
        assertEquals(HiPSDownloader.tileReachRad(4) / 2, HiPSDownloader.tileReachRad(5), 1e-12)
    }

    @Test
    fun serverRowLimitAtTheEndOfTheTableIsDetected() {
        val f = File.createTempFile("cat", ".xml")
        try {
            f.writeText("<VOTABLE>" + "<TR><TD>x</TD></TR>".repeat(2000) + "<INFO name=\"QUERY_STATUS\" value=\"OVERFLOW\">Truncated</INFO></VOTABLE>")
            assertNotNull(CatalogDownloader.detectOverflow(f))
            f.writeText("<VOTABLE>" + "<TR><TD>x</TD></TR>".repeat(2000) + "<INFO name=\"QUERY_STATUS\" value=\"OK\"/></VOTABLE>")
            assertNull(CatalogDownloader.detectOverflow(f))
        } finally {
            f.delete()
        }
    }
}
