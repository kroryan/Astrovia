package com.kroryan.astrovia.sky

import com.kroryan.astrovia.model.SkyPosition
import kotlin.math.atan2

/**
 * Sky camera: an orthonormal frame ([c] = where we look, [u] = screen up) plus the vertical FoV.
 *
 * The orientation is stored as a 3D frame, NOT as (ra, dec, rotation). The (ra, dec) form has a
 * singularity at the celestial poles: with "north up" enforced, crossing a pole flips the whole
 * view by 180 deg and a pan near it spins the picture wildly. Here every gesture is a plain
 * rotation of the sphere, so the poles are just ordinary points; "north up" is an option
 * ([alignedNorthUp]) instead of a constraint.
 *
 * Immutable; operations return a new camera.
 */
data class SkyCamera(
    val c: Vec3 = Vec3.fromRaDec(0.0, 0.0),
    val u: Vec3 = Vec3.NORTH,
    val fovDeg: Double = 60.0,
    /** Projection family: [ProjectionKind.HAMMER] for sky surveys, [ProjectionKind.ZEA] for planets. */
    val kind: ProjectionKind = ProjectionKind.HAMMER,
) {
    init {
        require(fovDeg > 0.0)
    }

    /** Sky position at the screen centre. */
    val center: SkyPosition
        get() {
            val rd = c.toRaDec()
            return SkyPosition(rd[0], rd[1])
        }

    /** Screen "right" axis (towards the west when north is up). */
    val right: Vec3 get() = c cross u

    /**
     * Angle in degrees from the screen's up direction to celestial north, positive when north
     * points to the right (clockwise). 0 = north up.
     */
    val northAngleDeg: Double
        get() {
            val n = Vec3.NORTH - c * (Vec3.NORTH dot c)
            if (n.norm() < 1e-9) return 0.0
            return Math.toDegrees(atan2(n dot right, n dot u))
        }

    fun projection(widthPx: Float, heightPx: Float): Projection = Projection(
        center = c,
        up = u,
        radPerPixel = Projection.radPerPixelFromFov(fovDeg, heightPx),
        widthPx = widthPx,
        heightPx = heightPx,
        kind = kind,
    )

    /** Rotates the whole camera frame by a rotation of the sphere (axis, angle). */
    private fun rotatedBy(rot: Pair<Vec3, Double>?): SkyCamera {
        if (rot == null) return this
        val (axis, angle) = rot
        val nc = c.rotated(axis, angle).unit()
        return copy(c = nc, u = orthonormalUp(nc, u.rotated(axis, angle)))
    }

    /**
     * Grab-style drag: the sky point that was under (fromX, fromY) ends up under (toX, toY).
     * Exact for any distance from the centre and any declination, poles included.
     */
    fun drag(fromX: Float, fromY: Float, toX: Float, toY: Float, widthPx: Float, heightPx: Float): SkyCamera {
        if (fromX == toX && fromY == toY) return this
        val p = projection(widthPx, heightPx)
        val a = p.inverseVec(fromX, fromY) // point to carry
        val b = p.inverseVec(toX, toY) // point currently under the destination
        // Camera rotation R with R(b) = a makes `a` appear where `b` was.
        return rotatedBy(Vec3.rotationBetween(b, a))
    }

    /** Zoom by [factor] (>1 zooms in) keeping the sky point under (focusX, focusY) fixed on screen. */
    fun zoom(factor: Float, widthPx: Float, heightPx: Float, focusX: Float, focusY: Float): SkyCamera {
        val newFov = (fovDeg / factor).coerceIn(MIN_FOV, maxFovDeg(widthPx, heightPx))
        if (newFov == fovDeg) return this
        val anchor = projection(widthPx, heightPx).inverseVec(focusX, focusY)
        val zoomed = copy(fovDeg = newFov)
        val under = zoomed.projection(widthPx, heightPx).inverseVec(focusX, focusY)
        return zoomed.rotatedBy(Vec3.rotationBetween(under, anchor))
    }

    /**
     * Twists the picture by [angleDeg] clockwise around the sky point under (focusX, focusY).
     * That point stays where it is on screen.
     */
    fun twist(angleDeg: Double, widthPx: Float, heightPx: Float, focusX: Float, focusY: Float): SkyCamera {
        if (angleDeg == 0.0) return this
        val anchor = projection(widthPx, heightPx).inverseVec(focusX, focusY)
        // Rotating the camera about +anchor by +t turns the picture counter-clockwise on screen.
        return rotatedBy(anchor to -Math.toRadians(angleDeg))
    }

    /** Rolls the picture clockwise by [angleDeg] around the screen centre. */
    fun rolledBy(angleDeg: Double): SkyCamera = rotatedBy(c to -Math.toRadians(angleDeg))

    /** Re-centres on [pos] with the smallest possible rotation (the orientation is preserved). */
    fun recenterTo(pos: SkyPosition): SkyCamera {
        val target = Vec3.fromRaDec(pos.raDeg, pos.decDeg)
        return rotatedBy(Vec3.rotationBetween(c, target))
    }

    /** Same view with celestial north exactly up (a no-op if the centre is on a pole). */
    fun alignedNorthUp(): SkyCamera {
        val n = Vec3.NORTH - c * (Vec3.NORTH dot c)
        if (n.norm() < 1e-9) return this
        return copy(u = n.unit())
    }

    /** Largest FoV for which the screen corners stay inside the render cutoff. */
    fun maxFovDeg(widthPx: Float, heightPx: Float): Double = maxFovFor(widthPx, heightPx, kind)

    /** This camera with the FoV clamped for a (possibly rotated) viewport. */
    fun clampedTo(widthPx: Float, heightPx: Float): SkyCamera {
        val f = fovDeg.coerceIn(MIN_FOV, maxFovFor(widthPx, heightPx, kind))
        return if (f == fovDeg) this else copy(fovDeg = f)
    }

    private fun orthonormalUp(newC: Vec3, rawUp: Vec3): Vec3 {
        val v = rawUp - newC * (rawUp dot newC)
        return if (v.norm() < 1e-12) rawUp else v.unit()
    }

    companion object {
        const val MIN_FOV = 0.005 // ~18 arcsec
        /** Largest FoV (deg) of the vertical extent: only used when the screen size is unknown. */
        const val MAX_FOV = 360.0

        /** Camera looking at [pos] with the given vertical FoV and north up (when possible). */
        fun looking(pos: SkyPosition, fovDeg: Double, kind: ProjectionKind = ProjectionKind.HAMMER): SkyCamera =
            SkyCamera(Vec3.fromRaDec(pos.raDeg, pos.decDeg), Vec3.NORTH, fovDeg, kind).let { cam ->
                val n = Vec3.NORTH - cam.c * (Vec3.NORTH dot cam.c)
                if (n.norm() < 1e-9) {
                    // On a pole: any perpendicular direction will do.
                    cam.copy(u = Vec3(1.0, 0.0, 0.0).let { it - cam.c * (it dot cam.c) }.unit())
                } else {
                    cam.copy(u = n.unit())
                }
            }

        /**
         * Zoom-out limit: the whole oval (Hammer) or disc (ZEA, the visible hemisphere) just fits the
         * screen, with a small margin. One projection unit is one radian at the centre, so the FoV
         * (vertical extent) for a given scale is `radPerPixel * heightPx`.
         */
        fun maxFovFor(widthPx: Float, heightPx: Float, kind: ProjectionKind = ProjectionKind.HAMMER): Double {
            if (widthPx <= 0f || heightPx <= 0f) return MAX_FOV
            val margin = 0.96
            val radPerPx = when (kind) {
                ProjectionKind.HAMMER -> maxOf(
                    2.0 * Projection.HAMMER_HALF_W / (widthPx * margin),
                    2.0 * Projection.HAMMER_HALF_H / (heightPx * margin),
                )
                ProjectionKind.ZEA -> 2.0 * Projection.RHO_CUTOFF / (minOf(widthPx, heightPx) * margin)
            }
            return Math.toDegrees(radPerPx * heightPx).coerceIn(MIN_FOV * 2, MAX_FOV * 4)
        }
    }
}
