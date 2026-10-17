@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.fits

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Header of one FITS HDU: the cards in order, with values as written (quotes removed from strings). */
class FitsHeader(val cards: List<Pair<String, String>>, val comments: List<String>) {
    private val map: Map<String, String> = LinkedHashMap<String, String>().also { m -> cards.forEach { (k, v) -> if (k !in m) m[k] = v } }
    fun str(key: String): String? = map[key]
    fun int(key: String): Int? = map[key]?.toDoubleOrNull()?.toInt()
    fun dbl(key: String): Double? = map[key]?.replace('D', 'E')?.toDoubleOrNull()
    fun has(key: String) = key in map
    /** Header as printable lines ("KEY = value"). */
    fun lines(): List<String> = cards.map { (k, v) -> "$k = $v" } + comments
}

enum class HduKind { IMAGE, TABLE, BINTABLE, OTHER }

class FitsHdu(val index: Int, val header: FitsHeader, val kind: HduKind, val dataStart: Long, val dataBytes: Long) {
    val name: String get() = header.str("EXTNAME") ?: if (index == 0) "PRIMARY" else "HDU$index"
}

/** A FITS image reduced for display: [values] (row 0 = FITS row 1 = bottom), possibly down-sampled by [step]. */
class FitsImage(
    val width: Int, val height: Int, val values: FloatArray,
    /** Original size of the image and the down-sampling step used to build [values]. */
    val origWidth: Int, val origHeight: Int, val step: Int,
    val header: FitsHeader, val wcs: Wcs?,
    val min: Float, val max: Float,
)

class FitsTable(val columns: List<String>, val rows: List<Map<String, String>>, val truncated: Boolean)

class FitsException(msg: String) : Exception(msg)

/**
 * FITS reader: any number of HDUs, images of any BITPIX (first plane of cubes), BINTABLE extensions.
 * The file is memory-mapped, so big images are read with a stride instead of being loaded whole.
 */
class FitsFile private constructor(private val raf: RandomAccessFile, private val buf: ByteBuffer, val hdus: List<FitsHdu>) : AutoCloseable {
    val imageHdus get() = hdus.filter { it.kind == HduKind.IMAGE && (it.header.int("NAXIS") ?: 0) >= 2 }
    val tableHdus get() = hdus.filter { it.kind == HduKind.BINTABLE }

    override fun close() { try { raf.close() } catch (e: Exception) { } }

