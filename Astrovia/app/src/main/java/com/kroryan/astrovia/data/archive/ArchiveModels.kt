@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.archive

import cds.astrovia.sky.Stcs

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/** Kinds of archive sources, in the order shown by the UI. */
enum class ArchiveKind(val label: String, val code: String) {
    OBSERVATIONS("Observations", "obs"),
    SPECTRA("Spectra", "spec"),
    CATALOGUES("Catalogues", "cat"),
    LIGHT_CURVES("Light curves", "lc"),
    ALERTS("Multi-messenger events", "alert"),
    SOLAR_SYSTEM("Solar system", "sso"),
    IMAGES("Outreach images", "img"),
    EXOPLANETS("Exoplanets", "exo");

    companion object {
        fun fromCode(c: String) = entries.firstOrNull { it.code == c } ?: CATALOGUES
    }
}

/** One queryable table (a mission archive, a catalogue...) described in assets/archives.json. */
data class ArchiveSource(
    val id: String,
    val label: String,
    val mission: String,
    val band: String,
    val kind: ArchiveKind,
    val table: String,
    val raCol: String,
    val decCol: String,
    val idCol: String?,
    val nameCol: String?,
    val regionCol: String?,
    val linkCol: String?,
    val tapUrl: String,
    /** Extra ADQL condition (e.g. a collection filter) and the answer format ("csv" or "votable"). */
    val where: String? = null,
    val format: String = "csv",
    /** Data centre that serves it (ESA, MAST, ESO, IRSA, HEASARC...). */
    val centre: String = "",
    /** Column to sort by (brightest first) so a row limit keeps the most useful sources, not an arbitrary sample. */
    val orderCol: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("label", label).put("mission", mission).put("band", band).put("kind", kind.code)
        .put("table", table).put("ra", raCol).put("dec", decCol).put("idCol", idCol ?: JSONObject.NULL).put("nameCol", nameCol ?: JSONObject.NULL)
        .put("regionCol", regionCol ?: JSONObject.NULL).put("linkCol", linkCol ?: JSONObject.NULL).put("tap", tapUrl).put("where", where ?: JSONObject.NULL)
        .put("fmt", format).put("centre", centre)

    companion object {
        fun fromJson(o: JSONObject, defaultTap: String): ArchiveSource {
            fun s(k: String) = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }
            return ArchiveSource(
                id = o.getString("id"), label = o.getString("label"), mission = o.optString("mission"),
                band = o.optString("band"), kind = ArchiveKind.fromCode(o.optString("kind")),
                table = o.getString("table"), raCol = o.getString("ra"), decCol = o.getString("dec"),
                idCol = s("idCol"), nameCol = s("nameCol"), regionCol = s("regionCol"), linkCol = s("linkCol"),
                tapUrl = s("tap") ?: defaultTap,
                where = s("where"), format = s("fmt") ?: "csv", centre = s("centre") ?: "ESA", orderCol = s("orderCol"),
            )
        }
    }
}

/** A closed polygon on the sky, as alternating RA/Dec in degrees. */
class SkyPolygon(val ra: DoubleArray, val dec: DoubleArray) {
    /** Point-in-polygon on the local tangent plane (RA offsets wrapped around the first vertex). */
    fun contains(raDeg: Double, decDeg: Double): Boolean {
        if (ra.size < 3) return false
        val cd = cos(Math.toRadians(dec[0])).coerceAtLeast(0.05)
        fun x(r: Double): Double { var d = (r - ra[0]) % 360.0; if (d > 180) d -= 360; if (d < -180) d += 360; return d * cd }
        val px = x(raDeg)
        var inside = false
        var j = ra.size - 1
        for (i in ra.indices) {
            val xi = x(ra[i]); val xj = x(ra[j])
            if ((dec[i] > decDeg) != (dec[j] > decDeg) && px < (xj - xi) * (decDeg - dec[i]) / (dec[j] - dec[i]) + xi) inside = !inside
            j = i
        }
        return inside
    }
}

/** A row returned by an archive query. */
data class ArchiveHit(
    val source: ArchiveSource,
    val id: String,
    val title: String,
    val raDeg: Double,
    val decDeg: Double,
    val footprints: List<SkyPolygon>,
    val link: String?,
    val fields: Map<String, String>,
)

/** Parser of the footprint notations used by the archives; the parsing lives in the shared Java code (cds.astrovia.sky.Stcs). */
object Footprints {
    fun parse(text: String?): List<SkyPolygon> = Stcs.parse(text).map { SkyPolygon(it[0], it[1]) }
}
