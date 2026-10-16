@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.hips

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.BufferedInputStream
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * In-memory LRU cache with size limit in `Long` bytes. `android.util.LruCache`
 * uses an `Int` for max size: `mb * 1024 * 1024` overflows an Int starting at
 * ~2048 MB (2048*1024*1024 == Int.MIN_VALUE) and the cache gets a negative maxSize,
 * which causes an immediate crash when inserting the first tile. Since there is no
 * upper limit in the UI (the user can enter any MB figure), we need Long arithmetic
 * end-to-end.
 */
private class LongLruCache<K, V>(private val maxBytes: Long, private val sizeOf: (V) -> Long) {
    private val map = LinkedHashMap<K, V>(16, 0.75f, true)
    private var currentBytes = 0L

    @Synchronized
    fun get(key: K): V? = map[key]

    @Synchronized
    fun put(key: K, value: V) {
        map.remove(key)?.let { currentBytes -= sizeOf(it) }
        map[key] = value
        currentBytes += sizeOf(value)
        trim()
    }

    private fun trim() {
        val it = map.entries.iterator()
        while (currentBytes > maxBytes && it.hasNext()) {
            val e = it.next()
            currentBytes -= sizeOf(e.value)
            it.remove()
        }
    }

    @Synchronized
    fun evictAll() {
        map.clear()
        currentBytes = 0L
    }
}

/**
 * Loads and caches HiPS tiles.
 *
 * Anti-OOM measures:
 * - BOUNDED and fixed in-memory cache (24 MB), independent of largeHeap.
 * - Downsampling via `inSampleSize` based on on-screen tile size: a
 *   tile viewed at 140 px is decoded to 128x128 (32 KB) instead of 512x512
 *   (512 KB) -> up to 16x less memory.
 * - Stream decoding (without materializing the body into a ByteArray). JPEG tiles (opaque)
 *   are decoded as RGB_565 (half the memory); PNG/WebP/FITS tiles keep ARGB_8888 so
 *   transparency and colour depth are not lost.
 * - Limited concurrency (semaphore) to avoid decoding many tiles at once.
 * - Bitmap recycling when evicted from cache (frees native memory).
 * - Coalesced invalidation: a burst of tiles produces a single UI notification.
 */
