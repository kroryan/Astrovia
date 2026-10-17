package com.kroryan.astrovia.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    @Test
    fun centreAndOffsets() {
        val c = Planner.toSky(83.82, -5.39, 0.0, 0.0, 0.0)
        assertEquals(83.82, c[0], 1e-9); assertEquals(-5.39, c[1], 1e-9)
        val n = Planner.toSky(83.82, -5.39, 0.0, 0.0, 3600.0)   // 1 deg north
        assertEquals(-4.39, n[1], 0.001); assertEquals(83.82, n[0], 1e-6)
        val e = Planner.toSky(83.82, 0.0, 0.0, 3600.0, 0.0)     // 1 deg east on the equator
        assertEquals(84.82, e[0], 0.001)
        // PA 90: the +y axis points east
        val r = Planner.toSky(10.0, 0.0, 90.0, 0.0, 3600.0)
        assertEquals(11.0, r[0], 0.001); assertEquals(0.0, r[1], 0.001)
    }

    @Test
    fun footprintsAreClosedShapes() {
        for (f in Planner.instruments) assertTrue(f.key, f.shapes.all { it.size >= 4 })
        val o = Planner.outline(Planner.byKey("jwst_nircam")!!, 10.0, 20.0, 45.0)
        assertEquals(2, o.size)
    }
}
