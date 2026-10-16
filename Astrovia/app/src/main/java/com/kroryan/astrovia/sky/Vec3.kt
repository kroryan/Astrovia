package com.kroryan.astrovia.sky

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Immutable 3D vector. Sky directions are unit vectors in ICRS
 * (x towards RA=0/Dec=0, y towards RA=90 deg, z towards the north celestial pole).
 */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3): Double = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    fun norm(): Double = sqrt(this dot this)

    fun unit(): Vec3 {
        val n = norm()
        return if (n < 1e-300) this else this * (1.0 / n)
    }

    /** Rodrigues rotation of this vector about the unit vector [axis] by [angle] radians (right-hand rule). */
    fun rotated(axis: Vec3, angle: Double): Vec3 {
        val c = cos(angle)
        val s = sin(angle)
        return this * c + (axis cross this) * s + axis * ((axis dot this) * (1.0 - c))
    }

    /** (raDeg in [0,360), decDeg in [-90,90]). */
    fun toRaDec(): DoubleArray {
        val ra = ((Math.toDegrees(atan2(y, x)) % 360.0) + 360.0) % 360.0
        val dec = Math.toDegrees(asin((z / norm().coerceAtLeast(1e-300)).coerceIn(-1.0, 1.0)))
        return doubleArrayOf(ra, dec)
    }

    companion object {
        val NORTH = Vec3(0.0, 0.0, 1.0)

        fun fromRaDec(raDeg: Double, decDeg: Double): Vec3 {
            val ra = Math.toRadians(raDeg)
            val dec = Math.toRadians(decDeg)
            val cd = cos(dec)
            return Vec3(cd * cos(ra), cd * sin(ra), sin(dec))
        }

        /**
         * The rotation (unit axis, angle in radians) that takes the unit vector [a] onto [b]
         * along the shortest arc, or null when they already coincide.
         */
        fun rotationBetween(a: Vec3, b: Vec3): Pair<Vec3, Double>? {
            val axis = a cross b
            val s = axis.norm()
            val c = a dot b
            if (s < 1e-15) {
                if (c > 0.0) return null
                // Antipodal: any axis perpendicular to a will do.
                val helper = if (kotlin.math.abs(a.x) < 0.9) Vec3(1.0, 0.0, 0.0) else Vec3(0.0, 1.0, 0.0)
                return (a cross helper).unit() to Math.PI
            }
            return axis * (1.0 / s) to atan2(s, c)
        }
    }
}
