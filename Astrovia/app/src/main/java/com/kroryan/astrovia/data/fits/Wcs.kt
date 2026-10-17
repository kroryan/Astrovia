@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.fits

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * World coordinate system of a FITS image (FITS WCS paper I/II): linear part (CD or PC x CDELT) plus a
 * spherical projection. Zenithal projections TAN/SIN/ARC/STG/ZEA and the plate carree (CAR) are supported,
 * in equatorial or galactic axes. SIP/TPV distortions are ignored.
 */
class Wcs private constructor(
    private val proj: String,
    private val crpix1: Double, private val crpix2: Double,
    private val crval1: Double, private val crval2: Double,
    private val m: DoubleArray, // 2x2 matrix: intermediate (deg) = m * (pixel - crpix)
    private val inv: DoubleArray,
    private val galactic: Boolean,
    private val lonpole: Double,
) {
    val projection get() = proj + if (galactic) " (galactic)" else ""

    /** FITS pixel (1-based, centre of the first pixel = 1.0) to ICRS RA/Dec in degrees; null if outside the projection. */
    fun pixelToSky(px: Double, py: Double): DoubleArray? {
        val dx = px - crpix1; val dy = py - crpix2
        val x = m[0] * dx + m[1] * dy
        val y = m[2] * dx + m[3] * dy
        val lonlat: DoubleArray
        if (proj == "CAR") {
            // plate carree with the reference at the equator: native = celestial
            if (abs(crval2) > 1e-6) return null
            lonlat = doubleArrayOf(crval1 + x, crval2 + y)
        } else {
            val r = sqrt(x * x + y * y) * PI / 180.0
            val phi = atan2(x, -y)
            val theta = when (proj) {
                "TAN" -> atan2(1.0, r)
                "SIN" -> if (r > 1.0) return null else acos(r)
                "ARC" -> PI / 2 - r
                "STG" -> PI / 2 - 2 * atan(r / 2)
                "ZEA" -> if (r > 2.0) return null else PI / 2 - 2 * asin(r / 2)
                else -> return null
            }
            val dp = Math.toRadians(crval2); val phip = Math.toRadians(lonpole)
            val sd = sin(theta) * sin(dp) + cos(theta) * cos(dp) * cos(phi - phip)
            val dec = asin(sd.coerceIn(-1.0, 1.0))
            val ra = Math.toRadians(crval1) + atan2(-cos(theta) * sin(phi - phip), sin(theta) * cos(dp) - cos(theta) * sin(dp) * cos(phi - phip))
            lonlat = doubleArrayOf(Math.toDegrees(ra), Math.toDegrees(dec))
        }
        var lon = ((lonlat[0] % 360.0) + 360.0) % 360.0
        var lat = lonlat[1]
        if (galactic) { val e = galToEq(lon, lat); lon = e[0]; lat = e[1] }
        return doubleArrayOf(lon, lat)
    }

    /** ICRS RA/Dec to FITS pixel; null if the point cannot be projected. */
    fun skyToPixel(raDeg: Double, decDeg: Double): DoubleArray? {
        var lon = raDeg; var lat = decDeg
        if (galactic) { val g = eqToGal(raDeg, decDeg); lon = g[0]; lat = g[1] }
        val x: Double; val y: Double
        if (proj == "CAR") {
            if (abs(crval2) > 1e-6) return null
            var d = lon - crval1; if (d > 180) d -= 360; if (d < -180) d += 360
            x = d; y = lat - crval2
        } else {
            val a = Math.toRadians(lon); val d = Math.toRadians(lat)
            val ap = Math.toRadians(crval1); val dp = Math.toRadians(crval2); val phip = Math.toRadians(lonpole)
            val st = sin(d) * sin(dp) + cos(d) * cos(dp) * cos(a - ap)
            val theta = asin(st.coerceIn(-1.0, 1.0))
            val phi = phip + atan2(-cos(d) * sin(a - ap), sin(d) * cos(dp) - cos(d) * sin(dp) * cos(a - ap))
            val r = when (proj) {
                "TAN" -> if (theta <= 0.0) return null else 1.0 / tan(theta)
                "SIN" -> if (theta < 0.0) return null else cos(theta)
                "ARC" -> PI / 2 - theta
                "STG" -> 2 * tan((PI / 2 - theta) / 2)
                "ZEA" -> 2 * sin((PI / 2 - theta) / 2)
                else -> return null
            } * 180.0 / PI
            x = r * sin(phi); y = -r * cos(phi)
        }
        return doubleArrayOf(inv[0] * x + inv[1] * y + crpix1, inv[2] * x + inv[3] * y + crpix2)
    }

    companion object {
        /** Pixel scale in degrees per pixel, from the linear part. */
        fun scaleDeg(w: Wcs): Double = sqrt(abs(w.m[0] * w.m[3] - w.m[1] * w.m[2]))

        fun from(h: FitsHeader): Wcs? {
            val t1 = h.str("CTYPE1")?.uppercase() ?: return null
            val t2 = h.str("CTYPE2")?.uppercase() ?: return null
            val code = t1.takeLast(3).takeIf { t1.length >= 8 && t1[4] == '-' } ?: return null
            if (t2.takeLast(3) != code) return null
            val gal = t1.startsWith("GLON") && t2.startsWith("GLAT")
            val eq = t1.startsWith("RA--") && t2.startsWith("DEC-")
            if (!gal && !eq) return null
            if (code !in listOf("TAN", "SIN", "ARC", "STG", "ZEA", "CAR")) return null
            val crpix1 = h.dbl("CRPIX1") ?: return null
            val crpix2 = h.dbl("CRPIX2") ?: return null
            val crval1 = h.dbl("CRVAL1") ?: return null
            val crval2 = h.dbl("CRVAL2") ?: return null
            val m = DoubleArray(4)
            if (h.has("CD1_1") || h.has("CD2_2") || h.has("CD1_2")) {
                m[0] = h.dbl("CD1_1") ?: 0.0; m[1] = h.dbl("CD1_2") ?: 0.0; m[2] = h.dbl("CD2_1") ?: 0.0; m[3] = h.dbl("CD2_2") ?: 0.0
            } else {
                val c1 = h.dbl("CDELT1") ?: 1.0; val c2 = h.dbl("CDELT2") ?: 1.0
                var pc11 = h.dbl("PC1_1") ?: 1.0; var pc12 = h.dbl("PC1_2") ?: 0.0
                var pc21 = h.dbl("PC2_1") ?: 0.0; var pc22 = h.dbl("PC2_2") ?: 1.0
                if (!h.has("PC1_1") && h.has("CROTA2")) {
                    val r = Math.toRadians(h.dbl("CROTA2") ?: 0.0)
                    pc11 = cos(r); pc12 = -sin(r) * c2 / c1; pc21 = sin(r) * c1 / c2; pc22 = cos(r)
                }
                m[0] = c1 * pc11; m[1] = c1 * pc12; m[2] = c2 * pc21; m[3] = c2 * pc22
            }
            val det = m[0] * m[3] - m[1] * m[2]
            if (abs(det) < 1e-30) return null
            val inv = doubleArrayOf(m[3] / det, -m[1] / det, -m[2] / det, m[0] / det)
            return Wcs(code, crpix1, crpix2, crval1, crval2, m, inv, gal, h.dbl("LONPOLE") ?: 180.0)
        }

        // J2000 galactic <-> equatorial (rotation matrix of the IAU 1958 galactic system)
        private val R = arrayOf(
            doubleArrayOf(-0.0548755604162154, -0.8734370902348850, -0.4838350155487132),
            doubleArrayOf(0.4941094278755837, -0.4448296299600112, 0.7469822444972189),
            doubleArrayOf(-0.8676661490190047, -0.1980763734312015, 0.4559837761750669),
        )

        fun eqToGal(ra: Double, dec: Double): DoubleArray {
            val v = vec(ra, dec)
            val g = DoubleArray(3) { i -> R[i][0] * v[0] + R[i][1] * v[1] + R[i][2] * v[2] }
            return lonlat(g)
        }

        fun galToEq(l: Double, b: Double): DoubleArray {
            val v = vec(l, b)
            val e = DoubleArray(3) { i -> R[0][i] * v[0] + R[1][i] * v[1] + R[2][i] * v[2] }
            return lonlat(e)
        }

        private fun vec(lon: Double, lat: Double): DoubleArray {
            val a = Math.toRadians(lon); val d = Math.toRadians(lat)
            return doubleArrayOf(cos(d) * cos(a), cos(d) * sin(a), sin(d))
        }

        private fun lonlat(v: DoubleArray): DoubleArray {
            val lon = (Math.toDegrees(atan2(v[1], v[0])) + 360.0) % 360.0
            return doubleArrayOf(lon, Math.toDegrees(asin(v[2].coerceIn(-1.0, 1.0))))
        }
    }
}
