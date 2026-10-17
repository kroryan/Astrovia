package com.kroryan.astrovia.fits

import com.kroryan.astrovia.data.fits.FitsFile
import com.kroryan.astrovia.data.fits.FitsRender
import com.kroryan.astrovia.data.fits.Wcs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class FitsTest {
    private fun card(k: String, v: String) = (k.padEnd(8) + "= " + v).padEnd(80)

    private fun header(cards: List<String>): ByteArray {
        val sb = StringBuilder()
        cards.forEach { sb.append(it.padEnd(80)) }
        sb.append("END".padEnd(80))
        while (sb.length % 2880 != 0) sb.append(' ')
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    private fun pad(b: ByteArray): ByteArray = b.copyOf(((b.size + 2879) / 2880) * 2880)

    private fun imageFile(): File {
        val out = ByteArrayOutputStream()
        out.write(header(listOf(card("SIMPLE", "T"), card("BITPIX", "16"), card("NAXIS", "2"), card("NAXIS1", "4"), card("NAXIS2", "3"),
            card("BZERO", "100"), card("BSCALE", "2"),
            card("CTYPE1", "'RA---TAN'"), card("CTYPE2", "'DEC--TAN'"), card("CRPIX1", "2.0"), card("CRPIX2", "2.0"),
            card("CRVAL1", "83.82"), card("CRVAL2", "-5.39"), card("CD1_1", "-0.001"), card("CD1_2", "0"), card("CD2_1", "0"), card("CD2_2", "0.001"))))
        val bb = ByteBuffer.allocate(24)
        for (i in 0 until 12) bb.putShort(i.toShort())
        out.write(pad(bb.array()))
        // BINTABLE: RA (D), DEC (D), NAME (4A), MAG (E)
        out.write(header(listOf(card("XTENSION", "'BINTABLE'"), card("BITPIX", "8"), card("NAXIS", "2"), card("NAXIS1", "24"), card("NAXIS2", "2"),
            card("PCOUNT", "0"), card("GCOUNT", "1"), card("TFIELDS", "4"),
            card("TTYPE1", "'RA'"), card("TFORM1", "'1D'"), card("TTYPE2", "'DEC'"), card("TFORM2", "'1D'"),
            card("TTYPE3", "'NAME'"), card("TFORM3", "'4A'"), card("TTYPE4", "'MAG'"), card("TFORM4", "'1E'"))))
        val t = ByteBuffer.allocate(48)
        t.putDouble(10.5).putDouble(-20.25).put("abcd".toByteArray()).putFloat(12.5f)
        t.putDouble(11.5).putDouble(-21.25).put("efgh".toByteArray()).putFloat(13.5f)
        out.write(pad(t.array()))
        val f = File.createTempFile("test", ".fits")
        f.writeBytes(out.toByteArray())
        f.deleteOnExit()
        return f
    }

    @Test
    fun readsImageAndTable() {
        FitsFile.open(imageFile()).use { fits ->
            assertEquals(2, fits.hdus.size)
            val img = fits.readImage(fits.imageHdus.first())
            assertEquals(4, img.width); assertEquals(3, img.height)
            assertEquals(100f, img.values[0], 0f)          // BZERO
            assertEquals(100f + 2 * 11, img.values[11], 0f)  // BSCALE
            val t = fits.readTable(fits.tableHdus.first())
            assertEquals(2, t.rows.size)
            assertEquals("abcd", t.rows[0]["NAME"])
            assertEquals("10.5", t.rows[0]["RA"])
            assertEquals("13.5", t.rows[1]["MAG"])
        }
    }

    @Test
    fun wcsTanRoundTrip() {
        FitsFile.open(imageFile()).use { fits ->
            val w = fits.readImage(fits.imageHdus.first()).wcs
            assertNotNull(w)
            val c = w!!.pixelToSky(2.0, 2.0)!!
            assertEquals(83.82, c[0], 1e-9); assertEquals(-5.39, c[1], 1e-9)
            val p = w.pixelToSky(4.0, 3.0)!!
            val back = w.skyToPixel(p[0], p[1])!!
            assertEquals(4.0, back[0], 1e-6); assertEquals(3.0, back[1], 1e-6)
            // one pixel = 0.001 deg, RA increases to the left
            assertTrue(w.pixelToSky(3.0, 2.0)!![0] < 83.82)
            assertEquals(0.001, Wcs.scaleDeg(w), 1e-9)
        }
    }

    @Test
    fun galacticCentre() {
        val e = Wcs.galToEq(0.0, 0.0)
        assertEquals(266.405, e[0], 0.01); assertEquals(-28.936, e[1], 0.01)
        val g = Wcs.eqToGal(e[0], e[1])
        assertTrue(g[0] < 0.01 || g[0] > 359.99); assertEquals(0.0, g[1], 0.01)
    }

    @Test
    fun cutsAndRender() {
        val (lo, hi) = FitsRender.cuts(FloatArray(1000) { it.toFloat() })
        assertTrue(lo < hi)
        assertEquals(0xFF000000.toInt(), FitsRender.color(0f, com.kroryan.astrovia.data.fits.ColorMap.GRAY))
    }
}
