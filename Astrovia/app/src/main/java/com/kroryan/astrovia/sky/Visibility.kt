@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.sky

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Pointing constraint of a space observatory: allowed Sun-target angle range (degrees). */
data class VisibilityConstraint(val name: String, val minSunDeg: Double, val maxSunDeg: Double) {
    companion object {
        /** Approximate published pointing limits; real schedules add more constraints. */
        val presets = listOf(
            VisibilityConstraint("JWST (Sun 85°–135°)", 85.0, 135.0),
            VisibilityConstraint("Euclid (Sun ≈87°–121°)", 87.0, 121.0),
            VisibilityConstraint("Generic (Sun > 45°)", 45.0, 180.0),
        )
    }
}

/** A continuous range of days (Julian dates) during which the target is observable. */
data class VisibilityWindow(val startJd: Double, val endJd: Double) {
    val days get() = endJd - startJd
}

/**
 * Visibility of a fixed sky position from a Sun-pointing-constrained observatory, from a low-precision
 * solar ephemeris (accurate to ~0.01°, enough for planning windows measured in days).
 */
object Visibility {
    private const val J2000 = 2451545.0

    fun julianDate(epochMillis: Long): Double = epochMillis / 86_400_000.0 + 2440587.5

    /** Sun RA/Dec in degrees at a Julian date. */
    fun sunPosition(jd: Double): DoubleArray {
        val n = jd - J2000
        val l = Math.toRadians((280.460 + 0.9856474 * n) % 360.0)
        val g = Math.toRadians((357.528 + 0.9856003 * n) % 360.0)
        val lambda = l + Math.toRadians(1.915) * sin(g) + Math.toRadians(0.020) * sin(2 * g)
        val eps = Math.toRadians(23.439 - 0.0000004 * n)
        val ra = Math.atan2(cos(eps) * sin(lambda), cos(lambda))
        val dec = Math.asin(sin(eps) * sin(lambda))
        return doubleArrayOf((Math.toDegrees(ra) + 360.0) % 360.0, Math.toDegrees(dec))
    }

    /** Sun-target angle in degrees. */
    fun elongation(raDeg: Double, decDeg: Double, jd: Double): Double {
        val s = sunPosition(jd)
        val a = Math.toRadians(raDeg); val d = Math.toRadians(decDeg)
        val sa = Math.toRadians(s[0]); val sd = Math.toRadians(s[1])
        val c = sin(d) * sin(sd) + cos(d) * cos(sd) * cos(a - sa)
        return Math.toDegrees(acos(c.coerceIn(-1.0, 1.0)))
    }

    fun windows(
        raDeg: Double, decDeg: Double, c: VisibilityConstraint, startJd: Double, days: Int = 365, stepDays: Double = 0.25,
    ): List<VisibilityWindow> {
        val out = ArrayList<VisibilityWindow>()
        var open: Double? = null
        var jd = startJd
        val end = startJd + days
        while (jd <= end) {
            val e = elongation(raDeg, decDeg, jd)
            val ok = e >= c.minSunDeg && e <= c.maxSunDeg
            if (ok && open == null) open = jd
            if (!ok && open != null) { out.add(VisibilityWindow(open, jd)); open = null }
            jd += stepDays
        }
        open?.let { out.add(VisibilityWindow(it, end)) }
        return out
    }
}
