package com.kroryan.astrovia.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyFrameTest {
    @Test
    fun galacticCentreIsInSagittarius() {
        val rd = SkyFrame.GALACTIC.toIcrsRaDec(0.0, 0.0)
        assertEquals(266.405, rd[0], 0.001)
        assertEquals(-28.936, rd[1], 0.001)
    }

    @Test
    fun northGalacticPole() {
        val rd = SkyFrame.GALACTIC.toIcrsRaDec(0.0, 90.0)
        assertEquals(192.859, rd[0], 0.001)
        assertEquals(27.128, rd[1], 0.001)
    }

    @Test
    fun rotationPreservesLengthAndIsInvertibleByConstruction() {
        val v = Vec3.fromRaDec(123.0, -45.0)
        val r = SkyFrame.GALACTIC.toIcrs(v.x, v.y, v.z)
        assertEquals(1.0, Vec3(r[0], r[1], r[2]).norm(), 1e-12)
    }

    @Test
    fun bodiesAndSkyFramesAreParsed() {
        assertEquals(SkyFrame.ICRS, SkyFrame.parse("equatorial"))
        assertEquals(SkyFrame.ICRS, SkyFrame.parse(null))
        assertEquals(SkyFrame.GALACTIC, SkyFrame.parse("galactic"))
        for (body in listOf("mars", "moon", "venus", "titan", "mars-stimson", "jupiter", "sun", "ceres", "earth")) {
            assertEquals(body, SkyFrame.BODY, SkyFrame.parse(body))
        }
    }

    @Test
    fun bodyFrameIsShownAsIs() {
        val rd = SkyFrame.BODY.toIcrsRaDec(123.0, -45.0)
        assertEquals(123.0, rd[0], 0.0)
        assertEquals(-45.0, rd[1], 0.0)
        val a = Healpix.center(3, 100, SkyFrame.BODY)
        val b = Healpix.center(3, 100, SkyFrame.ICRS)
        assertEquals(b[0], a[0], 1e-12)
        assertEquals(b[1], a[1], 1e-12)
    }

    @Test
    fun healpixGalacticTilesAreRotatedIntoIcrs() {
        val raw = Healpix.center(4, 777, SkyFrame.ICRS) // HEALPix lon/lat taken as-is
        val expected = SkyFrame.GALACTIC.toIcrsRaDec(raw[0], raw[1])
        val got = Healpix.center(4, 777, SkyFrame.GALACTIC)
        assertEquals(expected[0], got[0], 1e-9)
        assertEquals(expected[1], got[1], 1e-9)
        assertTrue(kotlin.math.abs(expected[1] - raw[1]) > 1e-3 || kotlin.math.abs(expected[0] - raw[0]) > 1e-3)
    }
}
