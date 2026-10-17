@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.fits

import cds.astrovia.sky.LightCurveCols

/** A time series: TIME and FLUX (and optional errors) with display labels. */
class LightCurve(
    val title: String, val timeLabel: String, val fluxLabel: String,
    val time: DoubleArray, val flux: DoubleArray, val error: DoubleArray?,
)

/** Finds the time and flux columns of a FITS table (TESS, Kepler, K2, generic) and builds a [LightCurve]. */
object LightCurves {
    /** Column names present in the table that look like a time axis and a flux axis, or null when it is not a light curve. */
    fun detect(columns: List<String>): Pair<String, String>? =
        LightCurveCols.detect(columns)?.let { it[0] to it[1] }

    fun columnsOf(fits: FitsFile, hdu: FitsHdu): List<String> {
        val n = hdu.header.int("TFIELDS") ?: 0
        return (1..n).map { hdu.header.str("TTYPE$it") ?: "col$it" }
    }

    /** First BINTABLE of the file that holds a light curve. */
    fun find(fits: FitsFile): Pair<FitsHdu, Pair<String, String>>? =
        fits.tableHdus.firstNotNullOfOrNull { h -> detect(columnsOf(fits, h))?.let { h to it } }

    /** Reads the series, drops non-finite points and (when there is a QUALITY column) flagged ones. */
    fun read(fits: FitsFile, hdu: FitsHdu, cols: Pair<String, String>, name: String): LightCurve {
        val available = columnsOf(fits, hdu)
        val errName = listOf(cols.second + "_ERR", "FLUX_ERR", "MAG_ERR", "ERR").firstOrNull { c -> available.any { it.equals(c, true) } }
        val q = available.firstOrNull { it.equals("QUALITY", true) }
        val want = listOfNotNull(cols.first, cols.second, errName, q)
        val data = fits.readColumns(hdu, want)
        fun col(n: String?) = n?.let { x -> data.entries.firstOrNull { it.key.equals(x, true) }?.value }
        val t = col(cols.first) ?: throw FitsException("No time column")
        val f = col(cols.second) ?: throw FitsException("No flux column")
        val e = col(errName)
        val ql = col(q)
        val keep = t.indices.filter { i -> !t[i].isNaN() && !f[i].isNaN() && !f[i].isInfinite() && (ql == null || ql[i] == 0.0) }
        if (keep.size < 2) throw FitsException("The light curve has fewer than 2 valid points")
        val h = hdu.header
        val object_ = h.str("OBJECT") ?: name
        val ref = h.dbl("BJDREFI")?.let { (it + (h.dbl("BJDREFF") ?: 0.0)) }
        val tl = when { cols.first.equals("TIME", true) && ref != null -> "BJD - ${"%.1f".format(ref)} (days)"; else -> cols.first }
        return LightCurve(object_, tl, cols.second, DoubleArray(keep.size) { t[keep[it]] }, DoubleArray(keep.size) { f[keep[it]] }, e?.let { ee -> DoubleArray(keep.size) { ee[keep[it]] } })
    }
}
