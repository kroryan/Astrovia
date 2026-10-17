@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.data.gallery

/** Tile pyramids of very large pictures (Zoomify and Deep Zoom): the geometry lives in the shared Java code, also used by the desktop viewer. */
typealias TilePyramid = cds.astrovia.sky.TilePyramid

/** Opens the right pyramid for the `tiles_url` of an outreach image (".dzi" file or a Zoomify folder). */
object TilePyramids {
    suspend fun open(tilesUrl: String, fetch: suspend (String) -> String?): TilePyramid? =
        if (tilesUrl.endsWith(".dzi")) fetch(tilesUrl)?.let { TilePyramid.deepZoom(tilesUrl, it) }
        else fetch(tilesUrl.trimEnd('/') + "/ImageProperties.xml")?.let { TilePyramid.zoomify(tilesUrl, it) }
}
