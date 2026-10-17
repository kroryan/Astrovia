@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.sky

import cds.astrovia.sky.Visibility as SharedVisibility


/** Pointing constraint of a space observatory: allowed Sun-target angle range (degrees). */
data class VisibilityConstraint(val name: String, val minSunDeg: Double, val maxSunDeg: Double) {
    companion object {
        /** Approximate published pointing limits; the values live in the shared Java code. */
        val presets = SharedVisibility.PRESET_NAMES.indices.map { VisibilityConstraint(SharedVisibility.PRESET_NAMES[it], SharedVisibility.PRESET_LIMITS[it][0], SharedVisibility.PRESET_LIMITS[it][1]) }
    }
}

/** A continuous range of days (Julian dates) during which the target is observable. */
data class VisibilityWindow(val startJd: Double, val endJd: Double) {
    val days get() = endJd - startJd
}

/**
 * Visibility of a fixed sky position from a Sun-pointing-constrained observatory. The maths lives in the shared Java code
 * (cds.astrovia.sky.Visibility), also used by the desktop app.
 */
object Visibility {
    fun julianDate(epochMillis: Long): Double = SharedVisibility.julianDate(epochMillis)

    /** Sun RA/Dec in degrees at a Julian date. */
    fun sunPosition(jd: Double): DoubleArray = SharedVisibility.sunPosition(jd)

    /** Sun-target angle in degrees. */
    fun elongation(raDeg: Double, decDeg: Double, jd: Double): Double = SharedVisibility.elongation(raDeg, decDeg, jd)

    fun windows(
        raDeg: Double, decDeg: Double, c: VisibilityConstraint, startJd: Double, days: Int = 365, stepDays: Double = 0.25,
    ): List<VisibilityWindow> =
        SharedVisibility.windows(raDeg, decDeg, c.minSunDeg, c.maxSunDeg, startJd, days, stepDays).map { VisibilityWindow(it[0], it[1]) }
}
