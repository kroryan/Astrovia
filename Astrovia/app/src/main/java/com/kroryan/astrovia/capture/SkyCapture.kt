package com.kroryan.astrovia.capture

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.kroryan.astrovia.SkyUiState
import com.kroryan.astrovia.data.hips.HiPSProperties
import com.kroryan.astrovia.data.hips.HiPSSurvey
import com.kroryan.astrovia.data.hips.TileRepository
import com.kroryan.astrovia.sky.CoordGrid
import com.kroryan.astrovia.sky.DisplayFilter
import com.kroryan.astrovia.sky.Healpix
import com.kroryan.astrovia.sky.Projection
import com.kroryan.astrovia.sky.SkyRenderer
import com.kroryan.astrovia.sky.TileMeshPainter
import com.kroryan.astrovia.sky.VisibleTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class CaptureFormat(val ext: String, val mime: String, val label: String) {
    PNG("png", "image/png", "PNG (lossless)"),
    JPEG("jpg", "image/jpeg", "JPEG (quality 100)"),
}

/**
 * What to export. By default the capture is ONLY the sky picture: no buttons, crosshair, panels,
 * status bars or any other interface element, because it is rendered off-screen from the tiles,
 * not copied from the screen.
 */
data class CaptureOptions(
    /** Super-sampling of the on-screen size (1 = what the screen shows). */
    val scale: Int = 2,
    val format: CaptureFormat = CaptureFormat.PNG,
    val includeGrid: Boolean = false,
    val includeCatalog: Boolean = false,
    val includeMarkers: Boolean = false,
    /** Apply the brightness / contrast / invert settings, i.e. what the user is looking at. */
    val applyDisplayFilters: Boolean = true,
)

enum class CapturePhase { PREPARING, DOWNLOADING, FALLBACK, ENCODING }

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Running(val phase: CapturePhase, val done: Int, val total: Int) : CaptureState

    /** Android 7-9: the picture is rendered and waits for the user to choose where to save it. */
    data class AwaitingLocation(val suggestedName: String, val width: Int, val height: Int, val format: CaptureFormat) : CaptureState
    data class Saved(
        val uri: Uri,
        val width: Int,
        val height: Int,
        val bytes: Long,
        val missingTiles: Int,
        /** Human-readable place where it was saved ("Pictures/Astrovia" or the folder the user chose). */
        val location: String,
    ) : CaptureState
    data class Failed(val message: String) : CaptureState
}

class RenderedCapture(val bitmap: Bitmap, val missingTiles: Int, val meta: Map<String, String>, val name: String)

object SkyCapture {
    private const val MAX_SIDE = 8192
    private const val ABSOLUTE_MAX_PIXELS = 36_000_000L
    private const val BATCH = 12
    private const val MAX_TILES = 4000

    /** Largest bitmap we are willing to allocate (ARGB_8888 = 4 bytes/pixel, ~35 % of the heap). */
    fun maxPixels(): Long = min(ABSOLUTE_MAX_PIXELS, (Runtime.getRuntime().maxMemory() * 0.35 / 4).toLong())

    /** Output size for a given on-screen size and scale, reduced if it would not fit in memory. */
    fun outputSize(viewW: Int, viewH: Int, scale: Int, maxPixels: Long = maxPixels()): Pair<Int, Int> {
        var w = viewW.toDouble() * scale
        var h = viewH.toDouble() * scale
        val side = max(w, h)
        if (side > MAX_SIDE) {
            val k = MAX_SIDE / side
            w *= k
            h *= k
        }
        if (w * h > maxPixels) {
            val k = sqrt(maxPixels / (w * h))
            w *= k
            h *= k
        }
        var wi = w.roundToInt().coerceAtLeast(1)
        var hi = h.roundToInt().coerceAtLeast(1)
        // Rounding up could push the area just over the budget: step down until it fits.
        while (wi.toLong() * hi > maxPixels && wi > 1 && hi > 1) {
            if (wi >= hi) wi-- else hi--
        }
        return wi to hi
    }

