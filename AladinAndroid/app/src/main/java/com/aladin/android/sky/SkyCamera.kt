package com.aladin.android.sky

import com.aladin.android.model.SkyPosition

/**
 * Estado de la cámara del cielo: centro (ra,dec), FoV vertical y rotación.
 * Inmutable; las operaciones devuelven un nuevo estado.
 */
data class SkyCamera(
    val center: SkyPosition = SkyPosition(0.0, 0.0),
    val fovDeg: Double = 60.0,
    val rotationDeg: Double = 0.0,
) {
    init {
        require(fovDeg > 0.0)
    }

    fun projection(widthPx: Float, heightPx: Float): Projection = Projection(
        ra0 = center.raDeg,
        dec0 = center.decDeg,
        rotationDeg = rotationDeg,
        radPerPixel = Projection.radPerPixelFromFov(fovDeg, heightPx),
        widthPx = widthPx,
        heightPx = heightPx,
    )

    fun panBy(deltaPxX: Float, deltaPxY: Float, widthPx: Float, heightPx: Float): SkyCamera {
        val proj = projection(widthPx, heightPx)
        // Mover el centro en dirección opuesta al arrastre.
        val newCenter = proj.inverse(widthPx / 2f - deltaPxX, heightPx / 2f - deltaPxY)
        return copy(center = SkyPosition(newCenter[0], newCenter[1]))
    }

    fun zoom(factor: Float, widthPx: Float, heightPx: Float, focusX: Float, focusY: Float): SkyCamera {
        val newFov = (fovDeg / factor).coerceIn(MIN_FOV, MAX_FOV)
        if (newFov == fovDeg) return this
        // Zoom estándar (tipo fotos): el punto del cielo que está bajo el foco del
        // pinza debe quedarse bajo el foco tras hacer zoom.
        val oldProj = projection(widthPx, heightPx)
        val anchor = oldProj.inverse(focusX, focusY) // punto del cielo bajo el foco
        // Proyección con el nuevo FoV pero aún centrada en el centro antiguo: nos
        // dice dónde cae ahora el anchor en pantalla.
        val newProj = Projection(
            ra0 = center.raDeg, dec0 = center.decDeg, rotationDeg = rotationDeg,
            radPerPixel = Projection.radPerPixelFromFov(newFov, heightPx),
            widthPx = widthPx, heightPx = heightPx,
        )
        val anchorScreen = newProj.forward(anchor[0], anchor[1])
            ?: return copy(fovDeg = newFov)
        // Desplazar el centro de pantalla para que el anchor vuelva al foco.
        // Mover el centro en pantalla por D desplaza el contenido por -D, así que
        // para llevar el anchor de anchorScreen a focus: newCenterScreen = center + (anchorScreen - focus).
        val newCenterScreenX = widthPx / 2f + (anchorScreen[0] - focusX)
        val newCenterScreenY = heightPx / 2f + (anchorScreen[1] - focusY)
        val newCenter = newProj.inverse(newCenterScreenX, newCenterScreenY)
        return copy(center = SkyPosition(newCenter[0], newCenter[1]), fovDeg = newFov)
    }

    fun recenterTo(pos: SkyPosition): SkyCamera = copy(center = pos)

    companion object {
        const val MIN_FOV = 0.005 // ~18 arcsec
        // Con la proyección ZEA (ver Projection.kt) el radio de proyección está
        // acotado (w = sqrt((1+z)/2) nunca llega a 0 salvo en el antípoda exacto),
        // así que a diferencia de la gnomónica podemos alejar el zoom mucho más
        // sin que aparezcan paralelogramos gigantes cruzando la pantalla. Aladin
        // desktop permite hasta ~180° con ZEA (ver Projection.getDefaultType);
        // dejamos un pequeño margen bajo 180° para que el punto antípoda (donde
        // la proyección es indefinida) quede siempre fuera de la pantalla.
        const val MAX_FOV = 175.0
    }
}