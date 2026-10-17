package com.kroryan.astrovia.model

import android.graphics.Bitmap
import com.kroryan.astrovia.data.fits.ColorMap
import com.kroryan.astrovia.data.fits.FitsFile
import com.kroryan.astrovia.data.fits.FitsHdu
import com.kroryan.astrovia.data.fits.FitsImage
import com.kroryan.astrovia.data.fits.Stretch
import com.kroryan.astrovia.data.fits.Wcs

/** A FITS file open in the viewer, with the image HDU being shown and how it is displayed. */
class FitsDocument(
    val name: String,
    val fits: FitsFile,
    val hdu: FitsHdu?,
    val image: FitsImage?,
    val bitmap: Bitmap?,
    val stretch: Stretch = Stretch.ASINH,
    val colorMap: ColorMap = ColorMap.GRAY,
    val lo: Float = 0f,
    val hi: Float = 1f,
    val note: String? = null,
)

/** A FITS image drawn over the sky at its true position. */
class FitsOverlay(
    val bitmap: Bitmap,
    val wcs: Wcs,
    val origWidth: Int,
    val origHeight: Int,
    val step: Int,
    val alpha: Float = 0.85f,
)

/** A picture with four known sky corners (top-left, top-right, bottom-right, bottom-left) drawn over the sky. */
class QuadOverlay(val bitmap: Bitmap, val raCorners: DoubleArray, val decCorners: DoubleArray, val alpha: Float = 0.9f)
