@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.gallery

import kotlin.math.ceil
import kotlin.math.max

/**
 * Tile pyramid of a very large picture (a 30 000 x 38 000 px Webb image): the viewer only downloads the tiles it needs.
 * Two layouts are used by the outreach archives: Zoomify (ImageProperties.xml, TileGroupN/z-x-y.jpg) and Deep Zoom
 * (.dzi, <name>_files/level/col_row.jpg). Levels are numbered 0 = smallest ... [maxLevel] = full resolution.
 */
abstract class TilePyramid(val width: Int, val height: Int, val tileSize: Int) {
    abstract val maxLevel: Int
    /** Pixel size of a level. */
    fun levelWidth(level: Int): Int = ceil(width / Math.pow(2.0, (maxLevel - level).toDouble())).toInt().coerceAtLeast(1)
    fun levelHeight(level: Int): Int = ceil(height / Math.pow(2.0, (maxLevel - level).toDouble())).toInt().coerceAtLeast(1)
    fun cols(level: Int) = ceil(levelWidth(level).toDouble() / tileSize).toInt()
    fun rows(level: Int) = ceil(levelHeight(level).toDouble() / tileSize).toInt()
    abstract fun tileUrl(level: Int, col: Int, row: Int): String

    /** Largest level that fits in a single tile (used for thumbnails). */
    fun thumbnailLevel(): Int = (maxLevel downTo 0).firstOrNull { cols(it) == 1 && rows(it) == 1 } ?: 0

    /** Pixel rectangle (left, top, right, bottom) of a tile inside its level, ignoring overlaps. */
    open fun tileRect(level: Int, col: Int, row: Int): IntArray {
        val l = col * tileSize; val t = row * tileSize
        return intArrayOf(l, t, minOf(l + tileSize, levelWidth(level)), minOf(t + tileSize, levelHeight(level)))
    }
}

/** Zoomify layout. Levels 0..maxLevel from the smallest tier (<= one tile) to the full size. */
class ZoomifyPyramid(private val base: String, width: Int, height: Int, tileSize: Int) : TilePyramid(width, height, tileSize) {
    override val maxLevel: Int = run {
        var w = width; var h = height; var n = 0
        while (w > tileSize || h > tileSize) { w = (w + 1) / 2; h = (h + 1) / 2; n++ }
        n
    }

    /** Index of the first tile of a level in the whole pyramid (tiles are numbered from the smallest tier). */
    private fun firstTile(level: Int): Int { var s = 0; for (l in 0 until level) s += cols(l) * rows(l); return s }

    override fun tileUrl(level: Int, col: Int, row: Int): String {
        val index = firstTile(level) + row * cols(level) + col
        return "$base/TileGroup${index / 256}/$level-$col-$row.jpg"
    }

    companion object {
        /** From the ImageProperties.xml text: <IMAGE_PROPERTIES WIDTH=".." HEIGHT=".." TILESIZE=".." /> */
        fun parse(base: String, xml: String): ZoomifyPyramid? {
            fun attr(n: String) = Regex("$n=\"(\\d+)\"").find(xml)?.groupValues?.get(1)?.toIntOrNull()
            val w = attr("WIDTH") ?: return null; val h = attr("HEIGHT") ?: return null
            return ZoomifyPyramid(base.trimEnd('/'), w, h, attr("TILESIZE") ?: 256)
        }
    }
}

/** Deep Zoom layout; the tiles of a level overlap by [overlap] pixels. */
class DeepZoomPyramid(private val base: String, width: Int, height: Int, tileSize: Int, val overlap: Int, private val format: String) : TilePyramid(width, height, tileSize) {
    override val maxLevel: Int = ceil(Math.log(max(width, height).toDouble()) / Math.log(2.0)).toInt()
    override fun tileUrl(level: Int, col: Int, row: Int) = "${base}_files/$level/${col}_$row.$format"

    override fun tileRect(level: Int, col: Int, row: Int): IntArray {
        // the tile image includes `overlap` extra pixels on the sides that touch another tile
        val l = col * tileSize; val t = row * tileSize
        return intArrayOf(l, t, minOf(l + tileSize, levelWidth(level)), minOf(t + tileSize, levelHeight(level)))
    }

    /** Pixels of overlap present on the left/top of the tile image. */
    fun leftOverlap(col: Int) = if (col > 0) overlap else 0
    fun topOverlap(row: Int) = if (row > 0) overlap else 0

    companion object {
        fun parse(dziUrl: String, xml: String): DeepZoomPyramid? {
            fun attr(n: String) = Regex("$n=\"(\\w+)\"").find(xml)?.groupValues?.get(1)
            val w = attr("Width")?.toIntOrNull() ?: return null; val h = attr("Height")?.toIntOrNull() ?: return null
            return DeepZoomPyramid(dziUrl.removeSuffix(".dzi"), w, h, attr("TileSize")?.toIntOrNull() ?: 254, attr("Overlap")?.toIntOrNull() ?: 1, attr("Format") ?: "jpg")
        }
    }
}

/** Opens the right pyramid for the `tiles_url` of an outreach image (".dzi" file or a Zoomify folder). */
object TilePyramids {
    suspend fun open(tilesUrl: String, fetch: suspend (String) -> String?): TilePyramid? =
        if (tilesUrl.endsWith(".dzi")) fetch(tilesUrl)?.let { DeepZoomPyramid.parse(tilesUrl, it) }
        else fetch(tilesUrl.trimEnd('/') + "/ImageProperties.xml")?.let { ZoomifyPyramid.parse(tilesUrl, it) }
}
