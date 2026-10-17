@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.sky

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A field of view made of polygons (and circles) in arcseconds around the pointing centre: x towards east, y towards north when PA = 0. */
class InstrumentFootprint(val key: String, val label: String, val mission: String, val shapes: List<List<Pair<Double, Double>>>, val note: String = "")

/**
 * Projects instrument fields of view onto the sky to plan future observations: pick an instrument, a pointing and a
 * position angle and see what the detector would cover. Dimensions are the nominal published sizes (rounded);
 * real apertures have gaps, offsets and per-date orientation limits that the observatory tools model exactly.
 */
object Planner {
    private fun rect(cx: Double, cy: Double, w: Double, h: Double) =
        listOf((cx - w / 2) to (cy - h / 2), (cx + w / 2) to (cy - h / 2), (cx + w / 2) to (cy + h / 2), (cx - w / 2) to (cy + h / 2))

    private fun circle(r: Double, n: Int = 48) = List(n) { i -> r * cos(2 * Math.PI * i / n) to r * sin(2 * Math.PI * i / n) }

    val instruments: List<InstrumentFootprint> = listOf(
        InstrumentFootprint("jwst_nircam", "JWST NIRCam (2 modules)", "JWST", listOf(rect(-86.5, 0.0, 129.0, 129.0), rect(86.5, 0.0, 129.0, 129.0)), "Short-wavelength modules A and B, ~44\" gap"),
        InstrumentFootprint("jwst_nirspec_msa", "JWST NIRSpec MSA", "JWST", listOf(rect(-52.0, 51.0, 100.0, 96.0), rect(52.0, 51.0, 100.0, 96.0), rect(-52.0, -51.0, 100.0, 96.0), rect(52.0, -51.0, 100.0, 96.0)), "Four quadrants, 3.6' x 3.4'"),
        InstrumentFootprint("jwst_niriss", "JWST NIRISS", "JWST", listOf(rect(0.0, 0.0, 132.0, 132.0))),
        InstrumentFootprint("jwst_miri", "JWST MIRI imager", "JWST", listOf(rect(0.0, 0.0, 74.0, 113.0))),
        InstrumentFootprint("hst_wfc3_uvis", "HST WFC3/UVIS", "HST", listOf(rect(0.0, 0.0, 162.0, 162.0))),
        InstrumentFootprint("hst_wfc3_ir", "HST WFC3/IR", "HST", listOf(rect(0.0, 0.0, 136.0, 123.0))),
        InstrumentFootprint("hst_acs_wfc", "HST ACS/WFC", "HST", listOf(rect(0.0, 51.0, 202.0, 100.0), rect(0.0, -51.0, 202.0, 100.0))),
        InstrumentFootprint("euclid_vis", "Euclid VIS", "Euclid", listOf(rect(0.0, 0.0, 2833.0, 2552.0)), "~0.79 x 0.71 deg"),
        InstrumentFootprint("euclid_nisp", "Euclid NISP", "Euclid", listOf(rect(0.0, 0.0, 2747.0, 2599.0)), "~0.76 x 0.72 deg"),
        InstrumentFootprint("xmm_epic", "XMM-Newton EPIC (30')", "XMM-Newton", listOf(circle(900.0))),
        InstrumentFootprint("chandra_acis_i", "Chandra ACIS-I", "Chandra", listOf(rect(0.0, 0.0, 1014.0, 1014.0)), "16.9' x 16.9'"),
        InstrumentFootprint("rubin_lsst", "Rubin LSST camera", "Rubin", listOf(circle(6300.0)), "3.5 deg field"),
        InstrumentFootprint("custom_1deg", "Custom 1° x 1°", "Custom", listOf(rect(0.0, 0.0, 3600.0, 3600.0))),
    )

    fun byKey(key: String) = instruments.firstOrNull { it.key == key }

    /**
     * Sky position of a point [xArcsec] east / [yArcsec] north of the pointing in the instrument frame, rotated by
     * [paDeg] (position angle of the +y axis, measured from north towards east), by gnomonic de-projection.
     */
    fun toSky(raDeg: Double, decDeg: Double, paDeg: Double, xArcsec: Double, yArcsec: Double): DoubleArray {
        val pa = Math.toRadians(paDeg)
        // +y points to PA east of north: east = x cos pa + y sin pa, north = -x sin pa + y cos pa
        val e = xArcsec * cos(pa) + yArcsec * sin(pa)
        val n = -xArcsec * sin(pa) + yArcsec * cos(pa)
        val xi = Math.toRadians(e / 3600.0); val eta = Math.toRadians(n / 3600.0)
        val ra0 = Math.toRadians(raDeg); val d0 = Math.toRadians(decDeg)
        val den = cos(d0) - eta * sin(d0)
        val ra = ra0 + atan2(xi, den)
        val dec = atan2(sin(d0) + eta * cos(d0), sqrt(xi * xi + den * den))
        return doubleArrayOf((Math.toDegrees(ra) % 360.0 + 360.0) % 360.0, Math.toDegrees(dec))
    }

    /** All the polygons of a footprint on the sky, as RA/Dec degree pairs. */
    fun outline(f: InstrumentFootprint, raDeg: Double, decDeg: Double, paDeg: Double): List<Pair<DoubleArray, DoubleArray>> =
        f.shapes.map { poly ->
            val pts = poly.map { toSky(raDeg, decDeg, paDeg, it.first, it.second) }
            DoubleArray(pts.size) { pts[it][0] } to DoubleArray(pts.size) { pts[it][1] }
        }
}