    /** The scales that fit on this device for the current screen, e.g. [1, 2, 3]. */
    fun availableScales(viewW: Int, viewH: Int): List<Int> =
        (1..4).filter { s ->
            val (w, h) = outputSize(viewW, viewH, s)
            w >= viewW * s - 1 && h >= viewH * s - 1
        }.ifEmpty { listOf(1) }

    /**
     * Renders the sky picture of [state] at [viewW]x[viewH] x scale.
     *
     * The framing is identical to the one on screen: the camera is the same and the vertical FoV
     * is kept, so a larger bitmap just has more pixels per degree. Tiles are requested at the
     * order needed for that resolution (native resolution at most), downloaded at full quality
     * (never the 2x/4x downsampled copies used on screen) and drawn with a fine mesh.
     */
    suspend fun render(
        repo: TileRepository,
        state: SkyUiState,
        viewW: Int,
        viewH: Int,
        options: CaptureOptions,
        progress: (CaptureState.Running) -> Unit,
    ): RenderedCapture = withContext(Dispatchers.Default) {
        val props = state.props ?: error("No survey is loaded")
        val survey = state.survey
        progress(CaptureState.Running(CapturePhase.PREPARING, 0, 0))

        val (w, h) = outputSize(viewW, viewH, options.scale)
        val proj = state.camera.projection(w.toFloat(), h.toFloat())
        val tiles = SkyRenderer.computeVisibleTiles(proj, props, targetScale = 1.0f, maxTiles = MAX_TILES)
        val leaves = tiles.filter { it.isLeaf }

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFF000000.toInt())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
            isAntiAlias = false
            isDither = false
            if (options.applyDisplayFilters) {
                DisplayFilter.matrix(state.settings.invert, state.settings.contrast, state.settings.brightness)
                    ?.let { colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
            }
        }

        // 1) Download the leaf tiles in small batches and paint each one as soon as it arrives.
        val failed = ArrayList<VisibleTile>()
        var done = 0
        for (batch in leaves.chunked(BATCH)) {
            coroutineContext.ensureActive()
            val results = coroutineScope {
                batch.map { t -> async { t to repo.fetchFull(survey, props, t.order, t.npix) } }.awaitAll()
            }
            for ((tile, tileBmp) in results) {
                if (tileBmp == null) {
                    failed.add(tile)
                } else {
                    drawTile(canvas, proj, tile, tileBmp, paint)
                    tileBmp.recycle()
                }
            }
            done += batch.size
            progress(CaptureState.Running(CapturePhase.DOWNLOADING, done, leaves.size))
        }

        // 2) Tiles that do not exist (sparse survey) or kept failing: fill the hole with the
        //    nearest coarser ancestor, clipped to the missing tile so good pixels are untouched.
        var missing = 0
        if (failed.isNotEmpty()) {
            var n = 0
            for (leaf in failed) {
                coroutineContext.ensureActive()
                progress(CaptureState.Running(CapturePhase.FALLBACK, n++, failed.size))
                if (!paintFromAncestor(canvas, proj, leaf, props, survey, repo, paint)) missing++
            }
        }

        // 3) Optional overlays (off by default: the export is the sky and nothing else).
        drawOverlays(canvas, proj, state, options, w.toFloat() / viewW)

