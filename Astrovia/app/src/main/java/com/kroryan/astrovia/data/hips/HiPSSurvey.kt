package com.kroryan.astrovia.data.hips

/**
 * Un survey HiPS seleccionable. `baseUrl` apunta a la raíz del árbol de tiles.
 * Los tiles se sirven en: {baseUrl}/Norder{order}/Dir{dir}/Npix{npix}.{ext}
 * donde dir = (npix / 10000) * 10000  (DIRSIZE = 10000, ver cds/tools/pixtools/Util.java).
 *
 * [frame] and [formats] come from the MocServer record (`hips_frame`, `hips_tile_format`); the
 * authoritative values are re-read from the survey's `properties` file when it is opened.
 */
data class HiPSSurvey(
    val id: String,
    val title: String,
    val baseUrl: String,
    val isColor: Boolean = true,
    val description: String = "",
    val frame: String = "equatorial",
    val formats: String = "",
    /** Folder path in the directory tree, e.g. "Image/Infrared/JWST" (from the MocServer `client_category`). */
    val category: String = "",
    /** Wavelength regime as published (Radio, Infrared, Optical, UV, X-ray, Gamma-ray...). */
    val regime: String = "",
    /** Wavelength range in metres when the server gives it (em_min, em_max). */
    val emMin: Double? = null,
    val emMax: Double? = null,
) {
    /** Normalised band used by the band filter. */
    val band: String get() = HiPSBands.of(this)

    fun tileUrl(order: Int, npix: Long, ext: String): String {
        val dir = (npix / 10000L) * 10000L
        return "$baseUrl/Norder$order/Dir$dir/Npix$npix.$ext"
    }

    fun propertiesUrl(): String = "$baseUrl/properties"
}

/**
 * Surveys shown first in the list. IDs and URLs are the ones published by the CDS MocServer
 * (checked against it: the previous list had no "CDS/" prefix and six dead URLs).
 */
object SurveyRegistry {
    private const val A = "https://alasky.cds.unistra.fr"

    val builtIn: List<HiPSSurvey> by lazy { rawBuiltIn.map { it.copy(category = if (it.frame in setOf("moon", "mars", "earth")) "Solar system/Popular" else "Popular", regime = regimeOf(it.id)) } }

    private fun regimeOf(id: String): String = when {
        "2MASS" in id || "WISE" in id || "IRIS" in id -> "Infrared"
        "GALEX" in id -> "UV"
        "Fermi" in id -> "Gamma-ray"
        else -> "Optical"
    }

    private val rawBuiltIn: List<HiPSSurvey> = listOf(
        HiPSSurvey("CDS/P/DSS2/color", "DSS2 color", "$A/DSS/DSSColor", true, "Optical survey (color)", formats = "jpeg"),
        HiPSSurvey("CDS/P/DSS2/red", "DSS2 red", "$A/DSS/DSS2Merged", false, "Optical survey (red)", formats = "jpeg fits"),
        HiPSSurvey("CDS/P/DSS2/NIR", "DSS2 NIR", "$A/DSS/DSS2-NIR", false, "Near-IR", formats = "jpeg fits"),
        HiPSSurvey("CDS/P/DSS2/blue", "DSS2 blue", "$A/DSS/DSS2-blue-XJ-S", false, "Optical (blue)", formats = "jpeg fits"),
        HiPSSurvey("CDS/P/2MASS/color", "2MASS color", "$A/2MASS/Color", true, "Near-IR all-sky", formats = "jpeg"),
        HiPSSurvey("CDS/P/allWISE/color", "AllWISE color", "$A/AllWISE/RGB-W4-W2-W1", true, "Mid-IR", formats = "jpeg"),
        HiPSSurvey("CDS/P/PanSTARRS/DR1/color-z-zg-g", "PanSTARRS DR1 color", "$A/Pan-STARRS/DR1/color-z-zg-g", true, "Optical, deeper", formats = "jpeg"),
        HiPSSurvey("CDS/P/SDSS9/color", "SDSS9 color", "$A/SDSS/DR9/color", true, "Optical (SDSS DR9)", formats = "jpeg"),
        HiPSSurvey("CDS/P/GALEXGR6/AIS/color", "GALEX color", "$A/GALEX/GR6-03-2014/AIS-Color", true, "UV", formats = "png jpeg"),
        HiPSSurvey("CDS/P/Fermi/color", "Fermi color", "$A/Fermi/Color", true, "Gamma-ray", formats = "jpeg"),
        HiPSSurvey("CDS/P/Mellinger/color", "Mellinger color optical", "$A/MellingerRGB", true, "Optical Milky Way (galactic frame)", "galactic", "jpeg"),
        HiPSSurvey("CDS/P/IRIS/color", "IRAS-IRIS color", "$A/IRISColor", true, "Far-IR dust (galactic frame)", "galactic", "jpeg"),
        HiPSSurvey("CDS/P/Moon/Clementine-750nm-118m", "Moon (Clementine 750 nm)", "$A/Planets/CDS_P_Moon_Clementine-750nm-118m", false, "Planetary map", "moon", "jpeg"),
        HiPSSurvey("CDS/P/Mars/Express286545", "Mars (Mars Express)", "$A/Planets/CDS_P_Mars_Express286545", true, "Planetary map", "mars", "jpeg"),
        HiPSSurvey("CDS/P/Earth/BlueMarble", "Earth (Blue Marble)", "$A/Planets/CDS_P_Earth_BlueMarble", true, "Planetary map", "earth", "jpeg"),
    )

