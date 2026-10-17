@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.sky

import cds.astrovia.sky.Planner as SharedPlanner

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A field of view made of polygons in arcseconds around the pointing: x towards east, y towards north when PA = 0. */
class InstrumentFootprint(val key: String, val label: String, val mission: String, val shapes: List<List<Pair<Double, Double>>>, val note: String = "")

/**
 * Projects instrument fields of view onto the sky to plan future observations. The sizes and the projection live in the
 * shared Java code (cds.astrovia.sky.Planner), also used by the desktop app.
 */
object Planner {
    val instruments: List<InstrumentFootprint> = SharedPlanner.all().map { i ->
        InstrumentFootprint(i.key, i.label, i.mission, i.shapes.map { poly -> poly.map { it[0] to it[1] } }, i.note)
    }

    fun byKey(key: String) = instruments.firstOrNull { it.key == key }

    fun toSky(raDeg: Double, decDeg: Double, paDeg: Double, xArcsec: Double, yArcsec: Double): DoubleArray =
        SharedPlanner.toSky(raDeg, decDeg, paDeg, xArcsec, yArcsec)

    /** All the polygons of a footprint on the sky, as RA/Dec degree pairs. */
    fun outline(f: InstrumentFootprint, raDeg: Double, decDeg: Double, paDeg: Double): List<Pair<DoubleArray, DoubleArray>> {
        val inst = SharedPlanner.byKey(f.key) ?: return emptyList()
        return SharedPlanner.outline(inst, raDeg, decDeg, paDeg).map { it[0] to it[1] }
    }
}
