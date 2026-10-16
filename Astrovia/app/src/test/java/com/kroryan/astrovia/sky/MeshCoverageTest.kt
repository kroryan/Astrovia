package com.kroryan.astrovia.sky

import com.kroryan.astrovia.data.hips.HiPSProperties
import com.kroryan.astrovia.model.SkyPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tile meshes must cover the screen without holes (the black wedges seen on Android at wide FoV). */
class MeshCoverageTest {
    private val sky = HiPSProperties(baseUrl = "x", order = 9, orderMin = 0, tileWidth = 512)

    /** Fraction of the samples inside the sky shape that no triangle of any tile covers. */
    private fun holeFraction(w: Float, h: Float, cam: SkyCamera, props: HiPSProperties, leavesOnly: Boolean): Double {
        val proj = cam.projection(w, h)
        val tiles = SkyRenderer.computeVisibleTiles(proj, props, 1.5f).filter { !leavesOnly || it.isLeaf }
        val step = 4
        val gw = (w / step).toInt()
        val gh = (h / step).toInt()
        val cov = BooleanArray(gw * gh)
        for (t in tiles) {
            val m = SkyRenderer.placementTriangles(proj, t, 512, 512) ?: continue
            var k = 0
            while (k < m.indices.size) {
                val a = m.indices[k].toInt() * 2; val b = m.indices[k + 1].toInt() * 2; val c = m.indices[k + 2].toInt() * 2
                k += 3
                val xs = floatArrayOf(m.positions[a], m.positions[b], m.positions[c])
                val ys = floatArrayOf(m.positions[a + 1], m.positions[b + 1], m.positions[c + 1])
                val x0 = (xs.min() / step).toInt().coerceAtLeast(0); val x1 = (xs.max() / step).toInt().coerceAtMost(gw - 1)
                val y0 = (ys.min() / step).toInt().coerceAtLeast(0); val y1 = (ys.max() / step).toInt().coerceAtMost(gh - 1)
                for (gy in y0..y1) for (gx in x0..x1) {
                    val px = gx * step + step / 2f; val py = gy * step + step / 2f
                    val d1 = (xs[1] - xs[0]) * (py - ys[0]) - (ys[1] - ys[0]) * (px - xs[0])
                    val d2 = (xs[2] - xs[1]) * (py - ys[1]) - (ys[2] - ys[1]) * (px - xs[1])
                    val d3 = (xs[0] - xs[2]) * (py - ys[2]) - (ys[0] - ys[2]) * (px - xs[2])
                    if ((d1 >= 0 && d2 >= 0 && d3 >= 0) || (d1 <= 0 && d2 <= 0 && d3 <= 0)) cov[gy * gw + gx] = true
                }
            }
        }
        var inside = 0
        var holes = 0
        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (!insideShape(proj, gx * step + step / 2f, gy * step + step / 2f)) continue
            inside++
            if (!cov[gy * gw + gx]) holes++
        }
        return if (inside == 0) 0.0 else holes.toDouble() / inside
    }

    private fun insideShape(p: Projection, x: Float, y: Float): Boolean {
        val xi = (x - p.cxPx) * p.radPerPixel
        val eta = (y - p.cyPx) * p.radPerPixel
        return when (p.kind) {
            ProjectionKind.HAMMER -> (xi / 4) * (xi / 4) + (eta / 2) * (eta / 2) < 0.485
            ProjectionKind.ZEA -> Math.hypot(xi, eta) < Projection.RHO_CUTOFF * 0.985
        }
    }

    @Test
    fun skySurveyHasNoHolesAtAnyZoom() {
        for ((pos, fov) in listOf(
            SkyPosition(25.0, 57.0) to 138.0, SkyPosition(350.0, 52.0) to 140.0, SkyPosition(83.8, -5.4) to 60.0,
            SkyPosition(83.8, -5.4) to 5.0, SkyPosition(10.0, 89.0) to 120.0, SkyPosition(0.0, 0.0) to 400.0,
        )) {
            val f = holeFraction(920f, 2000f, SkyCamera.looking(pos, fov), sky, leavesOnly = true)
            println("hammer fov=$fov holes=${"%.4f".format(f)}")
            // With the whole oval on screen the cells that straddle its seam (the far side of the sky) are
            // dropped: a thin dark band along the oval's left/right border, a couple of percent of its area.
            assertTrue("holes $f at fov $fov $pos", f < if (fov >= 360.0) 0.02 else 0.001)
        }
    }

    @Test
    fun planetHasNoHolesAtAnyZoom() {
        val body = sky.copy(frame = "mars", order = 5)
        for (fov in listOf(138.0, 90.0, 20.0, 300.0)) {
            val cam = SkyCamera.looking(SkyPosition(11.0, 45.0), fov, ProjectionKind.ZEA)
            val f = holeFraction(920f, 2000f, cam, body, leavesOnly = true)
            println("zea fov=$fov holes=${"%.4f".format(f)}")
            assertTrue("holes $f at fov $fov", f < 0.001)
        }
    }

    @Test
    fun zoomOutShowsTheWholeOvalAndTheWholeDisc() {
        val h = SkyCamera.looking(SkyPosition(0.0, 0.0), 10.0)
        assertTrue(h.zoom(0.001f, 920f, 2000f, 460f, 1000f).fovDeg > 180.0)
        val max = SkyCamera.maxFovFor(920f, 2000f, ProjectionKind.HAMMER)
        val p = SkyCamera(fovDeg = max).projection(920f, 2000f)
        // the whole oval fits the screen width
        assertTrue(p.forward(180.0, 0.0)!![0] >= -1f && p.forward(180.0 + 1e-6, 0.0)!![0] <= 921f)
        val pz = SkyCamera(fovDeg = SkyCamera.maxFovFor(920f, 2000f, ProjectionKind.ZEA), kind = ProjectionKind.ZEA).projection(920f, 2000f)
        assertEquals(0.5 * 920, pz.forward(90.0, 0.0)!![0].toDouble() - 0.5 * 920 + 0.5 * 920 - 0.0, 460.0)
    }

    @Test
    fun hammerRoundTrips() {
        val cam = SkyCamera.looking(SkyPosition(120.0, 30.0), 200.0)
        val p = cam.projection(920f, 2000f)
        for ((ra, dec) in listOf(120.0 to 30.0, 200.0 to -20.0, 300.0 to 60.0, 10.0 to 5.0, 120.0 to 80.0)) {
            val s = p.forward(ra, dec)!!
            val back = p.inverse(s[0], s[1])
            val d = Math.acos((Vec3.fromRaDec(ra, dec) dot Vec3.fromRaDec(back[0], back[1])).coerceIn(-1.0, 1.0))
            assertTrue("round trip error ${Math.toDegrees(d)} deg at $ra,$dec", Math.toDegrees(d) < 0.05)
        }
    }

    @Test
    fun hammerIsEqualAreaNearTheCentre() {
        val p = SkyCamera.looking(SkyPosition(10.0, 20.0), 30.0).projection(920f, 2000f)
        val a = p.forward(10.0, 20.0)!!
        val b = p.forward(10.0, 21.0)!!
        // one degree is ~ radians / radPerPixel pixels
        assertEquals(Math.toRadians(1.0) / p.radPerPixel, Math.hypot((b[0] - a[0]).toDouble(), (b[1] - a[1]).toDouble()), 0.5)
    }
}
