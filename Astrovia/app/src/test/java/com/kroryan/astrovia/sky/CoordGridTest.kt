package com.kroryan.astrovia.sky

import com.kroryan.astrovia.model.SkyPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordGridTest {
    @Test
    fun stepsGoDownToArcseconds() {
        assertEquals(30.0, CoordGrid.stepFor(140.0), 0.0)
        assertEquals(1.0, CoordGrid.stepFor(5.0), 0.0)
        // it never went below 0.5 deg before
        assertTrue(CoordGrid.stepFor(0.5) < 0.5)
        assertTrue(CoordGrid.stepFor(0.01) < 0.01)
    }

    @Test
    fun highZoomGridIsCheap() {
        // Before: ~780,000 projections per frame at FoV <= 2 deg.
        for (fov in doubleArrayOf(2.0, 0.5, 0.05, 0.005)) {
            val proj = SkyCamera.looking(SkyPosition(83.8, -5.4), fov).projection(1080f, 2400f)
            val seg = CoordGrid.segments(proj, fov)
            val n = seg.size / 4
            println("fov=$fov -> $n segments")
            assertTrue("fov $fov: no lines", n > 0)
            assertTrue("fov $fov: $n segments", n < 6000)
        }
    }

    @Test
    fun wideAndPolarViewsDrawLines() {
        for (c in listOf(SkyPosition(10.0, 0.0), SkyPosition(10.0, 89.9), SkyPosition(10.0, -89.9), SkyPosition(359.9, 30.0))) {
            val proj = SkyCamera.looking(c, 60.0).projection(1080f, 2400f)
            assertTrue("$c", CoordGrid.segments(proj, 60.0).size / 4 > 10)
        }
    }

    @Test
    fun gridSegmentsAreFastEvenAtTheWorstCase() {
        val proj = SkyCamera.looking(SkyPosition(83.8, 0.0), 120.0).projection(1080f, 2400f)
        val t0 = System.nanoTime()
        repeat(50) { CoordGrid.segments(proj, 120.0) }
        val ms = (System.nanoTime() - t0) / 1e6 / 50
        println("grid at fov 120: $ms ms/frame")
        assertTrue("$ms ms", ms < 30.0)
    }
}
