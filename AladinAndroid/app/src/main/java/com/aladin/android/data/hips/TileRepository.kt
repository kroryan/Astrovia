package com.aladin.android.data.hips

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
 * Caché LRU en memoria con límite de tamaño en `Long` bytes. `android.util.LruCache`
 * usa un `Int` para el tamaño máximo: `mb * 1024 * 1024` desborda un Int a partir de
 * ~2048 MB (2048*1024*1024 == Int.MIN_VALUE) y el cache queda con maxSize negativo,
 * lo que provoca un crash inmediato al insertar el primer tile. Sin límite superior
 * en la UI (el usuario puede escribir cualquier cifra de MB), necesitamos aritmética
 * en Long de punta a punta.
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
 * Carga y cachea tiles HiPS.
 *
 * Medidas anti-OOM:
 * - Caché en memoria ACOTADA y fija (24 MB), independiente de largeHeap.
 * - Downsampling por `inSampleSize` según el tamaño del tile en pantalla: un
 *   tile que se ve a 140 px se decodifica a 128×128 (32 KB) en vez de 512×512
 *   (512 KB) -> hasta 16× menos memoria.
 * - Reutilización de bitmaps reciclados vía `inBitmap` (sin allocatear nuevo).
 * - Decodificación por stream (sin materializar el body en un ByteArray) y en
 *   RGB_565 (los tiles HiPS son opacos): mitad de memoria que ARGB_8888.
 * - Concurrencia limitada (semáforo) para no decodificar muchos tiles a la vez.
 * - Reciclado de bitmaps al expulsarlos de la caché (libera memoria nativa).
 * - Invalidación coalescida: una ráfaga de tiles produce un único aviso al UI.
 */
class TileRepository(
    context: Context,
    memCacheMb: Int = 48,
    concurrency: Int = 12,
) {

    private val cacheDir = File(context.cacheDir, "hips_tiles").apply { mkdirs() }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cache(Cache(File(cacheDir, "net"), DISK_CACHE_BYTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    // Concurrencia de fetch/decode. El servidor CDS tolera ~12 conexiones
    // paralelas sin rate-limit (probado: 12 tiles en paralelo todos 200 en ~4s).
    // Con 12 huecos, la base gruesa (pocos tiles) llega en el primer lote y el
    // refinamiento fino se completa mucho antes que con 4-8 huecos. Ajustable
    // desde Settings (ver setConcurrency).
    private var permits = Semaphore(concurrency.coerceIn(2, 24))

    // Caché en memoria ajustable desde Settings (ver setMemCacheMb). Es `var`
    // para poder redimensionarla en caliente: vaciamos la vieja y creamos una
    // nueva con el tamaño pedido. NO reciclamos al expulsar (un bitmap que el
    // draw usa en otro hilo provocaría parpadeo / crash nativo). Sin límite
    // superior: el usuario puede escribir cualquier cifra de MB (ver
    // LongLruCache, que usa Long para evitar el overflow de Int que crasheaba
    // por encima de ~2048 MB).
    private var memCacheMbCurrent = memCacheMb.coerceAtLeast(8)
    private var memCache: LongLruCache<String, Bitmap> = newMemCache(memCacheMbCurrent)

    private fun newMemCache(mb: Int) =
        LongLruCache<String, Bitmap>(mb.toLong().coerceAtLeast(8L) * 1024L * 1024L) { it.byteCount.toLong() }

    /** Redimensiona la caché en memoria (vacía la actual y crea una nueva). */
    fun setMemCacheMb(mb: Int) {
        val clamped = mb.coerceAtLeast(8)
        if (clamped == memCacheMbCurrent) return
        synchronized(scope) {
            memCache.evictAll()
            memCache = newMemCache(clamped)
            memCacheMbCurrent = clamped
        }
    }

    /** Cambia la concurrencia de descarga (surte efecto en los próximos fetch). */
    fun setConcurrency(n: Int) {
        permits = Semaphore(n.coerceIn(2, 24))
    }

    /** Vacía la caché en memoria y la de disco. */
    fun clearAll() {
        memCache.evictAll()
        try { client.cache?.delete() } catch (_: Exception) {}
    }

    /** Callback coalescido al hilo principal cuando llegan tiles nuevos. */
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

    /** Nº de tiles pendientes de descarga ahora mismo (para el indicador de carga). */
    val activeDownloads: Int get() = inFlight.size

    fun get(surveyId: String, order: Int, npix: Long, sampleSize: Int): Bitmap? =
        memCache.get(key(surveyId, order, npix, sampleSize))

    /**
     * `scalePx` = píxeles de pantalla por píxel de tile nativo. Lo usamos para
     * decidir el `inSampleSize`: a menor zoom, mayor submuestreo -> menos memoria.
     */
    fun request(survey: HiPSSurvey, ext: String, order: Int, npix: Long, scalePx: Float) {
        val sample = sampleSizeFor(scalePx)
        val k = key(survey.id, order, npix, sample)
        if (memCache.get(k) != null) return
        if (!inFlight.add(k)) return
        // Avisamos también al ENCOLAR (no solo al terminar) para que el indicador
        // de carga aparezca en cuanto se piden tiles, no ~80ms+fetch más tarde.
        scheduleInvalidate()
        val url = survey.tileUrl(order, npix, ext)
        scope.launch {
            val bmp = permits.withPermit { fetch(url, sample) }
            if (bmp != null) memCache.put(k, bmp)
            inFlight.remove(k)
            scheduleInvalidate()
        }
    }

    private suspend fun fetch(url: String, sampleSize: Int): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val req = okhttp3.Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body ?: return@withContext null
                // Decodificación por stream (sin materializar el body en un ByteArray)
                // + inSampleSize para submuestrear según el zoom: un solo paso, la
                // caché de disco de OkHttp se completa correctamente.
                val opts = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inMutable = false
                    inSampleSize = sampleSize
                }
                BufferedInputStream(body.byteStream(), 32 * 1024).use { ins ->
                    BitmapFactory.decodeStream(ins, null, opts)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Cancela todo trabajo en curso (llamar al destruir el ViewModel). */
    fun shutdown() {
        scope.cancel()
        memCache.evictAll()
    }

    // OJO: la clave incluye el id del survey. Sin él, al cambiar de encuesta los
    // tiles del survey NUEVO (mismo order/npix) cache-hit con los bitmaps del
    // survey VIEJO y se muestran indefinidamente las imágenes antiguas (nunca se
    // re-piden). Con el id, cada survey tiene su propio espacio de claves.
    private fun key(surveyId: String, order: Int, npix: Long, sample: Int) =
        "$surveyId/$order/$npix/$sample"

    companion object {
        /** Caché de disco (fija; no se redimensiona en caliente). */
        private const val DISK_CACHE_BYTES = 64L * 1024 * 1024

        /**
         * Submuestreo según cuánto ocupa el tile en pantalla.
         * scalePx >= 0.5  -> tile >= 256 px -> sample 1 (full res)
         * scalePx >= 0.25 -> tile >= 128 px -> sample 2
         * menor          -> sample 4 (suficiente para vista general)
         */
        fun sampleSizeFor(scalePx: Float): Int = when {
            scalePx >= 0.5f -> 1
            scalePx >= 0.25f -> 2
            else -> 4
        }
    }
}