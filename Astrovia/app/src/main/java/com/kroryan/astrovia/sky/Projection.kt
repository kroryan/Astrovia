package com.kroryan.astrovia.sky

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Zenithal Equal Area projection (ZEA / Lambert azimuthal), the same one Aladin desktop
 * uses for most of the zoom range. Near the centre it matches TAN almost exactly and,
 * unlike TAN, its radius stays bounded, so zooming out reveals progressively more sky.
 *
 * The view is defined by an orthonormal frame:
 *  - [center]: unit vector of the sky point at the screen centre;
 *  - [up]: unit vector perpendicular to [center] that points towards the top of the screen.
 * The screen "right" axis is `center x up`. With this convention the sky is seen from the
 * inside of the celestial sphere, as in a sky chart: with north up, EAST IS ON THE LEFT.
 * (An earlier version mapped east to the right, which mirrored the whole sky.)
 *
 * `radPerPixel` = projection units (rho = 2 sin(theta/2)) per screen pixel.
 */
class Projection(
    val center: Vec3,
    val up: Vec3,
    val radPerPixel: Double,
    val widthPx: Float,
    val heightPx: Float,
) {
    val cxPx get() = widthPx / 2f
    val cyPx get() = heightPx / 2f

    private val right: Vec3 = center cross up

    /** (raDeg, decDeg) -> (screenX, screenY), or null if the point is on the far side of the view. */
    fun forward(raDeg: Double, decDeg: Double): FloatArray? {
        val d = Math.toRadians(decDeg)
        val a = Math.toRadians(raDeg)
        val cd = cos(d)
        return project(cd * cos(a), cd * sin(a), sin(d), clamp = false)
    }

    /** Unit-vector variant of [forward]. */
    fun forwardVec(v: Vec3): FloatArray? = project(v.x, v.y, v.z, clamp = false)

    /**
     * Same as [forward] but never returns null for points beyond the render cutoff: they are
     * pushed radially onto the boundary circle. Used only for tile meshes so a tile straddling
     * the visible hemisphere still gets a mesh instead of a hole.
     */
    fun forwardClamped(raDeg: Double, decDeg: Double): FloatArray {
        val d = Math.toRadians(decDeg)
        val a = Math.toRadians(raDeg)
        val cd = cos(d)
        return project(cd * cos(a), cd * sin(a), sin(d), clamp = true)!!
    }

    private fun project(px: Double, py: Double, pz: Double, clamp: Boolean): FloatArray? {
        var x = px * right.x + py * right.y + pz * right.z
        var y = px * up.x + py * up.y + pz * up.z
        var z = px * center.x + py * center.y + pz * center.z

        // Render only the front hemisphere plus a margin: z < -0.15 is more than ~98.6 deg
        // from the centre. Flat parallelograms over the back hemisphere would overlap the view.
        if (z < Z_CUTOFF) {
            if (!clamp) return null
            val rCut = sqrt(1.0 - Z_CUTOFF * Z_CUTOFF)
            val r = hypot(x, y)
            if (r > 1e-9) {
                val k = rCut / r
                x *= k
                y *= k
            } else {
                x = rCut
                y = 0.0
            }
            z = Z_CUTOFF
        }
        val w = sqrt((1.0 + z) / 2.0)
        if (w < 1e-9) return null
        return floatArrayOf(
            (cxPx + (x / w) / radPerPixel).toFloat(),
            (cyPx - (y / w) / radPerPixel).toFloat(),
        )
    }

    /** (screenX, screenY) -> (raDeg, decDeg). */
    fun inverse(sx: Float, sy: Float): DoubleArray = inverseVec(sx, sy).toRaDec()

    /** (screenX, screenY) -> unit vector. Points beyond the antipode radius are clamped to it. */
    fun inverseVec(sx: Float, sy: Float): Vec3 {
        val xi = (sx - cxPx).toDouble() * radPerPixel
        val eta = -(sy - cyPx).toDouble() * radPerPixel
        val rho = hypot(xi, eta)
        if (rho < 1e-15) return center
        // ZEA: rho = 2 sin(c/2) -> c = 2 asin(rho/2)
        val c = 2.0 * asin((rho / 2.0).coerceAtMost(1.0))
        val sinC = sin(c)
        val dir = (right * (xi / rho)) + (up * (eta / rho))
        return (center * cos(c) + dir * sinC).unit()
    }

    companion object {
        /** z of the render cutoff (about 98.6 deg from the centre). */
        const val Z_CUTOFF = -0.15

        /** Largest rho (projection radius) that is still inside the render cutoff. */
        val RHO_CUTOFF: Double = 2.0 * sin(Math.acos(Z_CUTOFF) / 2.0)

        /**
         * Projection units per pixel for a given vertical FoV: at the vertical screen edge
         * theta = fov/2, so rho_edge = 2 sin(fov/4).
         */
        fun radPerPixelFromFov(fovDeg: Double, heightPx: Float): Double {
            val fovRad = Math.toRadians(fovDeg.coerceIn(0.0001, 179.99))
            return 2.0 * sin(fovRad / 4.0) / (heightPx / 2.0)
        }
    }
}
