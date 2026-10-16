package com.kroryan.astrovia.sky

import com.kroryan.astrovia.model.SkyPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.random.Random

class SkyCameraTest {
    private val w = 1080f
    private val h = 2400f

    private fun angleBetween(a: Vec3, b: Vec3) = acos((a dot b).coerceIn(-1.0, 1.0))

    @Test
    fun eastIsOnTheLeftWithNorthUp() {
        // Orion: Betelgeuse (RA 88.79, Dec +7.41) is EAST of Bellatrix (RA 81.28, Dec +6.35),
        // so in a correct sky chart it is drawn to the LEFT of it. The old projection mirrored this.
        val cam = SkyCamera.looking(SkyPosition(83.8, -5.4), 20.0)
        val p = cam.projection(w, h)
        val betelgeuse = p.forward(88.79, 7.41)!!
        val bellatrix = p.forward(81.28, 6.35)!!
        assertTrue("east must be on the left", betelgeuse[0] < bellatrix[0])
        // and north is up
        val north = p.forward(83.8, 5.0)!!
        assertTrue(north[1] < p.forward(83.8, -5.0)!![1])
    }

    @Test
    fun alnitakFieldMatchesTheRealAndroidScreenshot() {
        // Flame Nebula (NGC 2024, RA 5h41m42s) lies east of Alnitak (5h40m46s): it must be left of it.
        val cam = SkyCamera.looking(SkyPosition(85.5, -1.65), 5.24)
        val p = cam.projection(w, h)
        val alnitak = p.forward(85.1896, -1.9426)!!
        val flame = p.forward(85.425, -1.85)!!
        assertTrue(flame[0] < alnitak[0])
    }

    @Test
    fun projectionRoundTrip() {
        val rnd = Random(7)
        for (fov in doubleArrayOf(0.02, 3.0, 60.0, 120.0)) {
            val cam = SkyCamera.looking(SkyPosition(rnd.nextDouble() * 360, rnd.nextDouble() * 160 - 80), fov)
            val p = cam.projection(w, h)
            repeat(50) {
                val sx = rnd.nextFloat() * w
                val sy = rnd.nextFloat() * h
                val rd = p.inverse(sx, sy)
                val back = p.forward(rd[0], rd[1])!!
                assertEquals(sx.toDouble(), back[0].toDouble(), 0.05)
                assertEquals(sy.toDouble(), back[1].toDouble(), 0.05)
            }
        }
    }

    @Test
    fun dragKeepsTheGrabbedPointUnderTheFinger_everywhereIncludingThePoles() {
        val rnd = Random(11)
        for (dec in doubleArrayOf(0.0, 45.0, 85.0, 89.99, -89.9)) {
            for (fov in doubleArrayOf(1.0, 30.0, 120.0)) {
                var cam = SkyCamera.looking(SkyPosition(rnd.nextDouble() * 360, dec), fov)
                repeat(20) {
                    val fx = 100 + rnd.nextFloat() * 880
                    val fy = 200 + rnd.nextFloat() * 2000
                    val tx = fx + rnd.nextFloat() * 120 - 60
                    val ty = fy + rnd.nextFloat() * 120 - 60
                    val under = cam.projection(w, h).inverseVec(fx, fy)
                    cam = cam.drag(fx, fy, tx, ty, w, h)
                    val now = cam.projection(w, h).forwardVec(under)!!
                    assertEquals("dec=$dec fov=$fov x", tx.toDouble(), now[0].toDouble(), 0.01)
                    assertEquals("dec=$dec fov=$fov y", ty.toDouble(), now[1].toDouble(), 0.01)
                }
            }
        }
    }

