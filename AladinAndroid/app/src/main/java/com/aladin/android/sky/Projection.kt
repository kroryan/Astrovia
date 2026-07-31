package com.aladin.android.sky

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Proyección zenital de área equivalente (ZEA / Lambert azimuthal), la misma
 * que usa Aladin desktop para la mayoría del rango de zoom (ver
 * `Projection.getDefaultType` en el código de escritorio: TAN solo por debajo
 * de ~4° de FoV, ZEA hasta ~120°, AIT más allá). Cerca del centro (ángulos
 * pequeños) ZEA y TAN coinciden casi exactamente, así que usar ZEA en todo el
 * rango no cambia nada a poco zoom y evita el problema de TAN a zoom lejano:
 * la gnomónica diverge cerca de su horizonte (90° del centro) y da la
 * sensación de estar "dentro de una bola" que hay que rotar; ZEA se mantiene
 * bien comportada (radio acotado) hasta casi el antípoda, así que al alejar
 * el zoom se ve progresivamente más cielo real en vez de un horizonte que se
 * estira sin límite.
 *
 * El centro de proyección es (ra0, dec0) en grados, con un ángulo de rotación
 * (posición) para orientar el cielo (por defecto norte arriba, rot = 0).
 * `radPerPixel` = unidades de proyección por píxel (definido por el FoV
 * vertical y el alto).
 */
data class Projection(
    val ra0: Double,
    val dec0: Double,
    val rotationDeg: Double = 0.0,
    val radPerPixel: Double,
    val widthPx: Float,
    val heightPx: Float,
) {
    val cxPx get() = widthPx / 2f
    val cyPx get() = heightPx / 2f
    private val rot = Math.toRadians(rotationDeg)
    private val cosRot = cos(rot)
    private val sinRot = sin(rot)
    private val ra0Rad = Math.toRadians(ra0)
    private val dec0Rad = Math.toRadians(dec0)
    private val sinDec0 = sin(dec0Rad)
    private val cosDec0 = cos(dec0Rad)

    /** (raDeg, decDeg) -> (screenX, screenY) o null si está en el punto antípoda (indefinido). */
    fun forward(raDeg: Double, decDeg: Double): FloatArray? {
        val dec = Math.toRadians(decDeg)
        var dra = Math.toRadians(raDeg) - ra0Rad
        if (dra > PI) dra -= 2 * PI
        else if (dra < -PI) dra += 2 * PI
        val cosDec = cos(dec)
        val x = cosDec * sin(dra)
        val y = sin(dec) * cosDec0 - cosDec * sinDec0 * cos(dra)
        val z = sin(dec) * sinDec0 + cosDec * cosDec0 * cos(dra)
        // ZEA (zenital de área equivalente): w = sqrt((1+z)/2), acotado y sin
        // divergencia salvo en el antípoda exacto (z=-1, w=0). A diferencia de
        // TAN (xi=x/z), aquí NO se descarta el hemisferio lejano: se puede
        // proyectar (case casi) todo el cielo, como hace Aladin desktop.
        val w = sqrt((1.0 + z) / 2.0)
        if (w < 1e-9) return null // antípoda exacto: indefinido
        val xi = x / w
        val eta = y / w
        val xr = xi * cosRot - eta * sinRot
        val yr = xi * sinRot + eta * cosRot
        val sx = cxPx + (xr / radPerPixel).toFloat()
        val sy = cyPx - (yr / radPerPixel).toFloat()
        return floatArrayOf(sx, sy)
    }

    /** (screenX, screenY) -> (raDeg, decDeg). */
    fun inverse(sx: Float, sy: Float): DoubleArray {
        val xr = (sx - cxPx) * radPerPixel.toFloat()
        val yr = -(sy - cyPx) * radPerPixel.toFloat()
        val xi = xr * cosRot + yr * sinRot
        val eta = -xr * sinRot + yr * cosRot
        val rho = sqrt(xi * xi + eta * eta)
        // ZEA: rho = 2*sin(c/2) -> c = 2*asin(rho/2). rho puede llegar hasta 2
        // (antípoda); coerceIn evita NaN por redondeo cuando rho roza ese límite.
        val c = 2.0 * kotlin.math.asin((rho / 2.0).coerceIn(-1.0, 1.0))
        val sinC = sin(c)
        val cosC = cos(c)
        val dec: Double
        val dra: Double
        if (rho < 1e-12) {
            dec = Math.toDegrees(dec0Rad)
            dra = 0.0
        } else {
            dec = Math.toDegrees(
                kotlin.math.asin((cosC * sinDec0 + eta * sinC * cosDec0 / rho).coerceIn(-1.0, 1.0))
            )
            dra = Math.toDegrees(atan2(xi * sinC, rho * cosDec0 * cosC - eta * sinDec0 * sinC))
        }
        var ra = (Math.toDegrees(ra0Rad) + dra + 360.0) % 360.0
        if (ra < 0) ra += 360.0
        return doubleArrayOf(ra, dec)
    }

    companion object {
        /**
         * Unidades de proyección (rho) por píxel para un FoV vertical dado.
         * En ZEA, rho = 2*sin(theta/2) donde theta es la distancia angular al
         * centro (ver `inverse`: c = 2*asin(rho/2) es exactamente esa distancia).
         * En el borde vertical de pantalla theta = fovDeg/2, así que el radio de
         * proyección ahí es rho_edge = 2*sin(fovDeg/4). (Antes usábamos
         * tan(fovRad/2), la fórmula de TAN/gnomónica; con ZEA el borde nunca se va
         * a infinito, lo que es justo lo que permite alejar el zoom sin artefactos.)
         */
        fun radPerPixelFromFov(fovDeg: Double, heightPx: Float): Double {
            val fovRad = Math.toRadians(fovDeg.coerceIn(0.0001, 179.99))
            val half = 2.0 * sin(fovRad / 4.0)
            return half / (heightPx / 2.0)
        }
    }
}