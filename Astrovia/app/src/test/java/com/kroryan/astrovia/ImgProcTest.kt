package com.kroryan.astrovia

import cds.astrovia.imgproc.FitsIO
import cds.astrovia.imgproc.Img
import cds.astrovia.imgproc.Ops
import cds.astrovia.imgproc.Stacker
import cds.astrovia.imgproc.Stars
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class ImgProcTest {
    private val sigmaStar = 1.8
    private val starsAt = listOf(40 to 40, 120 to 60, 200 to 150, 80 to 200, 250 to 90, 160 to 230, 300 to 210, 60 to 120, 220 to 30, 280 to 140)

    /** Synthetic sky: gradient + noise + gaussian stars (optionally shifted). */
    private fun sky(w: Int = 340, h: Int = 260, dx: Double = 0.0, dy: Double = 0.0, seed: Long = 1, gradient: Boolean = true, noise: Double = 3.0): Img {
        val img = Img(w, h, 1)
        val r = Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            var v = 100.0 + (if (gradient) 40.0 * x / w + 25.0 * y / h else 0.0) + r.nextGaussian() * noise
            for ((sx, sy) in starsAt) {
                val d2 = (x - (sx + dx)) * (x - (sx + dx)) + (y - (sy + dy)) * (y - (sy + dy))
                v += 1500.0 * exp(-d2 / (2 * sigmaStar * sigmaStar))
            }
            img.p[0][y * w + x] = v.toFloat()
        }
        return img
    }

    @Test
    fun autoStretchPutsTheMedianNearTarget() {
        val out = Ops.autoStretch(sky(), 0.25, -2.8, true)
        val s = Img.stats(out.p[0], out.w, out.h)
        assertEquals(0.25, s.median, 0.03)
        assertTrue(s.min >= 0.0 && s.max <= 1.0)
    }

    @Test
    fun backgroundRemovalFlattensTheGradient() {
        val a = sky()
        fun slope(i: Img): Double { // difference of the median of the left and right strips
            val l = ArrayList<Float>(); val r = ArrayList<Float>()
            for (y in 0 until i.h) for (x in 0 until 30) { l.add(i.p[0][y * i.w + x]); r.add(i.p[0][y * i.w + i.w - 1 - x]) }
            return abs(Img.median(r.toFloatArray()) - Img.median(l.toFloatArray())).toDouble()
        }
        val b = Ops.removeBackground(a, 8, 2)
        assertTrue("before ${slope(a)} after ${slope(b)}", slope(a) > 30 && slope(b) < 5)
        // stars survive: the brightest pixel is still bright
        assertTrue(Img.stats(b.p[0], b.w, b.h).max > 1000)
    }

    @Test
    fun starsAreFoundWithTheRightSize() {
        val a = Ops.removeBackground(sky(), 8, 2)
        val rep = Stars.detect(a.p[0], a.w, a.h, 6.0, 100)
        assertTrue("found ${rep.count}", rep.count >= 9 && rep.count <= 12)
        assertEquals(2.3548 * sigmaStar, rep.medianFwhm, 1.0)
        assertTrue(rep.medianRoundness > 0.8)
    }

    @Test
    fun translationIsRecovered() {
        val ref = Stars.detect(sky(gradient = false).p[0], 340, 260, 6.0, 100).stars
        val cur = Stars.detect(sky(dx = 7.4, dy = -3.2, seed = 5, gradient = false).p[0], 340, 260, 6.0, 100).stars
        val t = Stars.translation(ref, cur, 40)
        assertNotNull(t)
        assertEquals(-7.4, t!![0], 0.4); assertEquals(3.2, t[1], 0.4)
    }

    @Test
    fun noiseReductionAndFilters() {
        val a = sky(gradient = false, noise = 10.0)
        val m = Ops.median(a, 1)
        assertTrue(Img.stats(m.p[0], m.w, m.h).noise < Img.stats(a.p[0], a.w, a.h).noise)
        val g = Ops.gaussianBlur(a, 1.5)
        assertTrue(Img.stats(g.p[0], g.w, g.h).noise < Img.stats(a.p[0], a.w, a.h).noise)
        // a hot pixel is removed by the cosmetic correction
        val hot = sky(gradient = false, noise = 2.0); hot.p[0][100 * hot.w + 100] = 60000f
        val fixed = IntArray(1)
        val c = Ops.cosmetic(hot, 8.0, 0.0, fixed)
        assertTrue(fixed[0] >= 1); assertTrue(c.p[0][100 * hot.w + 100] < 200f)
    }

    @Test
    fun deconvolutionSharpensStars() {
        val a = Ops.gaussianBlur(sky(gradient = false, noise = 0.5), 1.5)
        val d = Ops.deconvolve(a, 1.5, 20)
        assertTrue(Img.stats(d.p[0], d.w, d.h).max > Img.stats(a.p[0], a.w, a.h).max * 1.3)
    }

    @Test
    fun geometryKeepsTheSkyCoordinates() {
        val a = sky(100, 80)
        a.header["CRPIX1"] = "50.5"; a.header["CRPIX2"] = "40.5"; a.header["CDELT1"] = "-0.001"; a.header["CDELT2"] = "0.001"
        val b = Ops.bin(a, 2)
        assertEquals(50, b.w); assertEquals(25.5, b.header["CRPIX1"]!!.toDouble(), 1e-9); assertEquals(-0.002, b.header["CDELT1"]!!.toDouble(), 1e-12)
        val c = Ops.crop(a, 10, 20, 50, 40)
        assertEquals(40.5, c.header["CRPIX1"]!!.toDouble(), 1e-9); assertEquals(20.5, c.header["CRPIX2"]!!.toDouble(), 1e-9)
        val r = Ops.rotate90(a, true)
        assertEquals(80, r.w); assertTrue(!r.header.containsKey("CRPIX1"))
        assertEquals(a.p[0][5 * a.w + 7], Ops.flip(Ops.flip(a, true), true).p[0][5 * a.w + 7], 0f)
    }

    @Test
    fun debayerRecoversColours() {
        // a uniform colour scene (R=100, G=200, B=50) sampled through an RGGB mosaic (seen from the top row)
        val w = 20; val h = 20
        val raw = Img(w, h, 1)
        for (y in 0 until h) for (x in 0 until w) {
            val top = h - 1 - y
            val c = when { top % 2 == 0 && x % 2 == 0 -> 100f; top % 2 == 1 && x % 2 == 1 -> 50f; else -> 200f }
            raw.p[0][y * w + x] = c
        }
        val rgb = Ops.debayer(raw, "RGGB")
        assertEquals(3, rgb.c)
        for (y in 3 until h - 3) for (x in 3 until w - 3) {
            assertEquals(100f, rgb.p[0][y * w + x], 0.01f); assertEquals(200f, rgb.p[1][y * w + x], 0.01f); assertEquals(50f, rgb.p[2][y * w + x], 0.01f)
        }
    }

    @Test
    fun colourTools() {
        val rgb = Img(4, 4, 3)
        for (i in 0 until 16) { rgb.p[0][i] = 100f; rgb.p[1][i] = 160f; rgb.p[2][i] = 100f }
        val s = Ops.scnr(rgb, 1.0)
        assertEquals(100f, s.p[1][0], 0.01f)
        val g = Ops.saturation(rgb, -1.0)
        assertEquals(g.p[0][0], g.p[1][0], 0.01f)
        val wb = Ops.whiteBalance(rgb)
        assertEquals(160f, wb.p[0][0], 0.1f)
    }

    @Test
    fun fitsRoundTripAndStackWithCalibration() {
        val dir = File.createTempFile("stk", "").let { it.delete(); it.mkdirs(); it }
        dir.deleteOnExit()
        val dark = Img(340, 260, 1); val flat = Img(340, 260, 1)
        val r = Random(9)
        for (i in 0 until dark.p[0].size) { dark.p[0][i] = 20f + r.nextGaussian().toFloat() * 0.2f }
        for (y in 0 until 260) for (x in 0 until 340) { val dx = (x - 170) / 170.0; flat.p[0][y * 340 + x] = (1000.0 * (1.0 - 0.3 * dx * dx)).toFloat() }
        FitsIO.write(dark, File(dir, "dark.fits")); FitsIO.write(flat, File(dir, "flat.fits"))
        val back = FitsIO.read(File(dir, "dark.fits"))
        assertEquals(dark.p[0][1234], back.p[0][1234], 0f)
        val shifts = listOf(0.0 to 0.0, 5.0 to -2.0, -4.0 to 3.0, 8.0 to 6.0, -6.0 to -5.0, 2.0 to 9.0)
        val lights = ArrayList<File>()
        shifts.forEachIndexed { i, (dx, dy) ->
            val im = sky(dx = dx, dy = dy, seed = 100L + i, gradient = false, noise = 8.0)
            // apply vignetting + dark: the frames need calibration to look alike
            for (y in 0 until 260) for (x in 0 until 340) { im.p[0][y * 340 + x] = (im.p[0][y * 340 + x] * flat.p[0][y * 340 + x] / 1000f) + dark.p[0][y * 340 + x] }
            val f = File(dir, "light$i.fits"); FitsIO.write(im, f); lights.add(f)
        }
        val p = Stacker.Params(); p.dark = File(dir, "dark.fits"); p.flat = File(dir, "flat.fits"); p.tempDir = dir; p.mode = Stacker.SIGMA_CLIP
        val res = Stacker.stack(lights, p, null)
        assertEquals(6, res.used)
        val one = FitsIO.read(lights[0])
        val noiseOne = Img.stats(Ops.removeBackground(one, 8, 2).p[0], 340, 260).noise
        val out = Ops.removeBackground(res.image, 8, 2)
        val noiseStack = Img.stats(out.p[0], 340, 260).noise
        assertTrue("noise $noiseOne -> $noiseStack", noiseStack < noiseOne * 0.6)
        // the stars are aligned: the brightest star of the stack is as sharp as in a single frame
        val rep = Stars.detect(out.p[0], 340, 260, 6.0, 100)
        assertTrue(rep.count >= 8)
        assertEquals(2.3548 * sigmaStar, rep.medianFwhm, 1.2)
        assertEquals("6", res.image.header["NCOMBINE"])
    }
}
