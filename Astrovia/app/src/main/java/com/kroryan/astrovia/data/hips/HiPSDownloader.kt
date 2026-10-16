package com.kroryan.astrovia.data.hips

import android.content.Context
import android.os.StatFs
import com.kroryan.astrovia.data.DownloadForeground
import com.kroryan.astrovia.data.DownloadNotice
import com.kroryan.astrovia.data.net.HttpClient
import com.kroryan.astrovia.sky.Healpix
import com.kroryan.astrovia.sky.SkyFrame
import com.kroryan.astrovia.sky.Vec3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class DownloadState(
    val isRunning: Boolean = false,
    val datasetName: String = "",
    val tilesScanned: Int = 0,
    val tilesDownloaded: Int = 0,
    val bytesDownloaded: Long = 0L,
    val activeThreads: Int = 0,
    val totalTilesQueued: Int = 0,
    /** Tiles that could not be fetched after retrying (network / server errors, NOT 404). */
    val tilesFailed: Int = 0,
    /** Tiles the server says do not exist (outside a sparse survey's coverage): normal, not an error. */
    val tilesNotFound: Int = 0,
    /** Tiles that were already on disk and verified. */
    val tilesSkipped: Int = 0,
    /** Final message after a run (summary or reason it did not start). */
    val message: String? = null,
    /** True when the run finished but some tiles failed: running it again fetches only those. */
    val incomplete: Boolean = false,
)

/** Circular region of the sky (ICRS) to restrict a download. */
data class SkyRegion(val raDeg: Double, val decDeg: Double, val radiusDeg: Double)

/** Result of [HiPSDownloader.estimate]. */
data class DownloadEstimate(val tiles: Long, val bytes: Long)

