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
    /** The file on disk (needed to process it) and the processing session, if started. */
    val file: java.io.File? = null,
    val proc: ProcSession? = null,
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


/** Processing history of an open picture: every step keeps its result so it can be undone. */
class ProcSession(
    val history: List<cds.astrovia.imgproc.Img>,
    val index: Int,
    val log: List<String>,
    val info: String? = null,
    val stars: List<cds.astrovia.imgproc.Stars.Star> = emptyList(),
    val showStars: Boolean = true,
) {
    val current: cds.astrovia.imgproc.Img get() = history[index]
    val canUndo get() = index > 0
    val canRedo get() = index < history.size - 1
    val steps get() = log.take(index)
}
