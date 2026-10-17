@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.fits

import android.graphics.Bitmap
import cds.astrovia.imgproc.Img

/** Bridges the shared processing engine ([Img]) and the viewer. */
object ProcRender {
    /** A [FitsImage] view of a processed picture (luminance for colour) so readouts, cuts and the WCS work as for a file. */
    fun imageOf(img: Img): FitsImage {
        val values = if (img.c == 1) img.p[0] else img.luma()
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (v in values) { if (v.isNaN() || v.isInfinite()) continue; if (v < mn) mn = v; if (v > mx) mx = v }
        if (mn > mx) { mn = 0f; mx = 1f }
        val header = FitsHeader(img.header.entries.map { it.key to it.value }, emptyList())
        return FitsImage(img.w, img.h, values, img.w, img.h, 1, header, Wcs.from(header), mn, mx)
    }

    /** ARGB bitmap, top row first. Colour pictures share the same cuts and stretch on the three channels. */
    fun bitmapOf(img: Img, fi: FitsImage, lo: Float, hi: Float, st: Stretch, cm: ColorMap): Bitmap {
        if (img.c == 1) {
            val argb = FitsRender.toArgb(fi, lo, hi, st, cm)
            return Bitmap.createBitmap(argb, img.w, img.h, Bitmap.Config.ARGB_8888)
        }
        val span = if (hi > lo) hi - lo else 1f
        val out = IntArray(img.w * img.h)
        for (y in 0 until img.h) {
            val src = (img.h - 1 - y) * img.w
            for (x in 0 until img.w) {
                fun ch(k: Int): Int = (FitsRender.stretch(((img.p[k][src + x] - lo) / span).coerceIn(0f, 1f), st) * 255f).toInt().coerceIn(0, 255)
                out[y * img.w + x] = (0xFF shl 24) or (ch(0) shl 16) or (ch(1) shl 8) or ch(2)
            }
        }
        return Bitmap.createBitmap(out, img.w, img.h, Bitmap.Config.ARGB_8888)
    }
}