class TileRepository(
    context: Context,
    memCacheMb: Int = 48,
    concurrency: Int = 12,
) {

    /**
     * The in-memory bitmap cache never exceeds a share of the app's heap class, whatever the user types in
     * Settings: Google Play's quality checks (Android vitals) penalise apps that hold lots of bitmap memory.
     */
    private val maxCacheMb: Int = run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        ((am?.memoryClass ?: 192) / 4).coerceIn(16, 128)
    }

    private val cacheDir = File(context.cacheDir, "hips_tiles").apply { mkdirs() }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "net"), DISK_CACHE_BYTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // Tiles that failed recently (offline, 404 on sparse HiPS...). Without this
    // cooldown every repaint would re-request them and, since each failure triggers
    // a repaint, the app would retry in a tight loop (battery and data drain).
    private val failedAt: MutableMap<String, Long> = java.util.concurrent.ConcurrentHashMap()
    // Tiles the server says do not exist (HTTP 404: outside a sparse survey's coverage) are
    // not retried for a long time; other failures (timeouts, 5xx) are retried sooner.
    private val missing: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // Fetch/decode concurrency. The CDS server tolerates ~12 parallel connections
    // without rate-limit (tested: 12 tiles in parallel all 200 in ~4s).
    // With 12 slots, the coarse base (few tiles) arrives in the first batch and
    // fine refinement completes much earlier than with 4-8 slots. Adjustable
    // from Settings (see setConcurrency).
    @Volatile private var permits = Semaphore(concurrency.coerceIn(2, 24))

    // In-memory cache adjustable from Settings (see setMemCacheMb). It is `var`
    // to allow hot resizing: we clear the old one and create a new one with the
    // requested size. We DO NOT recycle on eviction (a bitmap used by draw on
    // another thread would cause flickering / native crash). No upper limit:
    // the user can enter any MB figure (see LongLruCache, which uses Long to
    // avoid Int overflow that crashed above ~2048 MB).
    private var memCacheMbCurrent = memCacheMb.coerceIn(8, maxCacheMb)
    @Volatile private var memCache: LongLruCache<String, Bitmap> = newMemCache(memCacheMbCurrent)

    private fun newMemCache(mb: Int) =
        LongLruCache<String, Bitmap>(mb.toLong().coerceAtLeast(8L) * 1024L * 1024L) { it.byteCount.toLong() }

    /** Resizes the in-memory cache (clears the current one and creates a new one). */
    fun setMemCacheMb(mb: Int) {
        val clamped = mb.coerceIn(8, maxCacheMb)
        if (clamped == memCacheMbCurrent) return
        synchronized(scope) {
            memCache.evictAll()
            memCache = newMemCache(clamped)
            memCacheMbCurrent = clamped
        }
    }

    /**
     * Gives memory back when the system asks for it. Bitmaps must not sit in memory while the app is not
     * visible (background / cached), so everything is dropped as soon as the UI is hidden; they are
     * reloaded from the disk cache the next time the sky is shown. Under pressure in the foreground the
     * cache is emptied too (the visible tiles come back from disk in a moment).
     */
    fun trimMemory(level: Int) {
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
        ) {
            memCache.evictAll()
        }
    }

    /** Changes download concurrency (takes effect on subsequent fetches). */
    fun setConcurrency(n: Int) {
        permits = Semaphore(n.coerceIn(2, 24))
    }

    /** Clears in-memory and disk caches. */
    fun clearAll() {
        memCache.evictAll()
        // evictAll() (not delete()): delete() closes the OkHttp cache for good, after
        // which every request would throw IllegalStateException until app restart.
        try { client.cache?.evictAll() } catch (_: Exception) {}
        failedAt.clear()
        missing.clear()
    }

    /** Coalesced callback to the main thread when new tiles arrive. */
    var onTilesChanged: (() -> Unit)? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val invalidateRunnable = Runnable {
        pendingInvalidate = false
        onTilesChanged?.invoke()
    }
    @Volatile private var pendingInvalidate = false

    private fun scheduleInvalidate() {
        if (pendingInvalidate) return
        pendingInvalidate = true
        mainHandler.postDelayed(invalidateRunnable, 80)
    }

    /** Number of tiles currently pending download (for the loading indicator). */
    val activeDownloads: Int get() = inFlight.size

    fun get(surveyId: String, order: Int, npix: Long, sampleSize: Int): Bitmap? {
        val exact = memCache.get(key(surveyId, order, npix, sampleSize))
        if (exact != null) return exact
        for (s in intArrayOf(1, 2, 4)) {
            if (s != sampleSize) {
                val fallback = memCache.get(key(surveyId, order, npix, s))
                if (fallback != null) return fallback
            }
        }
        return null
    }

    /**
     * `scalePx` = screen pixels per native tile pixel. Used to decide the
     * `inSampleSize`: lower zoom means more downsampling -> less memory.
     */
    fun request(survey: HiPSSurvey, props: HiPSProperties, order: Int, npix: Long, scalePx: Float) {
        val sample = sampleSizeFor(scalePx)
        for (s in intArrayOf(1, 2, 4)) {
            if (s <= sample && memCache.get(key(survey.id, order, npix, s)) != null) return
        }
        val k = key(survey.id, order, npix, sample)
        if (missing.contains(k)) {
            val lastMiss = failedAt[k]
            if (lastMiss != null && System.currentTimeMillis() - lastMiss < MISSING_COOLDOWN_MS) return
            missing.remove(k)
        }
        val lastFail = failedAt[k]
        if (lastFail != null) {
            if (System.currentTimeMillis() - lastFail < FAIL_COOLDOWN_MS) return
            failedAt.remove(k)
        }
        if (!inFlight.add(k)) return
        // We notify when QUEUING (not just on completion) so the loading indicator
        // appears as soon as tiles are requested, not ~80ms+fetch later.
        scheduleInvalidate()
        val url = survey.tileUrl(order, npix, props.ext)
        scope.launch {
            val result = permits.withPermit { fetch(url, sample, props, highQuality = false) }
            if (result.bitmap != null) {
                memCache.put(k, result.bitmap)
            } else {
                failedAt[k] = System.currentTimeMillis()
                if (result.notFound) missing.add(k)
            }
            inFlight.remove(k)
            scheduleInvalidate()
        }
    }

    /**
     * Full-resolution, ARGB_8888 tile for exports (screenshots). It reuses the HTTP disk cache,
     * retries transient failures and is NOT stored in the live LRU cache, so the caller owns it.
     * Returns null only if the tile does not exist or all attempts failed.
     */
    suspend fun fetchFull(survey: HiPSSurvey, props: HiPSProperties, order: Int, npix: Long): Bitmap? {
        val url = survey.tileUrl(order, npix, props.ext)
        var attempt = 0
        while (attempt < FULL_ATTEMPTS) {
            val r = permits.withPermit { fetch(url, 1, props, highQuality = true) }
            if (r.bitmap != null) return r.bitmap
            if (r.notFound) return null
            attempt++
            kotlinx.coroutines.delay(400L * attempt)
        }
        return null
    }

    private class FetchResult(val bitmap: Bitmap?, val notFound: Boolean = false)

    private suspend fun fetch(url: String, sampleSize: Int, props: HiPSProperties, highQuality: Boolean): FetchResult =
        withContext(Dispatchers.IO) {
            val ext = props.ext
            try {
                // JPEG is opaque: RGB_565 halves the memory in the live view. Everything else
                // (and any export) keeps full 32-bit colour and alpha.
                val config = if (ext == "jpg" && !highQuality) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = config
                    inMutable = false
                    inSampleSize = sampleSize
                }
                fun decode(ins: java.io.InputStream): Bitmap? {
                    if (ext == "fits") {
                        val pic = FitsTileDecoder.decode(ins, props.pixelCutMin, props.pixelCutMax, sampleSize) ?: return null
                        return Bitmap.createBitmap(pic.argb, pic.width, pic.height, Bitmap.Config.ARGB_8888)
                    }
                    return BitmapFactory.decodeStream(ins, null, opts)
                }
                if (url.startsWith("file://")) {
                    val file = try { java.io.File(java.net.URI(url)) } catch (e: Exception) { java.io.File(url.removePrefix("file://")) }
                    if (!file.exists()) return@withContext FetchResult(null, notFound = true)
                    val bmp = file.inputStream().buffered(32 * 1024).use { decode(it) }
                    return@withContext FetchResult(bmp)
                }
                val req = okhttp3.Request.Builder().url(url).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        android.util.Log.w("TileRepository", "HTTP ${resp.code} for $url")
                        return@withContext FetchResult(null, notFound = resp.code == 404)
                    }
                    val body = resp.body ?: return@withContext FetchResult(null)
                    val decoded = BufferedInputStream(body.byteStream(), 32 * 1024).use { decode(it) }
                    if (decoded == null) android.util.Log.w("TileRepository", "could not decode $url")
                    FetchResult(decoded)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("TileRepository", "fetch failed for $url", e)
                FetchResult(null)
            }
        }

    /** Cancels all ongoing work (call when destroying the ViewModel). */
    fun shutdown() {
        scope.cancel()
        memCache.evictAll()
    }

    // WARNING: the key includes the survey id. Without it, changing surveys makes
    // the NEW survey tiles (same order/npix) cache-hit the bitmaps of the OLD
    // survey and endlessly display old images (never re-requested). With the id,
    // each survey has its own key space.
    private fun key(surveyId: String, order: Int, npix: Long, sample: Int) =
        "$surveyId/$order/$npix/$sample"

    companion object {
        /** Disk cache (fixed; cannot be hot-resized). */
        private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

        /** Minimum wait before retrying a tile that failed to load (network error, 5xx...). */
        private const val FAIL_COOLDOWN_MS = 30_000L

        /** A tile that does not exist (404) is asked for again only after this long. */
        private const val MISSING_COOLDOWN_MS = 10 * 60_000L

        /** Attempts for an export tile before giving up. */
        private const val FULL_ATTEMPTS = 3

        /**
         * Downsampling based on on-screen tile size.
         * scalePx >= 0.5  -> tile >= 256 px -> sample 1 (full res)
         * scalePx >= 0.25 -> tile >= 128 px -> sample 2
         * smaller        -> sample 4 (enough for an overview)
         */
        fun sampleSizeFor(scalePx: Float): Int = when {
            scalePx >= 0.5f -> 1
            scalePx >= 0.25f -> 2
            else -> 4
        }
    }
}
