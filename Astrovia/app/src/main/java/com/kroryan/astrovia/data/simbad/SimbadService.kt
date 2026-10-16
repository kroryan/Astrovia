@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.simbad

import com.kroryan.astrovia.data.VotableParser
import com.kroryan.astrovia.data.net.HttpClient
import com.kroryan.astrovia.model.CatalogRow
import com.kroryan.astrovia.model.SkyPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Resultado de una búsqueda en Simbad. */
data class SimbadObject(
    val mainId: String,
    val otype: String,
    val position: SkyPosition,
    val magV: Double? = null,
    val spectralType: String = "",
    val raw: Map<String, String> = emptyMap(),
)

/**
 * Consulta Simbad por nombre de objeto o por coordenadas (query = "ra dec" en grados).
 * Endpoints: simbad/sim-id (name) and simbad/sim-tap (cone search)
 * (Véase cds/aladin/ServerSimbad.java y el diccionario GLU SimbadXML1.)
 */
class SimbadService {
    private val client = HttpClient.client

    // `sim-id?...&Votable=...` (the old query) answers with an HTML page and HTTP 200, not a VOTable:
    // the parser found no rows and name search looked like "SIMBAD does not respond".
    // `output.format=VOTABLE&output.params=...` returns the real table.
    private val outputParams = "main_id,otype,ra(d),dec(d),flux(V),sp"

    /**
     * Resolves an object name (M42, NGC 7000, Betelgeuse...). Returns an empty list when SIMBAD
     * does not know the name; throws [java.io.IOException] when the service cannot be reached,
     * so the UI can tell "not found" from "no connection".
     */
    suspend fun search(query: String): List<SimbadObject> =
        withContext(Dispatchers.IO) {
            val ident = URLEncoder.encode(query.trim(), "UTF-8")
            val url = "https://simbad.cds.unistra.fr/simbad/sim-id?Ident=$ident" +
                "&output.format=VOTABLE&output.params=" + URLEncoder.encode(outputParams, "UTF-8")
            val text = fetchTextOrThrow(url)
            VotableParser.parse(text).mapNotNull { row -> row.toSimbad() }
        }

    suspend fun searchRegion(raDeg: Double, decDeg: Double, radiusArcmin: Double = 10.0, maxRows: Int = 500): List<SimbadObject> =
        withContext(Dispatchers.IO) {
            val tapUrl = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync"
            val radiusDegrees = radiusArcmin / 60.0
            val rStr = String.format(java.util.Locale.US, "%.6f", raDeg)
            val dStr = String.format(java.util.Locale.US, "%.6f", decDeg)
            val radStr = String.format(java.util.Locale.US, "%.6f", radiusDegrees)
            val topClause = if (maxRows > 0) "TOP $maxRows " else ""
            val adql = "SELECT $topClause b.main_id, b.otype, b.ra, b.dec, f.flux as flux_V, b.sp_type FROM basic AS b LEFT JOIN flux AS f ON b.oid = f.oidref AND f.filter = 'V' WHERE CONTAINS(POINT('ICRS', b.ra, b.dec), CIRCLE('ICRS', $rStr, $dStr, $radStr))=1 ORDER BY flux_V ASC"
            val url = "$tapUrl?REQUEST=doQuery&LANG=ADQL&FORMAT=votable/td&QUERY=${URLEncoder.encode(adql, "UTF-8")}"
            val text = fetchTextOrThrow(url)
            VotableParser.parse(text).mapNotNull { row -> row.toSimbad() }
        }

    /** Búsqueda de cono por coordenadas para identificar un objeto al tocar. */
    suspend fun coneSearch(raDeg: Double, decDeg: Double, radiusArcmin: Double = 1.0): SimbadObject? {
        val list = searchRegion(raDeg, decDeg, radiusArcmin)
        return list.minByOrNull {
            SkyPosition.separation(it.position, SkyPosition(raDeg, decDeg))
        }
    }

    fun toCatalogRow(obj: SimbadObject): CatalogRow = CatalogRow(
        id = obj.mainId,
        position = obj.position,
        raDeg = obj.position.raDeg,
        decDeg = obj.position.decDeg,
        mag = obj.magV,
        columns = obj.raw,
    )

    /**
     * Column names differ between endpoints (`RA_d`/`FLUX_V` in sim-id, `ra`/`flux_v` in TAP), so
     * they are matched case-insensitively. (The old exact-case match missed TAP's `flux_v`, so
     * region searches never carried a magnitude.)
     */
    fun Map<String, String>.toSimbad(): SimbadObject? {
        val m = entries.associate { it.key.lowercase() to it.value }
        fun first(vararg keys: String): String? = keys.firstNotNullOfOrNull { k -> m[k]?.takeIf { it.isNotBlank() } }

        val mainId = first("main_id") ?: return null
        val otype = first("otype", "otype_s") ?: "Unknown"
        val ra = first("ra_d", "ra")?.toDoubleOrNull() ?: return null
        val dec = first("dec_d", "dec")?.toDoubleOrNull() ?: return null
        val fluxV = first("flux_v", "flux(v)")?.toDoubleOrNull()
        val sp = first("sp_type", "sp") ?: ""

        return SimbadObject(
            mainId = mainId.trim(),
            otype = otype,
            position = SkyPosition(ra, dec),
            magV = fluxV,
            spectralType = sp,
            raw = this,
        )
    }

    /** Like [fetchText] but reports connectivity and server problems as exceptions. */
    private fun fetchTextOrThrow(url: String): String =
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("SIMBAD answered HTTP ${r.code}")
            r.body?.string() ?: throw java.io.IOException("SIMBAD sent an empty answer")
        }
}