    fun byId(id: String): HiPSSurvey? = builtIn.firstOrNull { it.id == id }
}

/**
 * Wavelength bands (ESASky-style: gamma, hard/soft X-ray, UV, optical, near/mid/far IR, sub-mm, radio) used by the
 * "By wavelength" organisation and the band filter of the Available data menu.
 */
object HiPSBands {
    val all = listOf("Gamma-ray", "Hard X-ray", "Soft X-ray", "UV", "Optical", "Near-IR", "Mid-IR", "Far-IR", "Infrared", "Sub-mm / mm", "Radio", "Gas lines", "Solar system", "Other")
    private val upper = listOf("Gamma-ray" to 1e-11, "Hard X-ray" to 1.2e-10, "Soft X-ray" to 1e-8, "UV" to 4e-7, "Optical" to 8e-7, "Near-IR" to 5e-6, "Mid-IR" to 3e-5, "Far-IR" to 1e-3, "Sub-mm / mm" to 1e-2, "Radio" to Double.MAX_VALUE)

    fun ofRange(minM: Double?, maxM: Double?): String? {
        if (minM == null || maxM == null || minM <= 0 || maxM < minM) return null
        val c = Math.sqrt(minM * maxM)
        return upper.first { c < it.second }.first
    }

    fun ofText(text: String?): String? {
        val t = (text ?: "").lowercase()
        return when {
            "gamma" in t -> "Gamma-ray"
            "x-ray" in t || "xray" in t || t.trim() == "x" || "/x/" in t || t.startsWith("x/") -> if ("hard" in t) "Hard X-ray" else "Soft X-ray"
            "uv" in t || "ultra" in t -> "UV"
            "optic" in t || "visible" in t -> "Optical"
            "near-ir" in t || "nir" in t -> "Near-IR"
            "mid-ir" in t -> "Mid-IR"
            "far-ir" in t || "fir" == t.trim() -> "Far-IR"
            "infra" in t || t.trim() == "ir" -> "Infrared"
            "millim" in t || "sub-mm" in t || "submm" in t -> "Sub-mm / mm"
            "radio" in t || "microwave" in t -> "Radio"
            "gas" in t || t.startsWith("hi ") || t.trim() == "hi" -> "Gas lines"
            else -> null
        }
    }

    fun of(s: HiPSSurvey): String {
        val parts = s.category.split('/').map { it.trim() }
        if (parts.firstOrNull().equals("Solar system", true)) return "Solar system"
        return ofRange(s.emMin, s.emMax) ?: ofText(s.regime) ?: ofText(parts.drop(1).take(1).joinToString(" ")) ?: "Other"
    }

    /** Band of an archive source from its descriptive band ("IR", "UV/Opt/IR", "X-ray"...). */
    fun ofArchive(band: String): String {
        val b = band.lowercase()
        return when {
            "gamma" in b -> "Gamma-ray"
            "x-ray" in b -> "Soft X-ray"
            "uv" in b -> "UV"
            "opt" in b -> "Optical"
            "fir" in b -> "Far-IR"
            "ir" in b -> "Infrared"
            "mm" in b -> "Sub-mm / mm"
            "radio" in b -> "Radio"
            else -> "Other"
        }
    }
}

/** How the Available data tree is organised. */
enum class Organization(val label: String) { DEFAULT("Default (folders)"), WAVELENGTH("By wavelength"), MISSION("By mission"), CENTRE("By data centre") }
