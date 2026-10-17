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

/** An object of the field with how many papers mention it (SIMBAD). */
data class FieldObject(val name: String, val type: String, val papers: Int)

/** What SIMBAD knows about an object: the "explore this object" card. */
data class ObjectInfo(
    val name: String, val type: String, val raDeg: Double, val decDeg: Double, val parallaxMas: Double?, val redshift: Double?, val spType: String?,
    val sizeArcmin: Double?, val papers: Int, val aliases: List<String>, val magnitudes: Map<String, Double>,
) {
    val distanceLy: Double? get() = parallaxMas?.takeIf { it > 0.5 }?.let { 3261.56 / it }
    val simbadUrl get() = "https://simbad.cds.unistra.fr/simbad/sim-id?Ident=" + URLEncoder.encode(name, "UTF-8")
    val nedUrl get() = "https://ned.ipac.caltech.edu/byname?objname=" + URLEncoder.encode(name, "UTF-8")
    val wikipediaUrl get() = "https://en.wikipedia.org/w/index.php?search=" + URLEncoder.encode(name, "UTF-8")
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
    /** Counts of the field overview must answer quickly: a slow archive is skipped instead of blocking the scan. */
    private val quick = HttpClient.client.newBuilder().callTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(20, java.util.concurrent.TimeUnit.SECONDS).build()

    val sources: List<ArchiveSource> by lazy {
        val txt = context.assets.open("archives.json").bufferedReader().use { it.readText() }
        val root = JSONObject(txt)
        val tap = root.getString("tap")
        val arr = root.getJSONArray("sources")
        List(arr.length()) { ArchiveSource.fromJson(arr.getJSONObject(it), tap) }
    }

    /** All the outreach pictures of a mission ("webb", "hst" or "euclid"), alphabetically. */
    suspend fun gallery(mission: String): List<com.kroryan.astrovia.data.gallery.OutreachImage> = withContext(Dispatchers.IO) {
        val table = when (mission) { "webb" -> "images.webb_outreach"; "euclid" -> "images.euclid_outreach"; else -> "images.hst_outreach" }
        val label = when (mission) { "webb" -> "JWST"; "euclid" -> "Euclid"; else -> "HST" }
        val rows = tapRows(sources.firstOrNull()?.tapUrl ?: "https://sky.esa.int/esasky-tap/tap",
            "SELECT TOP 2500 id,title,description,credit,tiles_url,large_url,ra_deg,dec_deg,fov_size,stc_s FROM $table ORDER BY title")
        rows.mapNotNull { com.kroryan.astrovia.data.gallery.OutreachImage.fromFields(it, label) }
    }

    /** Details of one object by name or identifier (SIMBAD TAP: basic data, identifiers, magnitudes). */
    suspend fun objectDetails(name: String): ObjectInfo? = withContext(Dispatchers.IO) {
        val id = name.trim().replace("'", "''")
        val base = "https://simbad.cds.unistra.fr/simbad/sim-tap"
        val basic = tapRows(base, "SELECT TOP 1 main_id, otype, ra, dec, plx_value, rvz_redshift, sp_type, galdim_majaxis, nbref FROM basic JOIN ident ON ident.oidref = basic.oid WHERE ident.id = '$id'").firstOrNull() ?: return@withContext null
        val main = basic["main_id"].orEmpty().trim()
        val aliases = runCatching { tapRows(base, "SELECT TOP 40 ident.id FROM ident JOIN basic ON ident.oidref = basic.oid WHERE basic.main_id = '${main.replace("'", "''")}'").mapNotNull { it["id"]?.trim() }.filter { it != main } }.getOrDefault(emptyList())
        val mags = runCatching {
            tapRows(base, "SELECT \"B\", \"V\", \"G\", \"J\", \"H\", \"K\" FROM allfluxes JOIN basic ON allfluxes.oidref = basic.oid WHERE basic.main_id = '${main.replace("'", "''")}'").firstOrNull()
                ?.mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k to it } }?.toMap().orEmpty()
        }.getOrDefault(emptyMap())
        ObjectInfo(
            name = main.ifEmpty { name }, type = otypeName(basic["otype"].orEmpty().trim()), raDeg = basic["ra"]?.toDoubleOrNull() ?: Double.NaN, decDeg = basic["dec"]?.toDoubleOrNull() ?: Double.NaN,
            parallaxMas = basic["plx_value"]?.toDoubleOrNull(), redshift = basic["rvz_redshift"]?.toDoubleOrNull(), spType = basic["sp_type"]?.takeIf { it.isNotBlank() },
            sizeArcmin = basic["galdim_majaxis"]?.toDoubleOrNull(), papers = basic["nbref"]?.toIntOrNull() ?: 0, aliases = aliases, magnitudes = mags,
        )
    }

    /** Readable name of the most common SIMBAD object-type codes. */
    fun otypeName(code: String): String = when (code) {
        "*" -> "Star"; "**" -> "Double or multiple star"; "V*" -> "Variable star"; "Psr" -> "Pulsar"; "PN" -> "Planetary nebula"; "SNR" -> "Supernova remnant"
        "HII" -> "HII region"; "Cl*" -> "Star cluster"; "OpC" -> "Open cluster"; "GlC" -> "Globular cluster"; "G" -> "Galaxy"; "GiG", "GiC" -> "Galaxy in a group or cluster"
        "AGN", "Sy1", "Sy2" -> "Active galaxy"; "QSO" -> "Quasar"; "Bla" -> "Blazar"; "ClG" -> "Galaxy cluster"; "BH" -> "Black hole"; "Neb" -> "Nebula"; "RNe" -> "Reflection nebula"
        "EmO" -> "Emission object"; "DNe" -> "Dark cloud"; "MoC" -> "Molecular cloud"; "SFR" -> "Star-forming region"; "Pl" -> "Planet"; "Pl?" -> "Planet candidate"; "" -> "Object"
        else -> code
    }

    /** Number of rows of a source inside a cone: the counts shown in the field overview. */
    suspend fun countInField(src: ArchiveSource, raDeg: Double, decDeg: Double, radiusDeg: Double): Int = withContext(Dispatchers.IO) {
        val adql = String.format(Locale.US, "SELECT COUNT(*) AS n FROM %s WHERE 1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',%.6f,%.6f,%.6f))", src.table, src.raCol, src.decCol, raDeg, decDeg, radiusDeg.coerceIn(0.0005, 90.0)) +
            (src.where?.let { " AND ($it)" } ?: "")
        tapRows(src.tapUrl, adql, src.format, fast = true).firstOrNull()?.values?.firstOrNull()?.toDoubleOrNull()?.toInt() ?: 0
    }

    // ---- user-added TAP services (external data centres) ----
    private val customPrefs = context.getSharedPreferences("astrovia_custom_tap", Context.MODE_PRIVATE)

    fun customSources(): List<ArchiveSource> = runCatching {
        val arr = JSONArray(customPrefs.getString("list", "[]"))
        List(arr.length()) { ArchiveSource.fromJson(arr.getJSONObject(it), "") }
    }.getOrDefault(emptyList())

    fun allSources(): List<ArchiveSource> = sources + customSources()

    fun addCustom(s: ArchiveSource) {
        val cur = customSources().filter { it.id != s.id } + s
        customPrefs.edit().putString("list", JSONArray().also { a -> cur.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    fun removeCustom(id: String) {
        val cur = customSources().filter { it.id != id }
        customPrefs.edit().putString("list", JSONArray().also { a -> cur.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    /** Table names offered by a TAP service (from its TAP_SCHEMA). */
    suspend fun listTables(tapUrl: String): List<String> = withContext(Dispatchers.IO) {
        tapRows(tapUrl.trimEnd('/'), "SELECT TOP 400 table_name FROM TAP_SCHEMA.tables ORDER BY table_name").mapNotNull { it["table_name"] }
    }

    /** Looks for the RA/Dec/id columns of a table (by UCD first, then by name) and builds a source for it. */
    suspend fun detectSource(tapUrl: String, table: String, label: String): ArchiveSource = withContext(Dispatchers.IO) {
        val base = tapUrl.trimEnd('/')
        val cols = tapRows(base, "SELECT column_name, ucd FROM TAP_SCHEMA.columns WHERE table_name='${table.replace("'", "''")}'")
        fun byUcd(vararg u: String) = cols.firstOrNull { c -> u.any { c["ucd"].orEmpty().startsWith(it) } }?.get("column_name")
        fun byName(vararg n: String) = cols.firstOrNull { c -> n.any { c["column_name"].equals(it, true) } }?.get("column_name")
        val ra = byUcd("pos.eq.ra;meta.main", "pos.eq.ra") ?: byName("s_ra", "ra", "raj2000", "ra_deg", "ra_icrs") ?: throw java.io.IOException("No RA column found in $table")
        val dec = byUcd("pos.eq.dec;meta.main", "pos.eq.dec") ?: byName("s_dec", "dec", "dej2000", "dec_deg", "de_icrs") ?: throw java.io.IOException("No Dec column found in $table")
        val id = byUcd("meta.id;meta.main") ?: byName("obs_id", "source_id", "name", "id", "main_id", "target_name")
        val region = byName("s_region", "stc_s", "footprint")
        val link = byName("access_url", "product_url", "url")
        val host = tapUrl.substringAfter("//").substringBefore('/')
        ArchiveSource(
            id = "custom:$host:$table", label = label.ifBlank { table }, mission = host, band = "Various", kind = ArchiveKind.CATALOGUES,
            table = table, raCol = ra, decCol = dec, idCol = id, nameCol = id, regionCol = region, linkCol = link, tapUrl = base, centre = host,
        )
    }

    /** The objects of a small field that have the most papers (cheap SIMBAD query), to jump to their publications. */
    suspend fun fieldObjects(raDeg: Double, decDeg: Double, radiusDeg: Double): List<FieldObject> = withContext(Dispatchers.IO) {
        val r = radiusDeg.coerceIn(0.002, 0.5)
        val adql = String.format(Locale.US, "SELECT TOP 25 main_id, otype, nbref FROM basic WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',%.6f,%.6f,%.5f)) AND nbref>0 ORDER BY nbref DESC", raDeg, decDeg, r)
        tapRows("https://simbad.cds.unistra.fr/simbad/sim-tap", adql).map { FieldObject(it["main_id"].orEmpty().trim(), it["otype"].orEmpty(), it["nbref"]?.toIntOrNull() ?: 0) }
    }

    suspend fun coneSearch(
        src: ArchiveSource, raDeg: Double, decDeg: Double, radiusDeg: Double, maxRows: Int = 300,
    ): List<ArchiveHit> = withContext(Dispatchers.IO) {
        val r = radiusDeg.coerceIn(0.0005, 90.0)
        val adql = String.format(
            Locale.US,
            "SELECT TOP %d * FROM %s WHERE 1=CONTAINS(POINT('ICRS',%s,%s),CIRCLE('ICRS',%.6f,%.6f,%.6f))",
            maxRows, src.table, src.raCol, src.decCol, raDeg, decDeg, r,
        ) + (src.where?.let { " AND ($it)" } ?: "")
        val rows = tapRows(src.tapUrl, adql, src.format)
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

    /** Runs an ADQL query and returns rows, in CSV or VOTable (TABLEDATA/BINARY) depending on what the archive supports. */
    fun tapRows(base: String, adql: String, format: String = "csv", fast: Boolean = false): List<Map<String, String>> {
        val text = tapSync(base, adql, format, fast)
        return if (format == "votable" || text.trimStart().startsWith("<")) VotableTable.parse(text) else Csv.parse(text)
    }

    private fun tapSync(base: String, adql: String, format: String = "csv", fast: Boolean = false): String {
        val body = FormBody.Builder().add("REQUEST", "doQuery").add("LANG", "ADQL").add("FORMAT", format).add("QUERY", adql).build()
        (if (fast) quick else client).newCall(Request.Builder().url("$base/sync").post(body).build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (text.trimStart().startsWith("<") && format == "csv") {
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