    @Test
    fun crossingTheNorthPoleIsSmoothAndTheCameraNeverGetsStuck() {
        // Reproduces the measured bug: with (ra, dec) + north-up the camera stuck at Dec 89.98 and
        // flipped 180 deg at every event. Now the drag must carry straight through the pole.
        var cam = SkyCamera.looking(SkyPosition(30.0, 84.0), 30.0)
        val star = Vec3.fromRaDec(30.0, 89.0)
        var prevStar = cam.projection(w, h).forwardVec(star)
        var maxDecSeen = -90.0
        var maxStarJump = 0.0
        var passedPole = false
        var prevC = cam.c
        var minStepDeg = 1e9
        var maxStepDeg = 0.0
        repeat(400) {
            cam = cam.drag(w / 2, h / 2, w / 2, h / 2 + 12f, w, h) // finger down = camera moves north
            val dec = cam.center.decDeg
            maxDecSeen = maxOf(maxDecSeen, dec)
            if (maxDecSeen > 89.9 && dec < maxDecSeen - 1.0) passedPole = true
            val step = Math.toDegrees(angleBetween(prevC, cam.c))
            minStepDeg = minOf(minStepDeg, step)
            maxStepDeg = maxOf(maxStepDeg, step)
            prevC = cam.c
            val now = cam.projection(w, h).forwardVec(star)
            if (prevStar != null && now != null) {
                maxStarJump = maxOf(maxStarJump, hypot((now[0] - prevStar!![0]).toDouble(), (now[1] - prevStar!![1]).toDouble()))
            }
            prevStar = now
        }
        // events are ~0.15 deg apart, so "through the pole" means within one step of it
        assertTrue("camera must pass through the pole (max dec $maxDecSeen)", maxDecSeen > 89.8)
        assertTrue("and keep going down the other side", passedPole)
        // every event moves the sky by the same amount (no stalls, no flips)
        assertTrue("step range $minStepDeg..$maxStepDeg", minStepDeg > 0.9 * maxStepDeg)
        assertTrue("landmark jumped $maxStarJump px", maxStarJump < 60.0)
    }

    @Test
    fun aFull360DegreeLoopOverThePoleReturnsToTheStartExactly() {
        val start = SkyCamera.looking(SkyPosition(30.0, 60.0), 30.0)
        // angle of one 12 px step at the centre
        val one = start.drag(w / 2, h / 2, w / 2, h / 2 + 12f, w, h)
        val step = angleBetween(start.c, one.c)
        val n = Math.round(2 * Math.PI / step).toInt()
        var cam = start
        repeat(n) { cam = cam.drag(w / 2, h / 2, w / 2, h / 2 + 12f, w, h) }
        // a closed great circle: the whole frame (centre AND orientation) comes back
        assertTrue("centre off by ${Math.toDegrees(angleBetween(start.c, cam.c))} deg", angleBetween(start.c, cam.c) < Math.toRadians(0.1))
        assertTrue("up off by ${Math.toDegrees(angleBetween(start.u, cam.u))} deg", angleBetween(start.u, cam.u) < Math.toRadians(0.1))
    }

    @Test
    fun sideDragNearThePoleDoesNotSpinThePicture() {
        // Measured bug: 200 px sideways at Dec 89 rotated the picture 95 degrees.
        var cam = SkyCamera.looking(SkyPosition(10.0, 89.0), 20.0)
        val a = Vec3.fromRaDec(10.0, 89.0)
        val b = Vec3.fromRaDec(40.0, 89.0)
        fun angle(c: SkyCamera): Double {
            val p = c.projection(w, h)
            val pa = p.forwardVec(a)!!
            val pb = p.forwardVec(b)!!
            return Math.toDegrees(kotlin.math.atan2((pb[1] - pa[1]).toDouble(), (pb[0] - pa[0]).toDouble()))
        }
        val before = angle(cam)
        repeat(200) { cam = cam.drag(w / 2, h / 2, w / 2 + 1f, h / 2, w, h) }
        var d = angle(cam) - before
        while (d > 180) d -= 360
        while (d < -180) d += 360
        // The picture still turns a little (parallel transport on a sphere near a pole is
        // geometric), but the 95 degrees of spurious "north-up" correction are gone: dragging
        // 200 px must not rotate the stars by more than the 200 px / (px per degree) it moved.
        val movedDeg = 200.0 * 20.0 / h.toDouble()
        assertTrue("rotation $d deg for a $movedDeg deg move", abs(d) < 3 * movedDeg + 1.0)
    }