    /** Reads an image HDU, keeping at most [maxSide] pixels per side. */
    fun readImage(hdu: FitsHdu, maxSide: Int = 4096): FitsImage {
        val h = hdu.header
        val bitpix = h.int("BITPIX") ?: throw FitsException("No BITPIX")
        val naxis = h.int("NAXIS") ?: 0
        if (naxis < 2) throw FitsException("This HDU has no image")
        val nx = h.int("NAXIS1") ?: 0
        val ny = h.int("NAXIS2") ?: 0
        if (nx <= 0 || ny <= 0) throw FitsException("Empty image")
        val bytes = abs(bitpix) / 8
        if (bitpix !in intArrayOf(8, 16, 32, 64, -32, -64)) throw FitsException("Unsupported BITPIX $bitpix")
        if (h.has("ZIMAGE") || h.str("XTENSION") == "BINTABLE") throw FitsException("Tile-compressed images (.fz) are not supported yet")
        val bscale = h.dbl("BSCALE") ?: 1.0
        val bzero = h.dbl("BZERO") ?: 0.0
        val blank = if (bitpix > 0) h.str("BLANK")?.toLongOrNull() else null
        val step = max(1, (max(nx, ny) + maxSide - 1) / maxSide)
        val w = (nx + step - 1) / step
        val hh = (ny + step - 1) / step
        val vals = FloatArray(w * hh)
        var lo = Float.POSITIVE_INFINITY
        var hi = Float.NEGATIVE_INFINITY
        val bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN)
        for (j in 0 until hh) {
            val srcRow = min(ny - 1, j * step)
            for (i in 0 until w) {
                val srcCol = min(nx - 1, i * step)
                val pos = hdu.dataStart + (srcRow.toLong() * nx + srcCol) * bytes
                if (pos + bytes > buf.limit()) { vals[j * w + i] = Float.NaN; continue }
                val p = pos.toInt()
                val raw: Double = when (bitpix) {
                    8 -> (bb.get(p).toInt() and 0xFF).toDouble()
                    16 -> bb.getShort(p).toDouble()
                    32 -> bb.getInt(p).toDouble()
                    64 -> bb.getLong(p).toDouble()
                    -32 -> bb.getFloat(p).toDouble()
                    else -> bb.getDouble(p)
                }
                val isBlank = blank != null && when (bitpix) { 8 -> (raw.toLong() == blank); 16 -> raw.toLong() == blank; 32 -> raw.toLong() == blank; else -> false }
                val v = if (raw.isNaN() || isBlank) Float.NaN else (raw * bscale + bzero).toFloat()
                vals[j * w + i] = v
                if (!v.isNaN() && !v.isInfinite()) { if (v < lo) lo = v; if (v > hi) hi = v }
            }
        }
        if (lo > hi) { lo = 0f; hi = 1f }
        return FitsImage(w, hh, vals, nx, ny, step, h, Wcs.from(h), lo, hi)
    }

    /** Reads a BINTABLE extension (up to [maxRows] rows). */
    fun readTable(hdu: FitsHdu, maxRows: Int = 50_000): FitsTable {
        val h = hdu.header
        val nrows = h.int("NAXIS2") ?: 0
        val rowLen = h.int("NAXIS1") ?: 0
        val nf = h.int("TFIELDS") ?: 0
        class Col(val name: String, val code: Char, val repeat: Int, val offset: Int, val width: Int, val scale: Double, val zero: Double)
        val cols = ArrayList<Col>()
        var off = 0
        for (c in 1..nf) {
            val form = (h.str("TFORM$c") ?: "").trim()
            val m = Regex("^(\\d*)([A-Za-z])").find(form) ?: throw FitsException("Bad TFORM$c: $form")
            val rep = m.groupValues[1].ifEmpty { "1" }.toInt()
            val code = m.groupValues[2][0].uppercaseChar()
            val w = when (code) { 'L', 'B', 'A' -> 1; 'I' -> 2; 'J', 'E' -> 4; 'K', 'D', 'C' -> if (code == 'C') 8 else 8; 'M' -> 16; 'X' -> (rep + 7) / 8; 'P' -> 8; 'Q' -> 16; else -> throw FitsException("Unsupported column type $code") }
            val size = if (code == 'X') w else w * rep
            cols.add(Col(h.str("TTYPE$c") ?: "col$c", code, rep, off, w, h.dbl("TSCAL$c") ?: 1.0, h.dbl("TZERO$c") ?: 0.0))
            off += size
        }
        val bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN)
        val n = min(nrows, maxRows)
        val rows = ArrayList<Map<String, String>>(n)
        for (r in 0 until n) {
            val base = hdu.dataStart + r.toLong() * rowLen
            if (base + rowLen > buf.limit()) break
            val row = LinkedHashMap<String, String>()
            for (c in cols) {
                val p = (base + c.offset).toInt()
                row[c.name] = when (c.code) {
                    'A' -> String(ByteArray(c.repeat) { bb.get(p + it) }, Charsets.US_ASCII).trim { it <= ' ' || it == '\u0000' }
                    'X', 'P', 'Q', 'C', 'M' -> ""
                    else -> {
                        if (c.repeat > 1) (0 until min(c.repeat, 4)).joinToString(",") { fmt(num(bb, p + it * c.width, c.code, c.scale, c.zero)) } + if (c.repeat > 4) ",…" else ""
                        else if (c.repeat == 0) "" else fmt(num(bb, p, c.code, c.scale, c.zero))
                    }
                }
            }
            rows.add(row)
        }
        return FitsTable(cols.map { it.name }, rows, nrows > maxRows)
    }

    /** Numeric columns by name (case-insensitive) as doubles, NaN where missing: for plotting without string round trips. */
    fun readColumns(hdu: FitsHdu, names: List<String>, maxRows: Int = 400_000): Map<String, DoubleArray> {
        val h = hdu.header
        val nrows = minOf(h.int("NAXIS2") ?: 0, maxRows)
        val rowLen = h.int("NAXIS1") ?: 0
        val nf = h.int("TFIELDS") ?: 0
        val out = LinkedHashMap<String, DoubleArray>()
        var off = 0
        val bb = buf.duplicate().order(ByteOrder.BIG_ENDIAN)
        for (c in 1..nf) {
            val form = (h.str("TFORM$c") ?: "").trim()
            val m = Regex("^(\\d*)([A-Za-z])").find(form) ?: throw FitsException("Bad TFORM$c: $form")
            val rep = m.groupValues[1].ifEmpty { "1" }.toInt()
            val code = m.groupValues[2][0].uppercaseChar()
            val w = when (code) { 'L', 'B', 'A' -> 1; 'I' -> 2; 'J', 'E' -> 4; 'K', 'D', 'P' -> 8; 'C' -> 8; 'M', 'Q' -> 16; 'X' -> (rep + 7) / 8; else -> throw FitsException("Unsupported column type $code") }
            val size = if (code == 'X') w else w * rep
            val name = h.str("TTYPE$c") ?: "col$c"
            if (names.any { it.equals(name, true) } && code in "BIJKED" && rep == 1) {
                val sc = h.dbl("TSCAL$c") ?: 1.0; val ze = h.dbl("TZERO$c") ?: 0.0
                val arr = DoubleArray(nrows)
                for (r in 0 until nrows) {
                    val p = (hdu.dataStart + r.toLong() * rowLen + off).toInt()
                    arr[r] = if (p + w > buf.limit()) Double.NaN else num(bb, p, code, sc, ze)
                }
                out[name] = arr
            }
            off += size
        }
        return out
    }

    private fun num(bb: ByteBuffer, p: Int, code: Char, scale: Double, zero: Double): Double {
        val v: Double = when (code) {
            'L' -> if (bb.get(p).toInt() == 'T'.code) 1.0 else 0.0
            'B' -> (bb.get(p).toInt() and 0xFF).toDouble()
            'I' -> bb.getShort(p).toDouble()
            'J' -> bb.getInt(p).toDouble()
            'K' -> bb.getLong(p).toDouble()
            'E' -> bb.getFloat(p).toDouble()
            else -> bb.getDouble(p)
        }
        return v * scale + zero
    }

    private fun fmt(v: Double): String = if (v.isNaN()) "" else if (v == Math.rint(v) && abs(v) < 1e15) v.toLong().toString() else "%.9g".format(java.util.Locale.US, v).let { t -> if (t.contains('e') || !t.contains('.')) t else t.trimEnd('0').trimEnd('.') }

    companion object {
        private const val BLOCK = 2880

        fun open(file: File): FitsFile {
            val raf = RandomAccessFile(file, "r")
            try {
                val len = raf.length()
                if (len > Int.MAX_VALUE) throw FitsException("File larger than 2 GB")
                if (len < BLOCK) throw FitsException("Not a FITS file (too small)")
                val buf = raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, len)
                return FitsFile(raf, buf, parseHdus(buf, len))
            } catch (e: Exception) {
                raf.close()
                throw e
            }
        }

        private fun parseHdus(buf: ByteBuffer, len: Long): List<FitsHdu> {
            val sig = ByteArray(9); buf.duplicate().get(sig)
            if (String(sig, Charsets.US_ASCII) != "SIMPLE  =") throw FitsException("Not a FITS file (no SIMPLE card)")
            val out = ArrayList<FitsHdu>()
            var pos = 0L
            while (pos + BLOCK <= len && out.size < 200) {
                val cards = ArrayList<Pair<String, String>>()
                val comments = ArrayList<String>()
                var ended = false
                var p = pos
                while (!ended && p + BLOCK <= len) {
                    val block = ByteArray(BLOCK); val d = buf.duplicate(); d.position(p.toInt()); d.get(block)
                    for (i in 0 until BLOCK / 80) {
                        val s = String(block, i * 80, 80, Charsets.US_ASCII)
                        val key = s.substring(0, 8).trim()
                        if (key == "END") { ended = true; break }
                        if (key.isEmpty()) continue
                        if (s.length > 10 && s[8] == '=') cards.add(key to cardValue(s.substring(10)))
                        else if (key == "COMMENT" || key == "HISTORY") comments.add("$key ${s.substring(8).trim()}")
                    }
                    p += BLOCK
                }
                if (!ended) break
                val hd = FitsHeader(cards, comments)
                val naxis = hd.int("NAXIS") ?: 0
                val bitpix = hd.int("BITPIX") ?: 8
                var elems = if (naxis == 0) 0L else 1L
                for (a in 1..naxis) elems *= (hd.int("NAXIS$a") ?: 0).toLong()
                val gcount = (hd.int("GCOUNT") ?: 1).toLong()
                val pcount = (hd.int("PCOUNT") ?: 0).toLong()
                val dataBytes = (abs(bitpix) / 8) * gcount * (pcount + elems)
                val xt = hd.str("XTENSION")
                val kind = when {
                    out.isEmpty() || xt == "IMAGE" -> HduKind.IMAGE
                    xt == "BINTABLE" -> HduKind.BINTABLE
                    xt == "TABLE" -> HduKind.TABLE
                    else -> HduKind.OTHER
                }
                out.add(FitsHdu(out.size, hd, kind, p, dataBytes))
                pos = p + ((dataBytes + BLOCK - 1) / BLOCK) * BLOCK
            }
            if (out.isEmpty()) throw FitsException("No HDU found")
            return out
        }

        private fun cardValue(rest: String): String {
            val t = rest.trimStart()
            if (t.startsWith("'")) {
                val sb = StringBuilder(); var i = 1
                while (i < t.length) {
                    if (t[i] == '\'') { if (i + 1 < t.length && t[i + 1] == '\'') { sb.append('\''); i += 2; continue } else break }
                    sb.append(t[i]); i++
                }
                return sb.toString().trimEnd()
            }
            return t.substringBefore('/').trim()
        }
    }
}
