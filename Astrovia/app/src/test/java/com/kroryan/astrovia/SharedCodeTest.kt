package com.kroryan.astrovia

import cds.astrovia.imgproc.FitsIO
import cds.astrovia.imgproc.FitsTables
import cds.astrovia.imgproc.Img
import cds.astrovia.sky.Adql
import cds.astrovia.sky.Bands
import cds.astrovia.sky.LightCurveCols
import cds.astrovia.sky.Stcs
import cds.astrovia.sky.VotableReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Direct tests of the Java code shared by the Android and the desktop apps. */
class SharedCodeTest {
    private fun card(k: String, v: String) = (k.padEnd(8) + "= " + v).padEnd(80)
    private fun header(cards: List<String>): ByteArray {
        val sb = StringBuilder(); cards.forEach { sb.append(it.padEnd(80)) }; sb.append("END".padEnd(80))
        while (sb.length % 2880 != 0) sb.append(' ')
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    @Test
    fun fitsTablesReadsLightCurveColumns() {
        val out = ByteArrayOutputStream()
        out.write(header(listOf(card("SIMPLE", "T"), card("BITPIX", "8"), card("NAXIS", "0"))))
        out.write(header(listOf(card("XTENSION", "'BINTABLE'"), card("BITPIX", "8"), card("NAXIS", "2"), card("NAXIS1", "12"), card("NAXIS2", "3"), card("PCOUNT", "0"), card("GCOUNT", "1"),
            card("TFIELDS", "2"), card("EXTNAME", "'LIGHTCURVE'"), card("BJDREFI", "2457000"), card("TTYPE1", "'TIME'"), card("TFORM1", "'1D'"), card("TTYPE2", "'SAP_FLUX'"), card("TFORM2", "'1E'"))))
        val b = ByteBuffer.allocate(36)
        for (i in 0 until 3) b.putDouble(1.0 + i).putFloat(100f + i)
        out.write(b.array().copyOf(2880))
        val f = File.createTempFile("shared", ".fits"); f.writeBytes(out.toByteArray()); f.deleteOnExit()
        val t = FitsTables.read(f, 100).single()
        assertEquals("LIGHTCURVE", t.extname); assertEquals(2457000.0, t.bjdRef, 0.0)
        assertEquals(2.0, t.data["TIME"]!![1], 0.0); assertEquals(102f, t.data["SAP_FLUX"]!![2].toFloat(), 0f)
        assertEquals(listOf("TIME", "SAP_FLUX"), LightCurveCols.detect(t.columns)!!.toList())
        // the same file as an image reader sees it: no image
        assertTrue(runCatching { FitsIO.read(f) }.isFailure)
    }

    @Test
    fun stcsPolygonsCirclesAndUnions() {
        val u = Stcs.parse("Union ICRS (Polygon J2000 1 1 2 1 2 2 Polygon J2000 10 10 11 10 11 11)")
        assertEquals(2, u.size); assertEquals(1.0, u[0][0][0], 0.0); assertEquals(11.0, u[1][1][2], 0.0)
        assertEquals(32, Stcs.parse("Circle ICRS 10 20 0.5").single()[0].size)
        assertEquals(4, Stcs.parse("Box ICRS 10 20 1 2").single()[0].size)
        assertTrue(Stcs.parse(null).isEmpty()); assertTrue(Stcs.parse("POSITION J2000 83.9 -5.4").isEmpty())
    }

    @Test
    fun adqlQueries() {
        assertEquals("SELECT TOP 5 * FROM t WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',10.000000,-5.000000,0.300000)) AND (a=1) ORDER BY m ASC", Adql.cone("t", "ra", "dec", 10.0, -5.0, 0.3, 5, "a=1", "m"))
        assertTrue(Adql.cone("t", "ra", "dec", 10.0, -5.0, 500.0, 5, null, null).contains("90.000000"))        // radius is capped
        assertEquals("SELECT COUNT(*) AS n FROM t WHERE 1=CONTAINS(POINT('ICRS',ra,dec),CIRCLE('ICRS',1.000000,2.000000,0.100000))", Adql.count("t", "ra", "dec", 1.0, 2.0, 0.1, null))
    }

    @Test
    fun votableErrorsAndTabledata() {
        try { VotableReader.parse("""<VOTABLE><RESOURCE><INFO name="QUERY_STATUS" value="ERROR">Unknown table</INFO></RESOURCE></VOTABLE>"""); throw AssertionError() }
        catch (e: java.io.IOException) { assertEquals("Unknown table", e.message) }
        val rows = VotableReader.parse("""<VOTABLE><RESOURCE><TABLE><FIELD name="a" datatype="int"/><DATA><TABLEDATA><TR><TD>5</TD></TR></TABLEDATA></DATA></TABLE></RESOURCE></VOTABLE>""")
        assertEquals("5", rows.single()["a"])
    }

    @Test
    fun bandsAndNameOrder() {
        assertEquals("Radio", Bands.ofRange(0.05, 0.2)); assertEquals("Optical", Bands.ofRange(5e-7, 6e-7))
        assertNotNull(Bands.ofText("Image/Infrared/JWST")); assertEquals("Other", Bands.ofArchive("Various"))
        assertEquals("Gamma-ray", Bands.ALL.first())
        assertNotNull(Img(2, 2, 1))
    }
}

class GzipFitsTest {
    @Test
    fun gzippedFitsIsRead() {
        val img = Img(6, 4, 1)
        for (i in 0 until 24) img.p[0][i] = i.toFloat()
        val plain = File.createTempFile("gzplain", ".fits"); FitsIO.write(img, plain)
        val gz = File.createTempFile("gzpacked", ".fits.gz")
        java.util.zip.GZIPOutputStream(gz.outputStream()).use { o -> plain.inputStream().use { it.copyTo(o) } }
        assertTrue(FitsIO.isGzip(gz)); assertTrue(!FitsIO.isGzip(plain))
        val back = FitsIO.read(gz)
        assertEquals(6, back.w); assertEquals(23f, back.p[0][23], 0f)
    }
}