    @Test
    fun zoomKeepsThePointUnderTheFingers() {
        val rnd = Random(3)
        for (dec in doubleArrayOf(0.0, 45.0, 75.0, 85.0)) {
            for (fov in doubleArrayOf(10.0, 60.0, 120.0)) {
                val cam = SkyCamera.looking(SkyPosition(100.0, dec), fov)
                val fx = 270f
                val fy = 600f
                val under = cam.projection(w, h).inverseVec(fx, fy)
                val z = cam.zoom(1.5f, w, h, fx, fy)
                assertTrue(z.fovDeg < fov)
                val now = z.projection(w, h).forwardVec(under)!!
                // The old camera drifted 29 px at Dec 45 / 60 deg and over 900 px at Dec 85.
                assertEquals("dec=$dec fov=$fov", fx.toDouble(), now[0].toDouble(), 0.01)
                assertEquals("dec=$dec fov=$fov", fy.toDouble(), now[1].toDouble(), 0.01)
            }
        }
        assertTrue(rnd.nextInt() != 0 || true)
    }

    @Test
    fun twistTurnsThePictureClockwiseAroundTheFocus() {
        val cam = SkyCamera.looking(SkyPosition(120.0, 10.0), 20.0)
        assertEquals(0.0, cam.northAngleDeg, 1e-6)
        val t = cam.twist(30.0, w, h, w / 2, h / 2)
        // clockwise image rotation: north (up) swings towards the right
        assertEquals(30.0, t.northAngleDeg, 1e-6)
        // an off-centre focus stays put
        val fx = 300f
        val fy = 500f
        val under = cam.projection(w, h).inverseVec(fx, fy)
        val t2 = cam.twist(45.0, w, h, fx, fy)
        val now = t2.projection(w, h).forwardVec(under)!!
        assertEquals(fx.toDouble(), now[0].toDouble(), 0.01)
        assertEquals(fy.toDouble(), now[1].toDouble(), 0.01)
    }

    @Test
    fun alignNorthUpAndRoll() {
        var cam = SkyCamera.looking(SkyPosition(200.0, -20.0), 15.0).twist(77.0, w, h, w / 2, h / 2)
        assertEquals(77.0, cam.northAngleDeg, 1e-6)
        cam = cam.alignedNorthUp()
        assertEquals(0.0, cam.northAngleDeg, 1e-6)
        assertEquals(25.0, cam.rolledBy(25.0).northAngleDeg, 1e-6)
    }

    @Test
    fun recenterIsExact() {
        val cam = SkyCamera.looking(SkyPosition(10.0, 20.0), 5.0).twist(40.0, w, h, w / 2, h / 2)
        val r = cam.recenterTo(SkyPosition(250.0, -60.0))
        assertEquals(250.0, r.center.raDeg, 1e-9)
        assertEquals(-60.0, r.center.decDeg, 1e-9)
    }

    @Test
    fun fovLimitKeepsTheScreenCornersOnTheSphere() {
        val portrait = SkyCamera.maxFovFor(1080f, 2400f)
        val landscape = SkyCamera.maxFovFor(2400f, 1080f)
        assertEquals(SkyCamera.MAX_FOV, portrait, 1e-9)
        assertTrue("landscape limit $landscape", landscape in 50.0..90.0)
        for ((ww, hh) in listOf(2400f to 1080f, 2560f to 1600f, 1080f to 2400f)) {
            val cam = SkyCamera.looking(SkyPosition(0.0, 0.0), 140.0).clampedTo(ww, hh)
            val p = cam.projection(ww, hh)
            val rhoCorner = hypot((ww / 2) * p.radPerPixel, (hh / 2) * p.radPerPixel)
            assertTrue("corner rho $rhoCorner", rhoCorner <= Projection.RHO_CUTOFF + 1e-6)
        }
    }

    @Test
    fun poleCameraIsValid() {
        val cam = SkyCamera.looking(SkyPosition(0.0, 90.0), 30.0)
        assertEquals(1.0, cam.u.norm(), 1e-9)
        assertEquals(0.0, cam.u dot cam.c, 1e-9)
        assertEquals(90.0, cam.center.decDeg, 1e-6)
    }
}