        val meta = metadata(state, props, survey, w, h, options)
        RenderedCapture(bmp, missing, meta, suggestedName(survey, state, options.format))
    }

    private fun drawTile(canvas: Canvas, proj: Projection, tile: VisibleTile, bmp: Bitmap, paint: Paint) {
        // A fine mesh (>= 8 cells per side) keeps the shared edges between tiles seamless.
        val mesh = SkyRenderer.placementTriangles(proj, tile, bmp.width, bmp.height, minCells = 8) ?: return
        TileMeshPainter.draw(canvas, bmp, mesh, paint)
    }

    private suspend fun paintFromAncestor(
        canvas: Canvas,
        proj: Projection,
        leaf: VisibleTile,
        props: HiPSProperties,
        survey: HiPSSurvey,
        repo: TileRepository,
        paint: Paint,
    ): Boolean {
        val clip = Path()
        leaf.cornersRadec.forEachIndexed { i, c ->
            val p = proj.forwardClamped(c[0], c[1])
            if (i == 0) clip.moveTo(p[0], p[1]) else clip.lineTo(p[0], p[1])
        }
        clip.close()
        var order = leaf.order
        var npix = leaf.npix
        var tries = 0
        while (order > props.orderMin && tries < 4) {
            order--
            npix = npix shr 2
            tries++
            val bmp = repo.fetchFull(survey, props, order, npix) ?: continue
            val anc = VisibleTile(
                order, npix,
                Healpix.corners(order, npix, leaf.frame), Healpix.center(order, npix, leaf.frame),
                1f, true, leaf.frame,
            )
            canvas.save()
            canvas.clipPath(clip)
            drawTile(canvas, proj, anc, bmp, paint)
            canvas.restore()
            bmp.recycle()
            return true
        }
        return false
    }

    private fun drawOverlays(canvas: Canvas, proj: Projection, state: SkyUiState, o: CaptureOptions, k: Float) {
        val line = Paint().apply { isAntiAlias = true; style = Paint.Style.STROKE }
        if (o.includeGrid) {
            line.color = 0x59FFFFFF
            line.strokeWidth = 1f * k
            val seg = CoordGrid.segments(proj, state.camera.fovDeg)
            var i = 0
            while (i + 3 < seg.size) {
                canvas.drawLine(seg[i], seg[i + 1], seg[i + 2], seg[i + 3], line)
                i += 4
            }
        }
        val fill = Paint().apply { isAntiAlias = true; style = Paint.Style.FILL }
        if (o.includeCatalog && state.catalogVisible) {
            fill.color = 0xD922C55E.toInt()
            for (row in state.catalogRows) {
                val p = proj.forward(row.raDeg, row.decDeg) ?: continue
                val r = row.mag?.let { (8f - it.toFloat().coerceIn(0f, 20f) * 0.3f).coerceIn(1.5f, 5f) } ?: 2.5f
                canvas.drawCircle(p[0], p[1], r * k, fill)
            }
        }
        if (o.includeMarkers) {
            for (m in state.markers) {
                val p = proj.forward(m.position.raDeg, m.position.decDeg) ?: continue
                fill.color = m.color.toInt()
                canvas.drawCircle(p[0], p[1], 6f * k, fill)
                line.color = 0xFFFFFFFF.toInt()
                line.strokeWidth = 1.5f * k
                canvas.drawCircle(p[0], p[1], 6f * k, line)
            }
            line.color = 0xFFEF4444.toInt()
            line.strokeWidth = 2f * k
            for (me in state.measurements) {
                val a = proj.forward(me.from.raDeg, me.from.decDeg) ?: continue
                val b = proj.forward(me.to.raDeg, me.to.decDeg) ?: continue
                canvas.drawLine(a[0], a[1], b[0], b[1], line)
            }
        }
    }

    // ------------------------------------------------------------------ naming and metadata

    fun suggestedName(survey: HiPSSurvey, state: SkyUiState, format: CaptureFormat): String {
        val c = state.camera.center
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val slug = survey.title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "sky" }
        return String.format(
            Locale.US, "astrovia_%s_ra%.3f_dec%+.3f_fov%.3f_%s.%s",
            slug, c.raDeg, c.decDeg, state.camera.fovDeg, stamp, format.ext,
        )
    }

    fun metadata(
        state: SkyUiState,
        props: HiPSProperties,
        survey: HiPSSurvey,
        w: Int,
        h: Int,
        o: CaptureOptions,
    ): Map<String, String> {
        val c = state.camera.center
        return linkedMapOf(
            "Software" to "Astrovia",
            "Title" to "${survey.title} - Astrovia sky view",
            "Source" to "HiPS ${survey.id} ${survey.baseUrl}",
            "Astrovia:Center" to String.format(Locale.US, "RA %.6f deg, Dec %+.6f deg (ICRS)", c.raDeg, c.decDeg),
            "Astrovia:FieldOfView" to String.format(Locale.US, "%.6f deg (vertical)", state.camera.fovDeg),
            "Astrovia:Projection" to "ZEA",
            "Astrovia:HiPSFrame" to props.frame,
            "Astrovia:Size" to "${w}x$h px, scale ${o.scale}x",
            "Creation Time" to SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
            }.format(Date()),
        )
    }

    // ------------------------------------------------------------------------------- saving

    /** Android 10+: save into Pictures/Astrovia through MediaStore (no permission needed). */
    suspend fun saveToGallery(ctx: Context, r: RenderedCapture, format: CaptureFormat): Pair<Uri, Long> =
        withContext(Dispatchers.IO) {
            require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, r.name)
                put(MediaStore.Images.Media.MIME_TYPE, format.mime)
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Astrovia")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Could not create the image in the gallery")
            try {
                val bytes = resolver.openOutputStream(uri)?.use { encode(ctx, r, format, it) }
                    ?: throw java.io.IOException("Could not open the destination")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                uri to bytes
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        }

    /** Name shown for the folder the user chose (falls back to the raw id). */
    fun folderLabel(ctx: Context, tree: Uri): String = try {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        ctx.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: DocumentsContract.getTreeDocumentId(tree)
    } catch (e: Exception) {
        tree.lastPathSegment ?: "selected folder"
    }

    /**
     * Any Android version: create the file inside the folder the user picked in Settings (a persisted
     * Storage Access Framework tree), so no per-capture prompt and no storage permission are needed.
     */
    suspend fun saveToTree(ctx: Context, r: RenderedCapture, format: CaptureFormat, tree: Uri): Pair<Uri, Long> =
        withContext(Dispatchers.IO) {
            val resolver = ctx.contentResolver
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(resolver, parent, format.mime, r.name)
                ?: throw java.io.IOException("Could not create the file in the chosen folder")
            try {
                val bytes = resolver.openOutputStream(doc)?.use { encode(ctx, r, format, it) }
                    ?: throw java.io.IOException("Could not open the destination")
                doc to bytes
            } catch (e: Exception) {
                try { DocumentsContract.deleteDocument(resolver, doc) } catch (ignored: Exception) { /* best effort */ }
                throw e
            }
        }

    /** Any Android version: write to a location the user picked (Storage Access Framework). */
    suspend fun saveToUri(ctx: Context, r: RenderedCapture, format: CaptureFormat, uri: Uri): Long =
        withContext(Dispatchers.IO) {
            ctx.contentResolver.openOutputStream(uri)?.use { encode(ctx, r, format, it) }
                ?: throw java.io.IOException("Could not open the destination")
        }

    private fun encode(ctx: Context, r: RenderedCapture, format: CaptureFormat, out: OutputStream): Long {
        val counting = CountingOutputStream(out)
        if (format == CaptureFormat.JPEG) {
            r.bitmap.compress(Bitmap.CompressFormat.JPEG, 100, counting)
        } else {
            // Encode to a temp file, then copy it inserting the metadata chunks (no re-encoding).
            val tmp = File.createTempFile("capture", ".png", ctx.cacheDir)
            try {
                tmp.outputStream().buffered(1 shl 16).use { r.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                PngMeta.copyWithText(tmp, counting, r.meta)
            } finally {
                tmp.delete()
            }
        }
        counting.flush()
        return counting.count
    }

    private class CountingOutputStream(private val inner: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            inner.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            inner.write(b, off, len)
            count += len
        }

        override fun flush() = inner.flush()
    }
}
