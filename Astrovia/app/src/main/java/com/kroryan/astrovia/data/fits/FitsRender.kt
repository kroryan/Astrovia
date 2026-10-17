@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.fits

import kotlin.math.asinh
import kotlin.math.ln
import kotlin.math.sqrt

enum class Stretch(val label: String) { LINEAR("Linear"), SQRT("Square root"), LOG("Log"), ASINH("Asinh"), SQUARED("Squared") }

enum class ColorMap(val label: String) { GRAY("Grey"), INVERTED("Inverted"), HEAT("Heat"), COOL("Cool"), RAINBOW("Rainbow"), VIRIDIS("Viridis") }

/** Turns FITS pixel values into a picture: cut levels, stretch function and colour map. */
object FitsRender {
    /** Percentile cut levels (e.g. 1 and 99.5) from a sample of the finite values. */
    fun cuts(values: FloatArray, lowPct: Double = 1.0, highPct: Double = 99.5): Pair<Float, Float> {
        val step = (values.size / 20000).coerceAtLeast(1)
        val s = ArrayList<Float>(values.size / step + 1)
        var i = 0
        while (i < values.size) { val v = values[i]; if (!v.isNaN() && !v.isInfinite()) s.add(v); i += step }
        if (s.isEmpty()) return 0f to 1f
        s.sort()
        val lo = s[((s.size - 1) * lowPct / 100.0).toInt().coerceIn(0, s.size - 1)]
        val hi = s[((s.size - 1) * highPct / 100.0).toInt().coerceIn(0, s.size - 1)]
        return if (hi > lo) lo to hi else lo to lo + 1f
    }

    fun stretch(t: Float, s: Stretch): Float = when (s) {
        Stretch.LINEAR -> t
        Stretch.SQRT -> sqrt(t)
        Stretch.LOG -> (ln(1.0 + 1000.0 * t) / ln(1001.0)).toFloat()
        Stretch.ASINH -> (asinh(10.0 * t) / asinh(10.0)).toFloat()
        Stretch.SQUARED -> t * t
    }

    /**
     * ARGB picture, top row first (FITS row 1 is the bottom of the sky picture, so rows are flipped).
     * NaN pixels are transparent.
     */
    fun toArgb(img: FitsImage, lo: Float, hi: Float, s: Stretch, cm: ColorMap): IntArray {
        val w = img.width; val h = img.height
        val out = IntArray(w * h)
        val span = if (hi > lo) hi - lo else 1f
        val lut = IntArray(256) { color(it / 255f, cm) }
        for (y in 0 until h) {
            val src = (h - 1 - y) * w
            for (x in 0 until w) {
                val v = img.values[src + x]
                out[y * w + x] = if (v.isNaN()) 0 else lut[(stretch(((v - lo) / span).coerceIn(0f, 1f), s) * 255f).toInt().coerceIn(0, 255)]
            }
        }
        return out
    }

    fun color(t: Float, cm: ColorMap): Int {
        fun px(r: Float, g: Float, b: Float) = (0xFF shl 24) or ((r.coerceIn(0f, 1f) * 255).toInt() shl 16) or ((g.coerceIn(0f, 1f) * 255).toInt() shl 8) or (b.coerceIn(0f, 1f) * 255).toInt()
        return when (cm) {
            ColorMap.GRAY -> px(t, t, t)
            ColorMap.INVERTED -> px(1 - t, 1 - t, 1 - t)
            ColorMap.HEAT -> px(t * 3f, t * 3f - 1f, t * 3f - 2f)
            ColorMap.COOL -> px(t, 1 - t, 1f)
            ColorMap.RAINBOW -> { val h = (1 - t) * 4f; val c = h.toInt().coerceIn(0, 3); val f = h - c
                when (c) { 0 -> px(1f, f, 0f); 1 -> px(1 - f, 1f, 0f); 2 -> px(0f, 1f, f); else -> px(0f, 1 - f, 1f) } }
            ColorMap.VIRIDIS -> px(0.267f + t * (0.993f - 0.267f) * t, 0.005f + t * 0.9f, 0.329f + (1 - t) * 0.3f * (1 - t) + t * 0.1f)
        }
    }
}
