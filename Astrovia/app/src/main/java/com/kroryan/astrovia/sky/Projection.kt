package com.kroryan.astrovia.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** Projection family: [HAMMER] (all-sky oval, like Aladin's Aitoff) for sky surveys, [ZEA] (a disc) for planets. */
enum class ProjectionKind { HAMMER, ZEA }

/**
 * Sky projection, always centred on the view. Both families are EQUAL-AREA, so a HEALPix tile of a given
 * order covers the same number of screen pixels wherever it is and the level of detail is uniform.
 *
 *  - [ProjectionKind.HAMMER] (Hammer-Aitoff): the whole sky fits an oval when zoomed out (as in Aladin
 *    desktop) and looks like a plain flat map when zoomed in. Used for sky surveys.
 *  - [ProjectionKind.ZEA]: Zenithal Equal Area projection (ZEA / Lambert azimuthal), the same one Aladin desktop
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
    val kind: ProjectionKind = ProjectionKind.ZEA,
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

        if (kind == ProjectionKind.HAMMER) return hammer(x, y, z)

        // Render only the front hemisphere plus a margin: z < -0.15 is more than ~98.6 deg
        // from the centre. Flat parallelograms over the back hemisphere would overlap the view.
        if (z < ZEA_Z_CUTOFF) {
            if (!clamp) return null
            val rCut = sqrt(1.0 - ZEA_Z_CUTOFF * ZEA_Z_CUTOFF)
            val r = hypot(x, y)
            if (r > 1e-9) {
                val k = rCut / r
                x *= k
                y *= k
            } else {
                x = rCut
                y = 0.0
            }
            z = ZEA_Z_CUTOFF
        }
        val w = sqrt((1.0 + z) / 2.0)
        if (w < 1e-9) return null
        return floatArrayOf(
            (cxPx + (x / w) / radPerPixel).toFloat(),
            (cyPx - (y / w) / radPerPixel).toFloat(),
        )
    }

    /** Hammer-Aitoff of a unit vector given in the (right, up, centre) frame. Never null: the whole sphere maps. */
    private fun hammer(x: Double, y: Double, z: Double): FloatArray {
        val phi = asin(y.coerceIn(-1.0, 1.0))
        val lam = atan2(x, z)
        val cp = cos(phi)
        val d = sqrt(1.0 + cp * cos(lam / 2.0))
        val hx = 2.0 * SQRT2 * cp * sin(lam / 2.0) / d
        val hy = SQRT2 * sin(phi) / d
        return floatArrayOf((cxPx + hx / radPerPixel).toFloat(), (cyPx - hy / radPerPixel).toFloat())
    }

    /** (screenX, screenY) -> (raDeg, decDeg). */
    fun inverse(sx: Float, sy: Float): DoubleArray = inverseVec(sx, sy).toRaDec()

    /** (screenX, screenY) -> unit vector. Points beyond the antipode radius are clamped to it. */
    fun inverseVec(sx: Float, sy: Float): Vec3 {
        val xi = (sx - cxPx).toDouble() * radPerPixel
        val eta = -(sy - cyPx).toDouble() * radPerPixel
        if (kind == ProjectionKind.HAMMER) return inverseHammer(xi, eta)
        val rho = hypot(xi, eta)
        if (rho < 1e-15) return center
        // ZEA: rho = 2 sin(c/2) -> c = 2 asin(rho/2)
        val c = 2.0 * asin((rho / 2.0).coerceAtMost(1.0))
        val sinC = sin(c)
        val dir = (right * (xi / rho)) + (up * (eta / rho))
        return (center * cos(c) + dir * sinC).unit()
    }

    /**
     * True when part of the oval's border (the sky behind the view) is on screen. With the sphere's
     * far side in view no angular cut is valid.
     */
    internal fun hammerBorderVisible(marginPx: Float): Boolean {
        // the oval is centred on the screen centre: its half extents in px
        val halfW = HAMMER_HALF_W / radPerPixel
        val halfH = HAMMER_HALF_H / radPerPixel
        val cw = (widthPx / 2f + marginPx).toDouble()
        val ch = (heightPx / 2f + marginPx).toDouble()
        // the farthest screen corner is outside the oval => the border crosses the screen
        return (cw / halfW) * (cw / halfW) + (ch / halfH) * (ch / halfH) >= 1.0
    }

    private fun inverseHammer(hx0: Double, hy0: Double): Vec3 {
        var hx = hx0
        var hy = hy0
        var t = (hx / 4.0) * (hx / 4.0) + (hy / 2.0) * (hy / 2.0)
        if (t > 0.4999) { // outside the oval (its border is t = 1/2): nearest point of its border
            val k = sqrt(0.49995 / t)
            hx *= k
            hy *= k
            t = (hx / 4.0) * (hx / 4.0) + (hy / 2.0) * (hy / 2.0)
        }
        val z = sqrt(1.0 - t)
        val lam = 2.0 * atan2(z * hx, 2.0 * (2.0 * z * z - 1.0))
        val phi = asin((hy * z).coerceIn(-1.0, 1.0))
        val cp = cos(phi)
        return ((right * (cp * sin(lam))) + (up * sin(phi)) + (center * (cp * cos(lam)))).unit()
    }

    companion object {
        private val SQRT2 = sqrt(2.0)

        /**
         * z of the ZEA render cutoff: the front hemisphere. Zoomed out the visible sky is a disc, like a
         * planet seen from space; anything behind it is not drawn.
         */
        const val ZEA_Z_CUTOFF = 0.0

        /** Largest rho of the ZEA disc (the limb, 90 deg from the centre). */
        val RHO_CUTOFF: Double = 2.0 * sin(Math.PI / 4.0)

        /** Half-width / half-height of the Hammer oval in projection units. */
        val HAMMER_HALF_W = 2.0 * SQRT2
        val HAMMER_HALF_H = SQRT2

        /**
         * Projection units per pixel for a given vertical FoV: near the centre one projection unit
         * is one radian (both projections), so the vertical extent is fov radians. Unlike a
         * formula based on the sphere, this also works for FoVs above 180 deg, where the whole
         * oval/disc is on screen.
         */
        fun radPerPixelFromFov(fovDeg: Double, heightPx: Float): Double =
            Math.toRadians(fovDeg.coerceAtLeast(0.0001)) / heightPx.toDouble()
    }
}
