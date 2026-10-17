package com.kroryan.astrovia.fits

import com.kroryan.astrovia.data.fits.FitsFile
import com.kroryan.astrovia.data.fits.LightCurves
import com.kroryan.astrovia.ui.niceTicks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

class LightCurveTest {
    private fun card(k: String, v: String) = (k.padEnd(8) + "= " + v).padEnd(80)
    private fun header(cards: List<String>): ByteArray {
        val sb = StringBuilder(); cards.forEach { sb.append(it.padEnd(80)) }; sb.append("END".padEnd(80))
        while (sb.length % 2880 != 0) sb.append(' ')
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun tessLikeLightCurve() {
        val out = ByteArrayOutputStream()
        out.write(header(listOf(card("SIMPLE", "T"), card("BITPIX", "8"), card("NAXIS", "0"))))
        // rows: TIME(D) PDCSAP_FLUX(E) PDCSAP_FLUX_ERR(E) QUALITY(J)
        out.write(header(listOf(card("XTENSION", "'BINTABLE'"), card("BITPIX", "8"), card("NAXIS", "2"), card("NAXIS1", "20"), card("NAXIS2", "4"),
            card("PCOUNT", "0"), card("GCOUNT", "1"), card("TFIELDS", "4"), card("OBJECT", "'TIC 1234'"), card("BJDREFI", "2457000"), card("BJDREFF", "0.0"),
            card("TTYPE1", "'TIME'"), card("TFORM1", "'1D'"), card("TTYPE2", "'PDCSAP_FLUX'"), card("TFORM2", "'1E'"),
            card("TTYPE3", "'PDCSAP_FLUX_ERR'"), card("TFORM3", "'1E'"), card("TTYPE4", "'QUALITY'"), card("TFORM4", "'1J'"))))
        val b = ByteBuffer.allocate(80)
        val rows = listOf(doubleArrayOf(1.0, 100.0, 1.0, 0.0), doubleArrayOf(2.0, 101.0, 1.0, 0.0), doubleArrayOf(3.0, Double.NaN, 1.0, 0.0), doubleArrayOf(4.0, 99.0, 1.0, 8.0))
        rows.forEach { r -> b.putDouble(r[0]).putFloat(r[1].toFloat()).putFloat(r[2].toFloat()).putInt(r[3].toInt()) }
        out.write(b.array().copyOf(2880))
        val f = File.createTempFile("lcurve", ".fits"); f.writeBytes(out.toByteArray()); f.deleteOnExit()
        FitsFile.open(f).use { fits ->
            val (hdu, cols) = LightCurves.find(fits)!!
            assertEquals("TIME" to "PDCSAP_FLUX", cols)
            val lc = LightCurves.read(fits, hdu, cols, "x")
            assertEquals(2, lc.time.size)            // NaN and flagged points dropped
            assertEquals("TIC 1234", lc.title)
            assertNotNull(lc.error)
            assertTrue(lc.timeLabel.contains("2457000"))
        }
    }

    @Test
    fun ticks() {
        val t = niceTicks(0.0, 10.0)
        assertTrue(t.first() >= 0.0 && t.last() <= 10.0 && t.size in 3..8)
    }
}
