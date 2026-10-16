package com.kroryan.astrovia.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

class CaptureTest {
    @Test
    fun outputSizeKeepsTheScreenAspectAndRespectsTheLimits() {
        val (w1, h1) = SkyCapture.outputSize(1080, 2400, 1, 36_000_000L)
        assertEquals(1080 to 2400, w1 to h1)
        val (w3, h3) = SkyCapture.outputSize(1080, 2400, 3, 36_000_000L)
        assertEquals(3240 to 7200, w3 to h3)
        // too big: shrunk, same aspect, within side and pixel limits
        val (w4, h4) = SkyCapture.outputSize(1080, 2400, 4, 36_000_000L)
        assertTrue(w4 <= 8192 && h4 <= 8192 && w4.toLong() * h4 <= 36_000_000L)
        assertEquals(1080.0 / 2400.0, w4.toDouble() / h4, 0.005)
        // tiny memory budget
        val (wm, hm) = SkyCapture.outputSize(1080, 2400, 3, 4_000_000L)
        assertTrue(wm.toLong() * hm <= 4_000_000L)
    }

    @Test
    fun availableScalesAreOrderedAndStartAtOne() {
        val s = SkyCapture.availableScales(1080, 2400)
        assertEquals(1, s.first())
        assertEquals(s.sorted(), s)
    }

    private fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray(Charsets.US_ASCII)
        val crc = CRC32().apply { update(t); update(data) }.value.toInt()
        return be(data.size) + t + data + be(crc)
    }

    /** A real 8-bit greyscale PNG built by hand (java.awt is not available to Android unit tests). */
    private fun tinyPng(w: Int, h: Int, raw: ByteArray): ByteArray {
        val rows = ByteArray((w + 1) * h)
        for (y in 0 until h) {
            rows[y * (w + 1)] = 0
            System.arraycopy(raw, y * w, rows, y * (w + 1) + 1, w)
        }
        val def = Deflater().apply { setInput(rows); finish() }
        val buf = ByteArray(rows.size + 64)
        val n = def.deflate(buf)
        val ihdr = be(w) + be(h) + byteArrayOf(8, 0, 0, 0, 0)
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            chunk("IHDR", ihdr) + chunk("IDAT", buf.copyOf(n)) + chunk("IEND", ByteArray(0))
    }

    /** Splits a PNG into (type, data) pairs, checking each CRC. */
    private fun chunks(png: ByteArray): List<Pair<String, ByteArray>> {
        val out = ArrayList<Pair<String, ByteArray>>()
        var i = 8
        while (i < png.size) {
            val len = ((png[i].toInt() and 0xFF) shl 24) or ((png[i + 1].toInt() and 0xFF) shl 16) or
                ((png[i + 2].toInt() and 0xFF) shl 8) or (png[i + 3].toInt() and 0xFF)
            val type = String(png, i + 4, 4, Charsets.US_ASCII)
            val data = png.copyOfRange(i + 8, i + 8 + len)
            val crc = CRC32().apply { update(png, i + 4, 4 + len) }.value
            val stored = ((png[i + 8 + len].toLong() and 0xFF) shl 24) or ((png[i + 9 + len].toLong() and 0xFF) shl 16) or
                ((png[i + 10 + len].toLong() and 0xFF) shl 8) or (png[i + 11 + len].toLong() and 0xFF)
            assertEquals("CRC of $type", crc, stored)
            out.add(type to data)
            i += 12 + len
        }
        return out
    }

    @Test
    fun pngMetadataIsInsertedWithoutBreakingTheImage() {
        val raw = ByteArray(8 * 6) { (it * 5).toByte() }
        val src = tinyPng(8, 6, raw)
        val out = ByteArrayOutputStream()
        PngMeta.copyWithText(
            ByteArrayInputStream(src), out,
            linkedMapOf("Software" to "Astrovia", "Astrovia:Center" to "RA 85.4 Dec -1.9 \u00e9"),
        )
        val list = chunks(out.toByteArray())
        assertEquals(listOf("IHDR", "tEXt", "tEXt", "IDAT", "IEND"), list.map { it.first })
        assertEquals("Software\u0000Astrovia", String(list[1].second, Charsets.ISO_8859_1))
        assertEquals("Astrovia:Center\u0000RA 85.4 Dec -1.9 ?", String(list[2].second, Charsets.ISO_8859_1))
        // the compressed picture is untouched
        val inf = Inflater().apply { setInput(list[3].second) }
        val rows = ByteArray((8 + 1) * 6)
        inf.inflate(rows)
        for (y in 0 until 6) for (x in 0 until 8) assertEquals(raw[y * 8 + x], rows[y * 9 + 1 + x])
    }

    @Test
    fun pngChunkCrcIsCorrect() {
        val chunk = PngMeta.textChunk("Key", "Value")
        val len = ((chunk[0].toInt() and 0xFF) shl 24) or ((chunk[1].toInt() and 0xFF) shl 16) or
            ((chunk[2].toInt() and 0xFF) shl 8) or (chunk[3].toInt() and 0xFF)
        assertEquals("Key".length + 1 + "Value".length, len)
        val crc = java.util.zip.CRC32().apply { update(chunk, 4, 4 + len) }.value
        val stored = ((chunk[8 + len].toLong() and 0xFF) shl 24) or ((chunk[9 + len].toLong() and 0xFF) shl 16) or
            ((chunk[10 + len].toLong() and 0xFF) shl 8) or (chunk[11 + len].toLong() and 0xFF)
        assertEquals(crc, stored)
    }
}
