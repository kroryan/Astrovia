@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.vizier

import com.kroryan.astrovia.data.VotableParser
import com.kroryan.astrovia.data.net.HttpClient
import com.kroryan.astrovia.model.CatalogDescriptor
import com.kroryan.astrovia.model.CatalogRow
import com.kroryan.astrovia.model.SkyPosition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Consulta VizieR (cone search) y devuelve fuentes como overlays.
 * Endpoint: viz-bin/votable?-source=...&-c=ra dec&-c.rm=arcmin&-out.add=_RAJ,_DEJ&-out.max=...
 * (Véase cds/aladin/ServerVizieR.java y cds/vizier/VizieRQuery.java.)
 */
class VizieRService {
    private val client = HttpClient.client

    /** Popular catalogs for the picker. */
    val catalogs: List<CatalogDescriptor> = listOf(
        CatalogDescriptor("I/355/gaiadr3", "Gaia DR3", "Gaia astrometric catalog (DR3)"),
        CatalogDescriptor("II/246/out", "2MASS", "2MASS all-sky infrared survey"),
        CatalogDescriptor("I/259/tyc2", "Tycho-2", "Tycho-2 astrometric catalog"),
        CatalogDescriptor("V/147/sdss16", "SDSS16", "Sloan Digital Sky Survey (DR16)"),
        CatalogDescriptor("I/322a", "Gaia DR1", "Gaia catalog (DR1)"),
        CatalogDescriptor("SIMBAD", "Simbad", "Simbad Astronomical Database"),
    )

    suspend fun coneSearch(
        catalogId: String,
        raDeg: Double,
        decDeg: Double,
        radiusArcmin: Double,
        maxRows: Int = 500,
    ): List<CatalogRow> = withContext(Dispatchers.IO) {
        val c = URLEncoder.encode(String.format(java.util.Locale.US, "%.6f %+.6f", raDeg, decDeg), "UTF-8")
        val maxParam = if (maxRows <= 0) "unlimited" else maxRows.toString()
        val url =
            "https://vizier.cds.unistra.fr/viz-bin/votable" +
                "?-source=$catalogId" +
                "&-c=$c" +
                "&-c.rm=$radiusArcmin" +
                "&-out.add=_RAJ,_DEJ" +
                "&-out.max=$maxParam" +
                "&-out.meta=DhuL" +
                "&-oc.form=dm"
        android.util.Log.d("VizieR", "Fetching: $url")
        // Errors now propagate (java.io.IOException) so the UI can say "no connection" instead of
        // the misleading "No objects found".
        val text = fetchText(url)
        rowsFromVotable(text)
    }

    /**
     * Rows of a VOTable from VizieR or SIMBAD (also used for catalogues downloaded earlier).
     * Column names are matched case-insensitively; SIMBAD's `ra`/`dec`/`main_id` are accepted too.
     */
    fun rowsFromVotable(text: String): List<CatalogRow> =
        VotableParser.parse(text).mapNotNull { row -> row.toCatalogRow() }

    private val raKeys = listOf("_raj2000", "_raj", "raj2000", "ra_icrs", "ra(icrs)", "ra_d", "ra")
    private val decKeys = listOf("_dej2000", "_dej", "dej2000", "de_icrs", "de(icrs)", "dec_d", "dec", "de")

    private fun Map<String, String>.toCatalogRow(): CatalogRow? {
        val m = entries.associate { it.key.lowercase() to it.value }
        fun first(keys: List<String>): String? = keys.firstNotNullOfOrNull { k -> m[k]?.trim()?.takeIf { it.isNotEmpty() } }
        val ra = first(raKeys)?.toDoubleOrNull() ?: return null
        val dec = first(decKeys)?.toDoubleOrNull() ?: return null
        val mag = first(listOf("gmag", "kmag", "jmag", "hmag", "vtmag", "btmag", "rmag", "g", "vmag", "flux_v"))
            ?.toDoubleOrNull()
        val id = first(listOf("source", "2mass", "tyc", "objid", "source_id", "main_id")) ?: "${ra}_${dec}"
        return CatalogRow(
            id = id,
            position = SkyPosition(ra, dec),
            raDeg = ra,
            decDeg = dec,
            mag = mag,
            columns = this,
        )
    }

    private fun fetchText(url: String): String =
        client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("VizieR answered HTTP ${r.code}")
            r.body?.string() ?: throw java.io.IOException("VizieR sent an empty answer")
        }
}
