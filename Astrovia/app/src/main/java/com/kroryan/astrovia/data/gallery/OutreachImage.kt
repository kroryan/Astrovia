@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.gallery

import com.kroryan.astrovia.data.archive.ArchiveHit
import com.kroryan.astrovia.data.archive.Footprints

/** A public-outreach picture (Webb, Hubble, Euclid) with its tile pyramid and where it sits on the sky. */
class OutreachImage(
    val id: String, val title: String, val description: String, val credit: String, val tilesUrl: String, val largeUrl: String?,
    val raDeg: Double, val decDeg: Double, val fovDeg: Double?, val corners: List<Pair<Double, Double>>, val mission: String,
) {
    /** Small preview tile that needs no extra request: smallest Zoomify tier or a low Deep Zoom level. */
    val thumbnailUrl: String get() = if (tilesUrl.endsWith(".dzi")) tilesUrl.removeSuffix(".dzi") + "_files/8/0_0.jpg" else tilesUrl.trimEnd('/') + "/TileGroup0/0-0-0.jpg"

    val webPage: String? get() = when {
        tilesUrl.contains("esawebb.org") -> "https://esawebb.org/images/$id/"
        tilesUrl.contains("esahubble.org") -> "https://esahubble.org/images/$id/"
        else -> null
    }

    companion object {
        /**
         * The archive lists the corners as bottom-left, bottom-right, top-right, top-left (counter-clockwise on the sky chart,
         * north up and east left). The viewer needs top-left, top-right, bottom-right, bottom-left, so a counter-clockwise polygon
         * is reordered; a clockwise one is already in that order.
         */
        fun orderCorners(c: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            val ra0 = c[0].first
            val cosd = Math.cos(Math.toRadians(c.sumOf { it.second } / c.size))
            fun x(r: Double): Double { var d = (r - ra0) % 360.0; if (d > 180) d -= 360; if (d < -180) d += 360; return -d * cosd }
            var area = 0.0
            for (i in c.indices) { val a = c[i]; val b = c[(i + 1) % c.size]; area += x(a.first) * b.second - x(b.first) * a.second }
            return if (area > 0) listOf(c[3], c[2], c[1], c[0]) else c
        }

        fun fromFields(f: Map<String, String>, mission: String): OutreachImage? {
            fun get(k: String) = f.entries.firstOrNull { it.key.equals(k, true) }?.value?.takeIf { it.isNotBlank() }
            val tiles = get("tiles_url") ?: return null
            val poly = Footprints.parse(get("stc_s")).firstOrNull()
            val corners = if (poly != null && poly.ra.size >= 4) orderCorners(List(4) { poly.ra[it] to poly.dec[it] }) else emptyList()
            return OutreachImage(
                id = get("id") ?: tiles.trimEnd('/').substringAfterLast('/'), title = get("title") ?: get("id") ?: "Image", description = get("description").orEmpty(),
                credit = get("credit").orEmpty(), tilesUrl = tiles, largeUrl = get("large_url")?.takeIf { it.startsWith("http") },
                raDeg = get("ra_deg")?.toDoubleOrNull() ?: return null, decDeg = get("dec_deg")?.toDoubleOrNull() ?: return null,
                fovDeg = get("fov_size")?.toDoubleOrNull(), corners = corners, mission = mission,
            )
        }

        fun fromHit(h: ArchiveHit): OutreachImage? = fromFields(h.fields + ("ra_deg" to h.raDeg.toString()) + ("dec_deg" to h.decDeg.toString()), h.source.mission)
    }
}
