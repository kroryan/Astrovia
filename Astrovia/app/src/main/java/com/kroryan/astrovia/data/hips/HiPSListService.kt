package com.kroryan.astrovia.data.hips

import com.kroryan.astrovia.data.net.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

object HiPSListService {
    // Only the fields the app needs: ~0.9 MB instead of the ~4.9 MB of the full records.
    private const val LIST_URL = "https://alasky.cds.unistra.fr/MocServer/query?dataproduct_type=image&get=record&fmt=json" +
        "&fields=ID,obs_title,obs_description,hips_service_url,hips_tile_format,hips_frame"

    /**
     * @param cacheFile where the last good list is kept so the catalogue is available offline and
     *        the app does not re-download ~1 MB on every start when the network fails.
     */
    suspend fun fetchAvailableSurveys(localDirs: List<File>, cacheFile: File? = null): List<HiPSSurvey> =
        withContext(Dispatchers.IO) {
            val list = mutableListOf<HiPSSurvey>()
            val seenIds = HashSet<String>()
            for (s in SurveyRegistry.builtIn) {
                if (seenIds.add(s.id)) list.add(s)
            }

            // Datasets downloaded on this device go first, labelled "(offline)" so they can be found by
            // typing "offline" in the search box.
            val offline = scanLocal(localDirs)
            offline.forEach { seenIds.add(it.id) }
            list.addAll(0, offline)

            val json = downloadList() ?: cacheFile?.takeIf { it.exists() }?.let { readCache(it) }
            if (json != null) {
                try {
                    val array = JSONArray(json)
                    for (i in 0 until array.length()) {
                        val obj = array.getJSONObject(i)
                        val id = obj.optString("ID", "")
                        if (id.isBlank() || !seenIds.add(id)) continue
                        val url = obj.optString("hips_service_url", "")
                        if (url.isBlank()) continue
                        val title = obj.optString("obs_title", id)
                        list.add(
                            HiPSSurvey(
                                id = id,
                                title = title,
                                // Plain-HTTP servers are blocked by the app's network policy: try HTTPS.
                                baseUrl = upgradeToHttps(url).trimEnd('/'),
                                isColor = title.contains("color", ignoreCase = true),
                                description = obj.optString("obs_description", ""),
                                frame = obj.optString("hips_frame", "equatorial").ifBlank { "equatorial" },
                                formats = obj.optString("hips_tile_format", ""),
                            ),
                        )
                    }
                    if (cacheFile != null && json.length > 1000) {
                        try { cacheFile.writeText(json) } catch (e: Exception) { /* cache is optional */ }
                    }
                } catch (e: Exception) {
                    android.util.Log.w("HiPSListService", "could not parse the survey list", e)
                }
            }

            // Offline copies, then the built-in entries; the rest are alphabetical.
            val head = list.take((offline.size + SurveyRegistry.builtIn.size).coerceAtMost(list.size))
            val rest = list.drop(head.size).sortedBy { it.title.lowercase() }
            head + rest
        }

    const val OFFLINE_SUFFIX = " (offline)"
    const val OFFLINE_DESC = "Offline: downloaded on this device"

    fun isOffline(s: HiPSSurvey) = s.id.endsWith("_local")

    /**
     * HiPS folders found in [localDirs] (each directory is a HiPS, or contains HiPS folders), as
     * "<title> (offline)". The title, frame and tile formats come from the folder's `properties`;
     * the folder name is the fallback.
     */
    fun scanLocal(localDirs: List<File>): List<HiPSSurvey> {
        val out = mutableListOf<HiPSSurvey>()
        val seen = HashSet<String>()
        for (localDir in localDirs) {
            if (!localDir.isDirectory) continue
            val candidates = if (File(localDir, "properties").length() > 0) listOf(localDir)
            else localDir.listFiles()?.filter { it.isDirectory && File(it, "properties").length() > 0 } ?: emptyList()
            for (dir in candidates) {
                val baseUrl = "file://" + dir.absolutePath
                if (!seen.add(baseUrl)) continue
                val p = readProps(File(dir, "properties"))
                val name = p["obs_title"]?.takeIf { it.isNotBlank() } ?: dir.name.replace("_", " ")
                out.add(
                    HiPSSurvey(
                        id = dir.name + "_local",
                        title = name + OFFLINE_SUFFIX,
                        baseUrl = baseUrl,
                        isColor = (p["dataproduct_subtype"] ?: "").contains("color", true) || name.contains("color", true),
                        description = OFFLINE_DESC,
                        frame = p["hips_frame"]?.takeIf { it.isNotBlank() } ?: "equatorial",
                        formats = p["hips_tile_format"] ?: "",
                    ),
                )
            }
        }
        return out.sortedBy { it.title.lowercase() }
    }

    private fun readProps(f: File): Map<String, String> = try {
        f.readLines().mapNotNull { l ->
            val i = l.indexOf('=')
            if (i <= 0 || l.trimStart().startsWith("#")) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    internal fun upgradeToHttps(url: String): String =
        if (url.startsWith("http://", ignoreCase = true)) "https://" + url.substring(7) else url

    private fun downloadList(): String? = try {
        HttpClient.client.newCall(Request.Builder().url(LIST_URL).build()).execute().use { resp ->
            if (!resp.isSuccessful) null else decode(resp.body?.bytes())
        }
    } catch (e: Exception) {
        null // offline: the cached copy (if any) is used instead
    }

    private fun readCache(f: File): String? = try { decode(f.readBytes()) } catch (e: Exception) { null }

    /**
     * The server sends `application/json` without a charset and some records contain Latin-1
     * bytes, which a UTF-8 decoder would turn into replacement characters: decode as strict UTF-8
     * first and fall back to ISO-8859-1.
     */
    internal fun decode(bytes: ByteArray?): String? {
        if (bytes == null) return null
        return try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            String(bytes, Charsets.ISO_8859_1)
        }
    }
}
