package com.kroryan.astrovia.sky

/**
 * Brightness / contrast / inversion applied to the sky picture, as a 4x5 colour matrix
 * (row-major, the layout of `android.graphics.ColorMatrix`). Shared by the live view and the
 * screenshot export so both look identical.
 *
 * out = sign * contrast * (v - 0.5) + 0.5 + brightness, with sign = -1 when inverted.
 */
object DisplayFilter {
    /** Null when everything is at its default value (no cost while drawing). */
    fun matrix(invert: Boolean, contrast: Float, brightness: Float): FloatArray? {
        if (!invert && contrast == 1f && brightness == 0f) return null
        val sign = if (invert) -1f else 1f
        val off = -sign * contrast * 0.5f + 0.5f + brightness
        return floatArrayOf(
            sign * contrast, 0f, 0f, 0f, off,
            0f, sign * contrast, 0f, 0f, off,
            0f, 0f, sign * contrast, 0f, off,
            0f, 0f, 0f, 1f, 0f,
        )
    }
}
