package com.kroryan.astrovia.data.hips

import com.kroryan.astrovia.sky.SkyFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiPSPropertiesTest {
    @Test
    fun formatPrefersJpegThenPngThenWebpThenFits() {
        assertEquals("jpg", HiPSPropertiesParser.pickFormat("png jpeg fits"))
        assertEquals("jpg", HiPSPropertiesParser.pickFormat("jpeg"))
        assertEquals("png", HiPSPropertiesParser.pickFormat("fits png"))   // 103 real surveys list fits first
        assertEquals("png", HiPSPropertiesParser.pickFormat("png fits"))
        assertEquals("png", HiPSPropertiesParser.pickFormat("webp png"))
        assertEquals("webp", HiPSPropertiesParser.pickFormat("webp"))
        assertEquals("fits", HiPSPropertiesParser.pickFormat("fits"))      // 12 FITS-only surveys: now decoded
        assertEquals("jpg", HiPSPropertiesParser.pickFormat(null))
    }

    @Test
    fun orderMinDefaultsToZeroAsInTheStandard() {
        val p = HiPSPropertiesParser.parse("u", "hips_order = 9\nhips_tile_width = 512\n")
        assertEquals(0, p.orderMin)
        assertEquals(9, p.order)
        assertEquals(3, HiPSPropertiesParser.parse("u", "hips_order_min = 3").orderMin)
    }

    @Test
    fun framesAndInitialPosition() {
        val p = HiPSPropertiesParser.parse(
            "u",
            "hips_frame = galactic\nhips_initial_ra = 0\nhips_initial_dec = 0\nhips_initial_fov = 90\n",
        )
        assertEquals(SkyFrame.GALACTIC, p.skyFrame)
        // the initial position is published in the survey's own frame: (l,b)=(0,0) is Sagittarius
        assertEquals(266.405, p.initialIcrs[0], 0.001)
        assertEquals(SkyFrame.BODY, HiPSPropertiesParser.parse("u", "hips_frame = mars").skyFrame)
        assertEquals(SkyFrame.ICRS, HiPSPropertiesParser.parse("u", "").skyFrame)
    }

    @Test
    fun pixelCutIsParsed() {
        val p = HiPSPropertiesParser.parse("u", "hips_pixel_cut = 1249 10940\nhips_tile_format = jpeg fits")
        assertEquals(1249.0, p.pixelCutMin!!, 0.0)
        assertEquals(10940.0, p.pixelCutMax!!, 0.0)
        assertNull(HiPSPropertiesParser.parse("u", "").pixelCutMin)
    }

    @Test
    fun extMapping() {
        assertEquals("jpg", HiPSProperties("u", tileFormat = "jpg").ext)
        assertEquals("fits", HiPSProperties("u", tileFormat = "fits").ext)
        assertEquals("webp", HiPSProperties("u", tileFormat = "webp").ext)
    }

    @Test
    fun builtInSurveysHaveUniqueIdsAndHttpsUrls() {
        val ids = SurveyRegistry.builtIn.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assert(SurveyRegistry.builtIn.all { it.baseUrl.startsWith("https://") })
        assert(SurveyRegistry.builtIn.all { it.id.contains('/') }) // registry-style IDs
    }

    @Test
    fun tileUrlFollowsTheDirectoryRule() {
        val s = HiPSSurvey("id", "t", "https://h/x")
        assertEquals("https://h/x/Norder3/Dir0/Npix300.jpg", s.tileUrl(3, 300, "jpg"))
        assertEquals("https://h/x/Norder9/Dir20000/Npix25000.png", s.tileUrl(9, 25000, "png"))
    }
}
