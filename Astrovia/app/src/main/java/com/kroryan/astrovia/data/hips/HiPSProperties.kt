package com.kroryan.astrovia.data.hips

import com.kroryan.astrovia.sky.SkyFrame

/**
 * Cabecera de propiedades de un survey HiPS (fichero `properties`).
 * Solo se conservan los campos que usa el render. Ver cds/allsky/Constante.java.
 */
data class HiPSProperties(
    val baseUrl: String,
    val title: String = "",
    val order: Int = 0,
    /** Per the HiPS standard the default is 0 (it used to be 3, which forced 700+ base tiles). */
    val orderMin: Int = 0,
    val tileWidth: Int = 512,
    /** Format chosen from the ones the survey offers: jpeg > png > webp > fits. */
    val tileFormat: String = "jpg",
    /** Raw `hips_frame` value (equatorial, galactic, mars...). */
    val frame: String = "equatorial",
    val initialRa: Double = 0.0,
    val initialDec: Double = 0.0,
    val initialFov: Double = 60.0,
    /** `hips_pixel_cut` (used to stretch FITS tiles to an 8-bit picture); null if not declared. */
    val pixelCutMin: Double? = null,
    val pixelCutMax: Double? = null,
    /** `moc_sky_fraction`: share of the sky the survey covers (1.0 = all sky or unknown). */
    val skyFraction: Double = 1.0,
) {
    val ext: String get() = when (tileFormat.lowercase()) {
        "png" -> "png"
        "fits" -> "fits"
        "webp" -> "webp"
        else -> "jpg"
    }

    val skyFrame: SkyFrame get() = SkyFrame.parse(frame)

    /** Initial centre in ICRS (the survey publishes it in its own frame). */
    val initialIcrs: DoubleArray get() = skyFrame.toIcrsRaDec(initialRa, initialDec)
}

object HiPSPropertiesParser {
    /** Prefer formats the phone can decode natively; FITS is last because it needs a stretch. */
    private val FORMAT_PRIORITY = listOf("jpeg", "jpg", "png", "webp", "fits")

    /** Picks the best tile format out of a `hips_tile_format` list such as "fits png jpeg". */
    fun pickFormat(list: String?): String {
        val offered = (list ?: "jpeg").lowercase().split(',', ';', ' ').filter { it.isNotBlank() }
        val best = FORMAT_PRIORITY.firstOrNull { it in offered } ?: offered.firstOrNull() ?: "jpeg"
        return if (best == "jpeg") "jpg" else best
    }

    /** Parsea un texto `key = value` (uno por línea) del fichero properties. */
    fun parse(baseUrl: String, text: String): HiPSProperties {
        val map = HashMap<String, String>()
        text.lineSequence().forEach { line ->
            val i = line.indexOf('=')
            if (i > 0) {
                val k = line.substring(0, i).trim()
                val v = line.substring(i + 1).trim()
                if (k.isNotEmpty()) map[k] = v
            }
        }
        val fmt = pickFormat(map["hips_tile_format"] ?: map["format"])
        val cut = (map["hips_pixel_cut"] ?: "").split(' ', ',').filter { it.isNotBlank() }.mapNotNull { it.toDoubleOrNull() }
        return HiPSProperties(
            baseUrl = baseUrl,
            title = map["obs_title"] ?: map["title"] ?: "",
            order = (map["hips_order"] ?: map["maxOrder"] ?: "0").toIntOrNull() ?: 0,
            orderMin = (map["hips_order_min"] ?: map["minOrder"] ?: "0").toIntOrNull() ?: 0,
            tileWidth = (map["hips_tile_width"] ?: "512").toIntOrNull() ?: 512,
            tileFormat = fmt,
            frame = (map["hips_frame"] ?: map["coordsys"] ?: "equatorial"),
            initialRa = (map["hips_initial_ra"] ?: "0").toDoubleOrNull() ?: 0.0,
            initialDec = (map["hips_initial_dec"] ?: "0").toDoubleOrNull() ?: 0.0,
            initialFov = (map["hips_initial_fov"] ?: "60").toDoubleOrNull() ?: 60.0,
            pixelCutMin = cut.getOrNull(0),
            pixelCutMax = cut.getOrNull(1),
            skyFraction = (map["moc_sky_fraction"] ?: "1").toDoubleOrNull()?.takeIf { it in 0.0..1.0 } ?: 1.0,
        )
    }
}
