package com.kroryan.astrovia.data.hips

import java.io.DataInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An 8-bit grey picture (packed ARGB) decoded from a FITS tile. */
class FitsPicture(val width: Int, val height: Int, val argb: IntArray)

/**
 * Minimal FITS reader for HiPS tiles (a single 2D image in the primary HDU).
 *
 * HiPS keeps FITS tiles in the FITS convention (first row = bottom) while JPEG/PNG tiles are
 * stored top-down, so the picture is flipped vertically to match every other tile format
 * (verified against a survey that publishes both formats: correlation 0.986 after the flip).
 */
object FitsTileDecoder {
    private const val BLOCK = 2880
    private const val CARD = 80

    /**
     * @param cutMin/cutMax pixel values mapped to black/white (`hips_pixel_cut`); when null the
     *        1st and 99th percentiles of the tile are used.
     * @param sample keep one pixel out of [sample] in each direction (saves memory when zoomed out).
     */
    fun decode(input: InputStream, cutMin: Double?, cutMax: Double?, sample: Int = 1): FitsPicture? {
        val din = DataInputStream(input)
        var bitpix = 0
        var naxis = 0
        var n1 = 0
        var n2 = 0
        var bzero = 0.0
        var bscale = 1.0
        var ended = false
        var blocks = 0
        val card = ByteArray(CARD)
        while (!ended) {
            val block = ByteArray(BLOCK)
            try {
                din.readFully(block)
            } catch (e: java.io.IOException) {
                return null
            }
            blocks++
            if (blocks > 40) return null // absurd header
            for (i in 0 until BLOCK / CARD) {
                System.arraycopy(block, i * CARD, card, 0, CARD)
                val key = String(card, 0, 8, Charsets.US_ASCII).trim()
                if (key == "END") {
                    ended = true
                    break
                }
                if (card[8] != '='.code.toByte()) continue
                val raw = String(card, 10, CARD - 10, Charsets.US_ASCII).substringBefore('/').trim()
                when (key) {
                    "BITPIX" -> bitpix = raw.toIntOrNull() ?: 0
                    "NAXIS" -> naxis = raw.toIntOrNull() ?: 0
                    "NAXIS1" -> n1 = raw.toIntOrNull() ?: 0
                    "NAXIS2" -> n2 = raw.toIntOrNull() ?: 0
                    "BZERO" -> bzero = raw.toDoubleOrNull() ?: 0.0
                    "BSCALE" -> bscale = raw.toDoubleOrNull() ?: 1.0
                }
            }
        }
        if (naxis < 2 || n1 <= 0 || n2 <= 0 || n1.toLong() * n2 > 64L * 1024 * 1024) return null
        val bytesPer = when (bitpix) {
            8 -> 1
            16 -> 2
            32, -32 -> 4
            -64 -> 8
            else -> return null
        }
        val data = ByteArray(n1 * n2 * bytesPer)
        try {
            din.readFully(data)
        } catch (e: java.io.IOException) {
            return null
        }
        val bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
        val values = FloatArray(n1 * n2)
        for (i in values.indices) {
            val v: Double = when (bitpix) {
                8 -> (data[i].toInt() and 0xFF).toDouble()
                16 -> bb.getShort(i * 2).toDouble()
                32 -> bb.getInt(i * 4).toDouble()
                -32 -> bb.getFloat(i * 4).toDouble()
                else -> bb.getDouble(i * 8)
            }
            values[i] = if (v.isNaN()) Float.NaN else (v * bscale + bzero).toFloat()
        }

        var lo: Double
        var hi: Double
        if (cutMin != null && cutMax != null && cutMax > cutMin) {
            lo = cutMin
            hi = cutMax
        } else {
            val (a, b) = percentiles(values)
            lo = a
            hi = b
        }
        if (hi <= lo) hi = lo + 1.0

        val s = sample.coerceAtLeast(1)
        val w = (n1 + s - 1) / s
        val h = (n2 + s - 1) / s
        val out = IntArray(w * h)
        val scale = 255.0 / (hi - lo)
        for (y in 0 until h) {
            // flip vertically: output row 0 is the top row = last FITS row
            val srcRow = (n2 - 1 - y * s).coerceAtLeast(0)
            for (x in 0 until w) {
                val v = values[srcRow * n1 + (x * s).coerceAtMost(n1 - 1)]
                val g = if (v.isNaN()) 0 else ((v - lo) * scale).toInt().coerceIn(0, 255)
                out[y * w + x] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
        return FitsPicture(w, h, out)
    }

    private fun percentiles(values: FloatArray): Pair<Double, Double> {
        val step = (values.size / 16384).coerceAtLeast(1)
        val sample = ArrayList<Float>(values.size / step + 1)
        var i = 0
        while (i < values.size) {
            val v = values[i]
            if (!v.isNaN()) sample.add(v)
            i += step
        }
        if (sample.isEmpty()) return 0.0 to 1.0
        sample.sort()
        val lo = sample[(sample.size * 0.01).toInt().coerceIn(0, sample.size - 1)].toDouble()
        val hi = sample[(sample.size * 0.99).toInt().coerceIn(0, sample.size - 1)].toDouble()
        return lo to hi
    }
}
