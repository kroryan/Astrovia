package com.kroryan.astrovia.sky

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * Coordinate grid (meridians and parallels) as screen-space line segments.
 *
 * The previous implementation sampled every meridian and parallel along the WHOLE sphere
 * (about 780,000 projections per frame at high zoom) and could not go below 0.5 deg. This one
 * works out which RA/Dec range is actually on screen and only samples that, with steps that go
 * down to arcseconds.
 */
object CoordGrid {
    /** Grid steps in degrees: 1" ... 90 deg. */
    val STEPS_DEG = doubleArrayOf(
        1.0 / 3600, 2.0 / 3600, 5.0 / 3600, 10.0 / 3600, 30.0 / 3600,
        1.0 / 60, 2.0 / 60, 5.0 / 60, 10.0 / 60, 30.0 / 60,
        1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 45.0, 90.0,
    )

    /** Smallest step that leaves at most ~6 lines across the field of view. */
    fun stepFor(fovDeg: Double): Double {
        for (s in STEPS_DEG) if (fovDeg / s <= 6.0) return s
        return 90.0
    }

    /**
     * @return flat array of segments [x1, y1, x2, y2, ...] in screen pixels.
     */
    fun segments(proj: Projection, fovDeg: Double, maxSegments: Int = 20000): FloatArray {
        val w = proj.widthPx
        val h = proj.heightPx
        val step = stepFor(fovDeg)

        // 1) RA/Dec range covered by the screen (sampled on a 9x9 grid of screen points).
        var decMin = 90.0
        var decMax = -90.0
        val raRel = ArrayList<Double>()
        val centerRaDec = proj.inverse(w / 2f, h / 2f)
        for (iy in 0..8) {
            for (ix in 0..8) {
                val rd = proj.inverse(w * ix / 8f, h * iy / 8f)
                decMin = min(decMin, rd[1])
                decMax = max(decMax, rd[1])
                raRel.add(((rd[0] - centerRaDec[0] + 540.0) % 360.0) - 180.0)
            }
        }
        // Pole on screen -> every meridian converges there and RA spans the full circle.
        val poleVisible = poleOnScreen(proj, +90.0) || poleOnScreen(proj, -90.0)
        if (poleOnScreen(proj, +90.0)) decMax = 90.0
        if (poleOnScreen(proj, -90.0)) decMin = -90.0
        val raLo: Double
        val raHi: Double
        if (poleVisible || (raRel.maxOrNull()!! - raRel.minOrNull()!!) > 300.0) {
            raLo = 0.0
            raHi = 360.0
        } else {
            val pad = step.coerceAtLeast((raRel.maxOrNull()!! - raRel.minOrNull()!!) * 0.1)
            raLo = centerRaDec[0] + raRel.minOrNull()!! - pad
            raHi = centerRaDec[0] + raRel.maxOrNull()!! + pad
        }
        val decPad = step.coerceAtLeast((decMax - decMin) * 0.1)
        val dLo = max(-90.0, decMin - decPad)
        val dHi = min(90.0, decMax + decPad)

        val out = FloatArray(maxSegments * 4)
        var n = 0
        var prevX = 0f
        var prevY = 0f
        var hasPrev = false
        // The oval's seam (the far side of the sky) puts neighbouring samples on opposite edges.
        val maxJump = if (proj.kind == ProjectionKind.HAMMER) w * 0.3f else max(w, h) * 1.5f

        fun addPoint(p: FloatArray?) {
            if (p == null) {
                hasPrev = false
                return
            }
            if (hasPrev && n < maxSegments &&
                abs(p[0] - prevX) + abs(p[1] - prevY) < maxJump &&
                (inside(prevX, prevY, w, h) || inside(p[0], p[1], w, h))
            ) {
                out[n * 4] = prevX
                out[n * 4 + 1] = prevY
                out[n * 4 + 2] = p[0]
                out[n * 4 + 3] = p[1]
                n++
            }
            prevX = p[0]
            prevY = p[1]
            hasPrev = true
        }

        // 2) Meridians: constant RA, sampled over the visible Dec range only.
        val decSamples = ((dHi - dLo) / min(step, (dHi - dLo) / 48.0).coerceAtLeast(1e-9)).toInt().coerceIn(8, 96)
        var ra = ceil(raLo / step) * step
        var guard = 0
        while (ra <= raHi && guard++ < 400) {
            hasPrev = false
            for (k in 0..decSamples) {
                val dec = dLo + (dHi - dLo) * k / decSamples
                addPoint(proj.forward(((ra % 360.0) + 360.0) % 360.0, dec))
            }
            ra += step
        }

        // 3) Parallels: constant Dec, sampled over the visible RA range only.
        val span = raHi - raLo
        var dec = ceil(dLo / step) * step
        guard = 0
        while (dec <= dHi && guard++ < 400) {
            if (abs(dec) < 90.0 - 1e-9) {
                // More samples where the parallel is short in RA terms (high |dec|).
                val raSamples = (48 + (60 * (1.0 - cos(Math.toRadians(dec)).coerceAtLeast(0.05)))).toInt().coerceIn(24, 120)
                hasPrev = false
                for (k in 0..raSamples) {
                    val r = raLo + span * k / raSamples
                    addPoint(proj.forward(((r % 360.0) + 360.0) % 360.0, dec))
                }
            }
            dec += step
        }
        return out.copyOf(n * 4)
    }

    private fun inside(x: Float, y: Float, w: Float, h: Float): Boolean {
        val m = 200f
        return x > -m && x < w + m && y > -m && y < h + m
    }

    private fun poleOnScreen(proj: Projection, decDeg: Double): Boolean {
        val p = proj.forward(0.0, decDeg) ?: return false
        return p[0] in 0f..proj.widthPx && p[1] in 0f..proj.heightPx
    }

}
