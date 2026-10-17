@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.archive

import android.content.Context
import com.kroryan.astrovia.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

/** A bibliographic reference attached to an object (SIMBAD). */
data class Publication(val bibcode: String, val year: Int, val title: String) {
    val url get() = "https://ui.adsabs.harvard.edu/abs/" + URLEncoder.encode(bibcode, "UTF-8") + "/abstract"
}

/** A solar-system body found in the field at a given epoch (SkyBoT). */
data class SolarSystemBody(
    val name: String, val number: Long, val cls: String, val vmag: Double?, val raDeg: Double, val decDeg: Double,
    val card: String?,
)

/**
 * Multi-archive search: cone searches against TAP services (ESA sky archive, Gaia, HST, NASA exoplanets),
 * solar-system objects (SkyBoT) and publications (SIMBAD). Own implementation on top of the public
 * IVOA protocols (TAP/ADQL, CSV).
 */
class ArchiveService(context: Context) {
    private val client = HttpClient.client

    val sources: List<ArchiveSource> by lazy {
        val txt = context.assets.open("archives.json").bufferedReader().use { it.readText() }
        val root = JSONObject(txt)
        val tap = root.getString("tap")
        val arr = root.getJSONArray("sources")
        List(arr.length()) { ArchiveSource.fromJson(arr.getJSONObject(it), tap) }
    }

    suspend fun coneSearch(
        src: ArchiveSource, raDeg: Double, decDeg: Double, radiusDeg: Double, maxRows: Int = 300,
    ): List<ArchiveHit> = withContext(Dispatchers.IO) {
        val r = radiusDeg.coerceIn(0.0005, 90.0)
        val adql = String.format(
            Locale.US,
            "SELECT TOP %d * FROM %s WHERE 1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',%.6f,%.6f,%.6f))",
            maxRows, src.table, src.raCol, src.decCol, raDeg, decDeg, r,
        )
        val rows = Csv.parse(tapSync(src.tapUrl, adql))
        rows.mapNotNull { row ->
            val m = row.entries.associate { it.key.lowercase() to it.value }
            fun col(c: String?) = c?.let { m[it.lowercase()] }?.trim()?.takeIf { it.isNotEmpty() }
            val ra = col(src.raCol)?.toDoubleOrNull() ?: return@mapNotNull null
            val dec = col(src.decCol)?.toDoubleOrNull() ?: return@mapNotNull null
            val id = col(src.idCol) ?: col(src.nameCol) ?: "%.5f%+.5f".format(Locale.US, ra, dec)
            ArchiveHit(
                source = src, id = id, title = col(src.nameCol) ?: id, raDeg = ra, decDeg = dec,
                footprints = Footprints.parse(col(src.regionCol)),
                link = col(src.linkCol)?.takeIf { it.startsWith("http") },
                fields = row.filterValues { it.isNotBlank() && it.length < 400 },
            )
        }
    }

    /** Solar-system objects in the cone at the given Julian date. */
    suspend fun solarSystem(raDeg: Double, decDeg: Double, radiusDeg: Double, jd: Double): List<SolarSystemBody> =
        withContext(Dispatchers.IO) {
            val rs = (radiusDeg * 3600.0).coerceIn(1.0, 36000.0)
            val url = String.format(
                Locale.US,
                "https://ssp.imcce.fr/webservices/skybot/api/conesearch.php?-ra=%.6f&-dec=%.6f&-rs=%.1f&-mime=json&-output=basic&-observer=500&-from=astrovia&-ep=%.5f",
                raDeg, decDeg, rs, jd,
            )
            val text = get(url)
            if (!text.trim().startsWith("[")) return@withContext emptyList()
            val arr = JSONArray(text)
            List(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                SolarSystemBody(
                    name = o.optString("Name"), number = o.optLong("Num", 0), cls = o.optString("Class"),
                    vmag = if (o.has("VMag (mag)")) o.optDouble("VMag (mag)") else null,
                    raDeg = hmsToDeg(o.optString("RA (hms)")), decDeg = dmsToDeg(o.optString("DEC (dms)")),
                    card = o.optJSONObject("ssodnet")?.optString("ssocard"),
                )
            }
        }

    /** Latest publications that mention a SIMBAD object. */
    suspend fun publications(objectName: String, limit: Int = 40): List<Publication> = withContext(Dispatchers.IO) {
        val id = objectName.trim().replace("'", "''")
        val adql = "SELECT TOP $limit ref.bibcode, ref.\"year\" AS yr, ref.title FROM ident " +
            "JOIN has_ref ON ident.oidref=has_ref.oidref JOIN ref ON has_ref.oidbibref=ref.oidbib " +
            "WHERE ident.id='$id' ORDER BY yr DESC"
        Csv.parse(tapSync("https://simbad.cds.unistra.fr/simbad/sim-tap", adql)).map {
            Publication(it["bibcode"].orEmpty(), it["yr"]?.toIntOrNull() ?: 0, it["title"].orEmpty())
        }
    }

    private fun tapSync(base: String, adql: String): String {
        val body = FormBody.Builder().add("REQUEST", "doQuery").add("LANG", "ADQL").add("FORMAT", "csv").add("QUERY", adql).build()
        client.newCall(Request.Builder().url("$base/sync").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (text.trimStart().startsWith("<")) {
                val msg = Regex("QUERY_STATUS\" value=\"ERROR\">([^<]{0,300})").find(text)?.groupValues?.get(1)?.trim()
                throw java.io.IOException(msg ?: "The archive rejected the query (HTTP ${r.code})")
            }
            if (!r.isSuccessful) throw java.io.IOException("Archive answered HTTP ${r.code}")
            return text
        }
    }

    private fun get(url: String): String =
        client.newCall(Request.Builder().url(url).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("Service answered HTTP ${r.code}")
            r.body?.string().orEmpty()
        }

    private fun hmsToDeg(s: String): Double {
        val p = s.trim().split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        return if (p.size == 3) (p[0] + p[1] / 60 + p[2] / 3600) * 15.0 else Double.NaN
    }

    private fun dmsToDeg(s: String): Double {
        val t = s.trim()
        val p = t.split(Regex("\\s+")).mapNotNull { it.toDoubleOrNull() }
        if (p.size != 3) return Double.NaN
        val neg = t.startsWith("-")
        val v = kotlin.math.abs(p[0]) + p[1] / 60 + p[2] / 3600
        return if (neg) -v else v
    }
}

/** Minimal RFC 4180 CSV reader: quoted fields, doubled quotes and line breaks inside quotes. */
object Csv {
    fun parse(text: String): List<Map<String, String>> {
        val records = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val sb = StringBuilder()
        var q = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (q) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false
                } else sb.append(c)
            } else when (c) {
                '"' -> q = true
                ',' -> { row.add(sb.toString()); sb.setLength(0) }
                '\n' -> { row.add(sb.toString()); sb.setLength(0); records.add(row); row = ArrayList() }
                '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        if (sb.isNotEmpty() || row.isNotEmpty()) { row.add(sb.toString()); records.add(row) }
        if (records.size < 2) return emptyList()
        val head = records[0]
        return records.drop(1).filter { it.size > 1 || it.firstOrNull()?.isNotEmpty() == true }.map { r ->
            val m = LinkedHashMap<String, String>()
            for (k in head.indices) m[head[k]] = r.getOrElse(k) { "" }
            m
        }
    }
}