class HiPSDownloader(private val context: Context) {
    private val _states = ConcurrentHashMap<String, MutableStateFlow<DownloadState>>()
    private val jobs = ConcurrentHashMap<String, Job>()
    private val calls = ConcurrentHashMap.newKeySet<Call>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        // The notification's "Stop" button cancels every download this object runs.
        DownloadForeground.registerStop { for (id in jobs.keys.toList()) stopDownload(id) }
    }

    fun getState(surveyId: String): StateFlow<DownloadState> =
        _states.getOrPut(surveyId) { MutableStateFlow(DownloadState()) }.asStateFlow()

    fun defaultDir(survey: HiPSSurvey): File {
        val datasetName = survey.id.replace(Regex("[^a-zA-Z0-9]"), "_")
        return File(context.getExternalFilesDir(null), "HiPS_Downloads/$datasetName")
    }

    fun startDownload(
        survey: HiPSSurvey,
        maxOrder: Int,
        fastCheck: Boolean = true,
        maxThreads: Int = 8,
        customPath: String? = null,
        region: SkyRegion? = null,
    ) {
        if (jobs[survey.id]?.isActive == true) return
        val baseDir = if (customPath.isNullOrBlank()) defaultDir(survey) else File(customPath)
        val state = _states.getOrPut(survey.id) { MutableStateFlow(DownloadState()) }

        // Foreground service: the download survives the app going to the background.
        val fgId = "hips:${survey.id}"
        DownloadForeground.begin(context, fgId, DownloadNotice.hipsLine(survey.title, 0, 0L))

        val launched = scope.launch {
            state.value = DownloadState(isRunning = true, datasetName = survey.title)
            fun fail(msg: String) {
                state.value = DownloadState(datasetName = survey.title, message = msg)
            }
            // Android 11+ only lets an app write inside its own folders: say so instead of silently failing.
            if (!baseDir.exists() && !baseDir.mkdirs()) {
                return@launch fail("Cannot create ${baseDir.path}. Apps can only write in their own folders on this Android version.")
            }
            if (!baseDir.canWrite()) return@launch fail("Cannot write to ${baseDir.path}.")

            val propsText = loadProperties(survey, baseDir) ?: return@launch fail("Could not download the survey's properties.")
            val props = HiPSPropertiesParser.parse(survey.baseUrl, propsText)
            val ext = props.ext
            val order = min(maxOrder, if (props.order > 0) props.order else maxOrder)

            // Refuse before starting, with the reason, instead of filling the phone.
            val est = estimate(order, region, ext, props.tileWidth)
            if (est.tiles > MAX_TILES_PER_RUN) {
                return@launch fail("About ${est.tiles} tiles: too many for one run. Choose a lower order or only the visible area.")
            }
            val free = freeBytes(baseDir)
            if (est.bytes > free * 0.9) {
                return@launch fail("Needs about ${human(est.bytes)} but only ${human(free)} are free. Choose a lower order or only the visible area.")
            }

            val frame = props.skyFrame
            val regionCenter = region?.let { Vec3.fromRaDec(it.raDeg, it.decDeg) }
            val scanned = AtomicInteger()
            val downloaded = AtomicInteger()
            val skipped = AtomicInteger()
            val failed = AtomicInteger()
            val notFound = AtomicInteger()
            val bytes = AtomicLong()
            val queued = AtomicInteger()
            val active = AtomicInteger()

            data class Task(val order: Int, val npix: Long)
            val queue = ConcurrentLinkedQueue<Task>()
            // Tiles queued or being processed: children are queued BEFORE the parent is marked done,
            // so this only reaches 0 when the whole tree is finished.
            val pending = AtomicInteger(0)

            fun intersects(o: Int, npix: Long): Boolean {
                if (regionCenter == null || region == null) return true
                val c = Healpix.center(o, npix, frame)
                val d = acos(Vec3.fromRaDec(c[0], c[1]).dot(regionCenter).coerceIn(-1.0, 1.0))
                return d <= Math.toRadians(region.radiusDeg) + tileReachRad(o)
            }

            fun enqueue(o: Int, npix: Long) {
                if (!intersects(o, npix)) return
                pending.incrementAndGet()
                queued.incrementAndGet()
                queue.add(Task(o, npix))
            }
            for (i in 0L..11L) enqueue(0, i)

            fun publish(running: Boolean, message: String? = null) {
                if (running) DownloadForeground.progress(fgId, DownloadNotice.hipsLine(survey.title, downloaded.get() + skipped.get(), bytes.get()))
                state.value = DownloadState(
                    isRunning = running, datasetName = survey.title, tilesScanned = scanned.get(),
                    tilesDownloaded = downloaded.get() + skipped.get(), bytesDownloaded = bytes.get(),
                    activeThreads = active.get(), totalTilesQueued = queued.get(),
                    tilesFailed = failed.get(), tilesNotFound = notFound.get(), tilesSkipped = skipped.get(),
                    message = message, incomplete = !running && failed.get() > 0,
                )
            }

            // One publisher instead of a state update per tile (thousands per second contended the UI).
            val publisher = launch {
                while (isActive) {
                    publish(true)
                    delay(300)
                }
            }

            suspend fun process(t: Task) {
                active.incrementAndGet()
                try {
                    val dir = (t.npix / 10000L) * 10000L
                    val file = File(baseDir, "Norder${t.order}/Dir$dir/Npix${t.npix}.$ext")
                    var present = false
                    if (fastCheck && file.exists() && file.length() > 0 && !File(file.path + ".part").exists()) {
                        present = true
                        skipped.incrementAndGet()
                    } else {
                        when (fetchTile(survey.tileUrl(t.order, t.npix, ext), file, bytes)) {
                            Outcome.OK -> { present = true; downloaded.incrementAndGet() }
                            Outcome.NOT_FOUND -> notFound.incrementAndGet()
                            // A transient failure must NOT prune the subtree (it used to be treated
                            // as "empty sky", silently dropping the 4 children and everything below):
                            // carry on, count it, and let the user re-run to complete the dataset.
                            Outcome.FAILED -> { failed.incrementAndGet(); present = true }
                        }
                    }
                    scanned.incrementAndGet()
                    if (present && t.order < order) {
                        val first = t.npix * 4
                        for (i in 0L..3L) enqueue(t.order + 1, first + i)
                    }
                } finally {
                    active.decrementAndGet()
                    pending.decrementAndGet()
                }
            }

            val workers = (1..maxThreads.coerceIn(1, 32)).map {
                launch {
                    while (isActive) {
                        val task = queue.poll()
                        if (task != null) process(task)
                        else if (pending.get() <= 0) break
                        else delay(40)
                    }
                }
            }
            try {
                workers.forEach { it.join() }
            } finally {
                publisher.cancel()
            }
            val summary = buildString {
                append("${downloaded.get() + skipped.get()} tiles on disk (${human(bytes.get())} downloaded)")
                if (notFound.get() > 0) append(", ${notFound.get()} outside the survey coverage")
                if (failed.get() > 0) append(", ${failed.get()} FAILED - run it again to fetch the missing ones")
            }
            val where = "Find it in the menu (top left) as \"${survey.title.removeSuffix(HiPSListService.OFFLINE_SUFFIX)}${HiPSListService.OFFLINE_SUFFIX}\"."
            publish(false, if (isActive) "$summary. $where" else "Stopped. $summary. $where")
        }
        jobs[survey.id] = launched
        launched.invokeOnCompletion { DownloadForeground.end(fgId) }
    }

    fun stopDownload(surveyId: String) {
        jobs[surveyId]?.cancel()
        // Blocking HTTP calls are cancelled too, so Stop is immediate instead of waiting for a timeout.
        for (c in calls.toList()) c.cancel()
        _states[surveyId]?.value = (_states[surveyId]?.value ?: DownloadState()).copy(isRunning = false, activeThreads = 0)
    }

    private enum class Outcome { OK, NOT_FOUND, FAILED }

    /**
     * Downloads one tile to [dest] atomically: bytes go to `.part`, the size is checked against
     * Content-Length and only then renamed. A cancelled or broken transfer therefore never leaves a
     * truncated tile that "Fast check" would later accept as valid.
     */
    private suspend fun fetchTile(url: String, dest: File, bytes: AtomicLong): Outcome {
        var attempt = 0
        while (attempt < ATTEMPTS) {
            val part = File(dest.path + ".part")
            try {
                val call = HttpClient.client.newCall(Request.Builder().url(url).build())
                calls.add(call)
                try {
                    call.execute().use { resp ->
                        if (resp.code == 404) return Outcome.NOT_FOUND
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        val body = resp.body ?: throw IOException("empty body")
                        dest.parentFile?.mkdirs()
                        part.outputStream().use { out -> body.byteStream().copyTo(out) }
                        val len = part.length()
                        val expected = body.contentLength()
                        if (len == 0L || (expected > 0 && len != expected)) throw IOException("incomplete tile ($len of $expected bytes)")
                        if (dest.exists()) dest.delete()
                        if (!part.renameTo(dest)) throw IOException("could not move the tile into place")
                        bytes.addAndGet(len)
                        return Outcome.OK
                    }
                } finally {
                    calls.remove(call)
                }
            } catch (e: CancellationException) {
                part.delete()
                throw e
            } catch (e: IOException) {
                part.delete()
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) throw CancellationException()
                attempt++
                delay(400L * attempt)
            }
        }
        return Outcome.FAILED
    }

    /** `properties`, downloaded atomically (a truncated file used to block re-downloading it for good). */
    private fun loadProperties(survey: HiPSSurvey, baseDir: File): String? {
        val file = File(baseDir, "properties")
        if (file.exists() && file.length() > 0) {
            val text = file.readText()
            if (text.contains("hips_")) return text
            file.delete()
        }
        return try {
            HttpClient.client.newCall(Request.Builder().url(survey.propertiesUrl()).build()).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val text = resp.body?.string() ?: return null
                if (!text.contains("hips_")) return null
                val part = File(baseDir, "properties.part")
                part.writeText(text)
                if (!part.renameTo(file)) return null
                text
            }
        } catch (e: IOException) {
            null
        }
    }

    companion object {
        private const val ATTEMPTS = 3
        const val MAX_TILES_PER_RUN = 6_000_000L

        /** Radius around a tile centre that is guaranteed to contain the whole tile (radians). */
        internal fun tileReachRad(order: Int): Double = HEALPIX_SIDE / (1L shl order).toDouble() * 1.0

        private val HEALPIX_SIDE = sqrt(PI / 3.0)

        /** Rough bytes per 512 px tile for each format (used only for the size warning). */
        private fun tileBytes(ext: String, tileW: Int): Long {
            val base = when (ext) {
                "png" -> 260_000L
                "fits" -> 530_000L
                "webp" -> 40_000L
                else -> 45_000L
            }
            val k = (tileW / 512.0) * (tileW / 512.0)
            return (base * k).toLong().coerceAtLeast(1_000)
        }

        /**
         * How many tiles (and roughly how many bytes) a download to [maxOrder] needs, for the whole
         * sky or only a circular [region]. Shown to the user BEFORE they start.
         */
        fun estimate(maxOrder: Int, region: SkyRegion?, ext: String, tileW: Int = 512): DownloadEstimate {
            var tiles = 0L
            for (o in 0..maxOrder) {
                val all = 12L * (1L shl (2 * o))
                val n = if (region == null) {
                    all
                } else {
                    // tiles whose centre lies within radius + reach of the region centre
                    val side = HEALPIX_SIDE / (1L shl o).toDouble()
                    val r = Math.toRadians(region.radiusDeg) + side
                    val solid = 2 * PI * (1 - kotlin.math.cos(min(r, PI)))
                    min(all, max(1L, (solid / (side * side)).toLong() + 1))
                }
                tiles += n
            }
            return DownloadEstimate(tiles, tiles * tileBytes(ext, tileW))
        }

        /** Free space on the volume that holds [dir] (walks up to an existing parent). */
        fun freeBytes(dir: File): Long {
            var d: File? = dir
            while (d != null && !d.exists()) d = d.parentFile
            return try { StatFs((d ?: dir).path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
        }

        fun human(b: Long): String = when {
            b >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f GB", b / 1073741824.0)
            b >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
            b >= 1L shl 10 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
            else -> "$b B"
        }
    }
}
