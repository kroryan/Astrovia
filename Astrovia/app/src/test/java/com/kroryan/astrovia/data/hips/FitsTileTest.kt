package com.kroryan.astrovia.data.hips

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class FitsTileTest {
    private fun card(s: String) = s.padEnd(80).toByteArray(Charsets.US_ASCII)

    private fun fits(bitpix: Int, n1: Int, n2: Int, data: ByteArray, extra: List<String> = emptyList()): ByteArray {
        val cards = mutableListOf("SIMPLE  =                    T", "BITPIX  = ${bitpix.toString().padStart(20)}",
            "NAXIS   =                    2", "NAXIS1  = ${n1.toString().padStart(20)}", "NAXIS2  = ${n2.toString().padStart(20)}")
        cards.addAll(extra)
        cards.add("END")
        val hdr = ByteArray(2880) { ' '.code.toByte() }
        cards.forEachIndexed { i, c -> System.arraycopy(card(c), 0, hdr, i * 80, 80) }
        val padded = ByteArray(((data.size + 2879) / 2880) * 2880)
        System.arraycopy(data, 0, padded, 0, data.size)
        return hdr + padded
    }

    @Test
    fun decodesInt16AndStretchesBetweenTheCuts() {
        // 2 columns x 2 rows, FITS order: bottom row first
        val bb = ByteBuffer.allocate(8)
        listOf<Short>(0, 100, 200, 300).forEach { bb.putShort(it) }
        val pic = FitsTileDecoder.decode(ByteArrayInputStream(fits(16, 2, 2, bb.array())), 0.0, 300.0)
        assertNotNull(pic)
        assertEquals(2, pic!!.width)
        // the picture is flipped vertically: output row 0 (top) is the LAST FITS row (200, 300)
        fun g(i: Int) = pic.argb[i] and 0xFF
        assertEquals(170, g(0)) // 200 -> 170
        assertEquals(255, g(1)) // 300 -> 255
        assertEquals(0, g(2))   // bottom row: 0
        assertEquals(85, g(3))  // 100 -> 85
    }

    @Test
    fun appliesBscaleAndBzeroAndHandlesFloatNaN() {
        val bb = ByteBuffer.allocate(16)
        listOf(0f, 1f, Float.NaN, 2f).forEach { bb.putFloat(it) }
        val pic = FitsTileDecoder.decode(
            ByteArrayInputStream(fits(-32, 2, 2, bb.array(), listOf("BZERO   =                  0.0", "BSCALE  =                  2.0"))),
            0.0, 4.0,
        )!!
        fun g(i: Int) = pic.argb[i] and 0xFF
        assertEquals(0, g(2)) // bottom-left 0
        assertEquals(127, g(3)) // 1*2 = 2 of 4 -> 127
        assertEquals(0, g(0)) // NaN -> black
        assertEquals(255, g(1)) // 2*2 = 4 -> 255
    }

    @Test
    fun withoutCutsUsesPercentiles() {
        val bb = ByteBuffer.allocate(2 * 16)
        for (i in 0 until 16) bb.putShort((i * 10).toShort())
        val pic = FitsTileDecoder.decode(ByteArrayInputStream(fits(16, 4, 4, bb.array())), null, null)!!
        val values = pic.argb.map { it and 0xFF }
        assertEquals(0, values.min())
        assertEquals(255, values.max())
    }

    @Test
    fun downsamplesAndRejectsGarbage() {
        val bb = ByteBuffer.allocate(2 * 16)
        for (i in 0 until 16) bb.putShort(i.toShort())
        val pic = FitsTileDecoder.decode(ByteArrayInputStream(fits(16, 4, 4, bb.array())), 0.0, 15.0, sample = 2)!!
        assertEquals(2, pic.width)
        assertEquals(2, pic.height)
        assertNull(FitsTileDecoder.decode(ByteArrayInputStream(ByteArray(100)), null, null))
        assertNull(FitsTileDecoder.decode(ByteArrayInputStream(fits(7, 2, 2, ByteArray(8))), null, null))
    }
}
