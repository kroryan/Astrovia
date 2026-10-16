@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.vizier

import android.content.Context
import com.kroryan.astrovia.data.DownloadForeground
import com.kroryan.astrovia.data.DownloadNotice
import com.kroryan.astrovia.data.hips.HiPSDownloader
import com.kroryan.astrovia.data.net.HttpClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.Request
import java.io.File
import java.net.URLEncoder

data class CatalogDownloadState(
    val isRunning: Boolean = false,
    val catalogName: String = "",
    val bytesDownloaded: Long = 0L,
    val isSuccess: Boolean = false,
    val errorMessage: String? = null,
    /** Set when the download succeeded but the server cut the table short (row limit). */
    val warning: String? = null,
    /** Absolute path of the last catalogue saved (so it can be shown on the sky). */
    val savedPath: String? = null,
)

class CatalogDownloader(private val context: Context) {
    private val _state = MutableStateFlow(CatalogDownloadState())
    val state: StateFlow<CatalogDownloadState> = _state.asStateFlow()

    private var downloadJob: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        DownloadForeground.registerStop { stopDownload() }
    }

    private fun beginForeground(name: String) =
        DownloadForeground.begin(context, FG_ID, DownloadNotice.catalogLine(name, 0L))

    fun startVizieRDownload(catalogId: String, raDeg: Double, decDeg: Double, radiusArcmin: Double, allSky: Boolean = false) {
        if (downloadJob?.isActive == true) return

        val safeCatalogId = catalogId.replace(Regex("[^a-zA-Z0-9]"), "_")
        val baseDir = File(context.getExternalFilesDir(null), "Catalog_Downloads")
        baseDir.mkdirs()
        val suffix = if (allSky) "allsky" else "${raDeg}_${decDeg}_${radiusArcmin}"
        val outputFile = File(baseDir, "${safeCatalogId}_$suffix.xml")

        beginForeground(catalogId)
        downloadJob = scope.launch {
            _state.value = CatalogDownloadState(isRunning = true, catalogName = catalogId)
            try {
                val url = if (allSky) {
                    "https://vizier.cds.unistra.fr/viz-bin/votable" +
                            "?-source=$catalogId" +
                            "&-out.add=_RAJ,_DEJ" +
                            "&-out.max=unlimited" +
                            "&-out.meta=DhuL" +
                            "&-oc.form=dm"
                } else {
                    val c = URLEncoder.encode(String.format(java.util.Locale.US, "%.6f %+.6f", raDeg, decDeg), "UTF-8")
                    "https://vizier.cds.unistra.fr/viz-bin/votable" +
                            "?-source=$catalogId" +
                            "&-c=$c" +
                            "&-c.rm=$radiusArcmin" +
                            "&-out.add=_RAJ,_DEJ" +
                            "&-out.max=unlimited" +
                            "&-out.meta=DhuL" +
                            "&-oc.form=dm"
                }

                downloadFile(url, outputFile)
                val warning = detectOverflow(outputFile)
                _state.update { it.copy(isRunning = false, isSuccess = true, warning = warning, savedPath = outputFile.absolutePath) }
            } catch (e: CancellationException) {
                outputFile.delete()
                throw e
            } catch (e: Exception) {
                outputFile.delete() // never leave a truncated catalog behind
                _state.update { it.copy(isRunning = false, errorMessage = e.message) }
            }
        }
        downloadJob?.invokeOnCompletion { DownloadForeground.end(FG_ID) }
    }

    fun startSimbadDownload(raDeg: Double, decDeg: Double, radiusArcmin: Double) {
        if (downloadJob?.isActive == true) return

        val baseDir = File(context.getExternalFilesDir(null), "Catalog_Downloads")
        baseDir.mkdirs()
        val outputFile = File(baseDir, "Simbad_${raDeg}_${decDeg}_${radiusArcmin}.xml")

        beginForeground("Simbad")
        downloadJob = scope.launch {
            _state.value = CatalogDownloadState(isRunning = true, catalogName = "Simbad")
            try {
                val tapUrl = "https://simbad.cds.unistra.fr/simbad/sim-tap/sync"
                val radiusDegrees = radiusArcmin / 60.0
                val adql = if (radiusArcmin >= 999999.0) {
                    "SELECT * FROM basic"
                } else {
                    val r = String.format(java.util.Locale.US, "%.6f", raDeg)
                    val d = String.format(java.util.Locale.US, "%.6f", decDeg)
                    val rad = String.format(java.util.Locale.US, "%.6f", radiusDegrees)
                    "SELECT * FROM basic WHERE CONTAINS(POINT('ICRS', ra, dec), CIRCLE('ICRS', $r, $d, $rad))=1"
                }
                val url = "$tapUrl?REQUEST=doQuery&LANG=ADQL&FORMAT=votable/td&QUERY=${URLEncoder.encode(adql, "UTF-8")}"

                downloadFile(url, outputFile)
                val warning = detectOverflow(outputFile)
                _state.update { it.copy(isRunning = false, isSuccess = true, warning = warning, savedPath = outputFile.absolutePath) }
            } catch (e: CancellationException) {
                outputFile.delete()
                throw e
            } catch (e: Exception) {
                outputFile.delete() // never leave a truncated catalog behind
                _state.update { it.copy(isRunning = false, errorMessage = e.message) }
            }
        }
        downloadJob?.invokeOnCompletion { DownloadForeground.end(FG_ID) }
    }

    private fun downloadFile(url: String, outputFile: File) {
        val req = Request.Builder().url(url).build()
        val call = HttpClient.client.newCall(req)
        activeCall = call
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw Exception("HTTP Error ${resp.code}")
                }
                val body = resp.body ?: throw Exception("Empty response body")
                var total = 0L
                var lastPublish = 0L
                var lastSpaceCheck = 0L
                outputFile.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var len: Int
                    val inStream = body.byteStream()
                    while (inStream.read(buf).also { len = it } != -1) {
                        out.write(buf, 0, len)
                        total += len
                        val now = System.currentTimeMillis()
                        // One UI update every 250 ms instead of one per chunk.
                        if (now - lastPublish > 250) {
                            lastPublish = now
                            _state.update { it.copy(bytesDownloaded = total) }
                            DownloadForeground.progress(FG_ID, DownloadNotice.catalogLine(_state.value.catalogName, total))
                        }
                        // A full catalogue can be gigabytes: stop before the phone runs out of space.
                        if (total - lastSpaceCheck > 8L * 1024 * 1024) {
                            lastSpaceCheck = total
                            if (HiPSDownloader.freeBytes(outputFile.parentFile ?: outputFile) < MIN_FREE_BYTES) {
                                throw java.io.IOException("Not enough free storage to continue this download")
                            }
                        }
                    }
                }
                _state.update { it.copy(bytesDownloaded = total) }
            }
        } finally {
            activeCall = null
        }
        detectVotableError(outputFile)?.let { error ->
            outputFile.delete()
            throw Exception(error)
        }
    }

    companion object {
        private const val FG_ID = "catalog"
        private const val MIN_FREE_BYTES = 50L * 1024 * 1024
        private val OVERFLOW = Regex("name=\"QUERY_STATUS\"\\s+value=\"OVERFLOW\"")
        private val VOTABLE_ERROR = Regex("name=\"Error\"\\s+value=\"([^\"]*)\"")

        // VizieR's ASU service reports invalid catalog names (and similar errors) as an
        // HTTP 200 response whose VOTable body carries an <INFO name="Error" .../> tag,
        // instead of a non-2xx status. Detect that so we don't report success with a
        // garbage file. Errors always appear near the top of the document, so peeking
        // at the first few KB is enough even for multi-megabyte catalog downloads.
        /**
         * SIMBAD/VizieR cut a table at their row limit and say so at the END of the document
         * (`<INFO name="QUERY_STATUS" value="OVERFLOW">`), which the head-only check never saw:
         * a truncated catalogue was reported as complete.
         */
        internal fun detectOverflow(file: File): String? {
            val tail = try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    val n = minOf(raf.length(), 16L * 1024).toInt()
                    raf.seek(raf.length() - n)
                    val b = ByteArray(n)
                    raf.readFully(b)
                    String(b, Charsets.UTF_8)
                }
            } catch (e: Exception) {
                return null
            }
            return if (OVERFLOW.containsMatchIn(tail)) {
                "The server stopped at its row limit: this table is INCOMPLETE. Use a smaller radius."
            } else {
                null
            }
        }

        internal fun detectVotableError(file: File): String? {
            val head = file.inputStream().use { input ->
                val buf = ByteArray(8192)
                val n = input.read(buf)
                if (n <= 0) return null
                String(buf, 0, n, Charsets.UTF_8)
            }
            val msg = VOTABLE_ERROR.find(head)?.groupValues?.get(1)?.trim() ?: return null
            return "VizieR/Simbad error: ${msg.ifEmpty { "unknown error" }}"
        }
    }

    fun stopDownload() {
        downloadJob?.cancel()
        activeCall?.cancel() // do not wait for the next read timeout
        _state.update { it.copy(isRunning = false) }
    }
}
