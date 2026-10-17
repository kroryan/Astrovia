package com.kroryan.astrovia.archive

import com.kroryan.astrovia.data.archive.Csv
import com.kroryan.astrovia.data.archive.Footprints
import com.kroryan.astrovia.sky.Visibility
import com.kroryan.astrovia.sky.VisibilityConstraint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArchiveTest {
    @Test
    fun csvHandlesQuotesAndNewlines() {
        val rows = Csv.parse("a,b,c\n1,\"x,y\",\"he said \"\"hi\"\"\"\n2,\"line1\nline2\",z\n")
        assertEquals(2, rows.size)
        assertEquals("x,y", rows[0]["b"])
        assertEquals("he said \"hi\"", rows[0]["c"])
        assertEquals("line1\nline2", rows[1]["b"])
    }

    @Test
    fun footprintsPolygonAndCircle() {
        val p = Footprints.parse("Polygon ICRS 83.8 -5.0 83.9 -5.0 83.9 -5.1 83.8 -5.1")
        assertEquals(1, p.size)
        assertEquals(4, p[0].ra.size)
        val u = Footprints.parse("Union ICRS (Polygon J2000 1 1 2 1 2 2 Polygon J2000 10 10 11 10 11 11)")
        assertEquals(2, u.size)
        assertEquals(1.0, u[0].ra[0], 1e-9)
        assertEquals(1, Footprints.parse("Circle ICRS 10 20 0.5").size)
        assertTrue(Footprints.parse(null).isEmpty())
    }

    @Test
    fun sunOnEquinoxAndVisibility() {
        // 2026-03-20 ~14:46 UTC (equinox): the Sun is near RA 0, Dec 0.
        val jd = 2461120.1
        val s = Visibility.sunPosition(jd)
        assertTrue(s[1] in -0.5..0.5)
        assertTrue(s[0] < 1.0 || s[0] > 359.0)
        assertEquals(90.0, Visibility.elongation(90.0, 0.0, jd), 1.0)
        // A target on the ecliptic is visible for JWST (85-135 deg) about 2 x 50 deg of the year each way.
        val w = Visibility.windows(90.0, 0.0, VisibilityConstraint.presets[0], jd, 365)
        assertTrue(w.isNotEmpty())
        assertTrue(w.sumOf { it.days } in 80.0..120.0)
    }
}
