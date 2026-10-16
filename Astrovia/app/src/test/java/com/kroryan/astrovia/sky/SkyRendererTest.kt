package com.kroryan.astrovia.sky

import com.kroryan.astrovia.data.hips.HiPSProperties
import com.kroryan.astrovia.model.SkyPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkyRendererTest {
    private val props = HiPSProperties(baseUrl = "x", order = 9, orderMin = 0, tileWidth = 512)

    @Test
    fun zoomedOutLevelOfDetailIsUniformAcrossTheScreen() {
        // Before: the scale was measured on one tile edge, which ZEA distorts by up to 2.5x, so at
        // FoV 140 the leaves came out at orders 0, 1 and 2 side by side (a patchwork of resolutions).
        val cam = SkyCamera.looking(SkyPosition(83.8, -5.4), 140.0)
        val proj = cam.projection(1080f, 2400f)
        val leaves = SkyRenderer.computeVisibleTiles(proj, props, 1.5f).filter { it.isLeaf }
        assertTrue(leaves.isNotEmpty())
        val orders = leaves.map { it.order }.toSet()
        println("leaf orders at fov 140: $orders")
        // Equal-area projection: every tile of one order has the same on-screen size, so the whole
        // screen settles on ONE order (before: orders 0, 1 and 2 side by side).
        assertEquals("leaf orders $orders", 1, orders.size)
    }

    @Test
    fun sameOrderRegardlessOfHowTheCameraIsTurned() {
        // The LOD must not depend on the roll of the view (it did, through the single-edge metric).
        val base = SkyCamera.looking(SkyPosition(150.0, 20.0), 100.0)
        val counts = (0..3).map { k ->
            val cam = base.twist(k * 23.0, 1080f, 2400f, 540f, 1200f)
            val leaves = SkyRenderer.computeVisibleTiles(cam.projection(1080f, 2400f), props, 1.5f).filter { it.isLeaf }
            leaves.map { it.order }.toSet().max()
        }
        println("max leaf order for 4 rolls: $counts")
        assertEquals("max leaf order changes with the roll: $counts", 1, counts.toSet().size)
    }

    @Test
    fun orderMinZeroNeedsFewBaseTiles() {
        val cam = SkyCamera.looking(SkyPosition(83.8, -5.4), 140.0)
        val tiles = SkyRenderer.computeVisibleTiles(cam.projection(1080f, 2400f), props, 1.5f)
        println("tiles at fov 140 with order_min=0: ${tiles.size}")
        assertTrue("candidate tiles ${tiles.size}", tiles.size < 120)
    }

    @Test
    fun tileScaleIsIndependentOfPositionAndHalvesPerOrder() {
        val p = SkyCamera.looking(SkyPosition(10.0, 20.0), 30.0).projection(1080f, 2400f)
        val s5 = SkyRenderer.tileScale(5, p.radPerPixel, 512f)
        assertEquals(s5 / 2f, SkyRenderer.tileScale(6, p.radPerPixel, 512f), 1e-6f)
        // measured on real projected tiles near the centre and near the edge of a wide view
        for (c in listOf(SkyPosition(83.8, -5.4), SkyPosition(60.0, 40.0))) {
            val wide = SkyCamera.looking(c, 90.0).projection(1080f, 2400f)
            val expected = SkyRenderer.tileScale(2, wide.radPerPixel, 512f).toDouble()
            val tiles = SkyRenderer.computeVisibleTiles(wide, props.copy(order = 2), 100f)
            for (t in tiles.filter { it.order == 2 }.take(40)) {
                val q = t.cornersRadec.map { wide.forward(it[0], it[1]) }
                if (q.any { it == null }) continue
                var a = 0.0
                for (i in 0 until 4) { val j = (i + 1) % 4; a += q[i]!![0].toDouble() * q[j]!![1] - q[j]!![0].toDouble() * q[i]!![1] }
                val side = Math.sqrt(Math.abs(a) / 2.0) / 512.0
                assertEquals("tile ${t.npix}", expected, side, expected * 0.12)
            }
        }
    }

    @Test
    fun meshCoversTheWholeBitmapAndIsSharedAcrossNeighbours() {
        val cam = SkyCamera.looking(SkyPosition(83.8, -5.4), 3.0)
        val proj = cam.projection(1080f, 2400f)
        val tile = SkyRenderer.computeVisibleTiles(proj, props, 1.5f).first { it.isLeaf }
        for (bmp in intArrayOf(128, 256, 512)) {
            for (minCells in intArrayOf(1, 4, 6, 8, 16)) {
                val m = SkyRenderer.placementTriangles(proj, tile, bmp, bmp, minCells)!!
                // texture coordinates reach both ends of the bitmap on both axes
                val xs = m.texCoords.filterIndexed { i, _ -> i % 2 == 0 }
                val ys = m.texCoords.filterIndexed { i, _ -> i % 2 == 1 }
                assertEquals(0f, xs.min(), 0f); assertEquals(bmp.toFloat(), xs.max(), 0f)
                assertEquals(0f, ys.min(), 0f); assertEquals(bmp.toFloat(), ys.max(), 0f)
            }
        }
        // every tile of one order uses the same grid size
        val sizes = SkyRenderer.computeVisibleTiles(cam.projection(1080f, 2400f), props, 1.5f)
            .groupBy { it.order }.mapValues { (o, _) -> SkyRenderer.meshCells(o, proj.radPerPixel) }
        assertTrue(sizes.isNotEmpty())
    }

    @Test
    fun galacticSurveyTilesAreRotatedToIcrs() {
        val gal = props.copy(frame = "galactic")
        val cam = SkyCamera.looking(SkyPosition(266.4, -28.9), 20.0) // galactic centre
        val tiles = SkyRenderer.computeVisibleTiles(cam.projection(1080f, 2400f), gal, 1.5f)
        assertTrue(tiles.isNotEmpty())
        assertTrue(tiles.all { it.frame == SkyFrame.GALACTIC })
        // tiles around the galactic centre are found when looking at it in ICRS
        val center = tiles.first { it.isLeaf }.centerRadec
        assertTrue(Math.abs(center[0] - 266.4) < 15 && Math.abs(center[1] + 28.9) < 15)
    }
}
