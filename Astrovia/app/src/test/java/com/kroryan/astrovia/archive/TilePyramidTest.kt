package com.kroryan.astrovia.archive

import com.kroryan.astrovia.data.gallery.DeepZoomPyramid
import com.kroryan.astrovia.data.gallery.ZoomifyPyramid
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TilePyramidTest {
    @Test
    fun zoomifyGeometryAndUrls() {
        val z = ZoomifyPyramid.parse("https://x/zoomable/img/", "<IMAGE_PROPERTIES WIDTH=\"30866\" HEIGHT=\"38204\" NUMTILES=\"24316\" NUMIMAGES=\"1\" VERSION=\"1.8\" TILESIZE=\"256\" />")!!
        assertEquals(38204, z.height); assertEquals(256, z.tileSize)
        // the smallest tier fits in one tile, the largest is the full image
        assertEquals(1, z.cols(0)); assertEquals(1, z.rows(0))
        assertEquals(30866, z.levelWidth(z.maxLevel)); assertEquals(38204, z.levelHeight(z.maxLevel))
        assertEquals("https://x/zoomable/img/TileGroup0/0-0-0.jpg", z.tileUrl(0, 0, 0))
        var total = 0; for (l in 0..z.maxLevel) total += z.cols(l) * z.rows(l)
        assertEquals(24316, total)                                // NUMTILES of the real archive file
        assertTrue(z.thumbnailLevel() in 0 until z.maxLevel)
        println("TILEURL " + z.tileUrl(z.maxLevel - 3, 1, 1) + " " + z.tileUrl(5, 0, 0))
    }

    @Test
    fun deepZoom() {
        val d = DeepZoomPyramid.parse("https://x/dorado_8k.dzi", "<Image Format=\"jpg\" Overlap=\"1\" TileSize=\"254\"><Size Height=\"8200\" Width=\"8200\"/></Image>")!!
        assertEquals(14, d.maxLevel)                              // 2^14 = 16384 >= 8200
        assertEquals(8200, d.levelWidth(14))
        assertEquals("https://x/dorado_8k_files/3/0_0.jpg", d.tileUrl(3, 0, 0))
        assertNotNull(d.thumbnailLevel())
        assertEquals(1, d.cols(d.thumbnailLevel()))
    }
}

class OutreachCornersTest {
    @Test
    fun cornersAreReorderedToTopLeftFirst() {
        // IC 348 from the archive: bottom-left, bottom-right, top-right, top-left (counter-clockwise on the sky chart)
        val ic = listOf(56.265245 to 32.320925, 56.364244 to 32.066126, 55.992460 to 31.962031, 55.892535 to 32.216538)
        val o = com.kroryan.astrovia.data.gallery.OutreachImage.orderCorners(ic)
        assertEquals(ic[3], o[0]); assertEquals(ic[0], o[3])
        // an already clockwise polygon is left alone
        assertEquals(o, com.kroryan.astrovia.data.gallery.OutreachImage.orderCorners(o))
    }
}
