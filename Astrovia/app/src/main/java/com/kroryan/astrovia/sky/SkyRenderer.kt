@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.sky

import com.kroryan.astrovia.data.hips.HiPSProperties
import kotlin.math.hypot

/**
 * Visible tile. We keep the corners and center in celestial coordinates
 * (ra/dec degrees), NOT in screen pixels: this way draw projects them with the
 * LIVE camera and pan/zoom moves the tiles smoothly without waiting to
 * recalculate the set of visible tiles.
 *
 * `scalePx` is a representative value (screen px per native tile px)
 * calculated at compute time, only to decide the `inSampleSize`.
 */
data class VisibleTile(
    val order: Int,
    val npix: Long,
    val cornersRadec: Array<DoubleArray>,
    val centerRadec: DoubleArray,
    val scalePx: Float,
    /** True if this tile stopped subdividing here: it's the correct resolution for its
     * on-screen size, not just an intermediate progressive-refinement base layer. */
    val isLeaf: Boolean = true,
    /** Frame the tile's HEALPix grid is published in (corners/centre are already in ICRS). */
    val frame: SkyFrame = SkyFrame.ICRS,
)

/**
 * A tile as a triangle mesh: a (n+1)x(n+1) grid of vertices shared by all its triangles, so a tile has no
 * cracks inside, and tiles of the same order share identical vertices along common edges (the grid size
 * depends only on the order and zoom, never on the individual tile).
 *
 * [positions] are screen px (x, y pairs), [texCoords] are bitmap px (x, y pairs), [indices] triangles.
 */
class TileMesh(val positions: FloatArray, val texCoords: FloatArray, val indices: ShortArray)

/**
 * Computes which HiPS tiles to draw for a given projection, by recursing
 * the NESTED tree (the children of (order,npix) are 4*npix+{0,1,2,3} at order+1).
 * Only needs HEALPix forward (center and corners).
 */
object SkyRenderer {

    /**
     * Culling margin (px) beyond the screen: preloads the immediate edge
     * so panning doesn't show black.
     */
    private const val CULL_MARGIN_PX = 512f

    /**
     * Default scale threshold to consider a tile "enough detail" and
     * stop subdividing. `scalePx` = screen px per native tile px
     * (projected). We subdivide only while the tile is UPSCALED beyond
     * its native resolution (scalePx > targetScale); beyond that, subdividing
     * adds no real detail (more tiles for the SAME native image) and only spikes
     * the tile count -> endless loading.
     *
     * This mirrors `maxOrder(v)` from Aladin desktop, which picks the order where
     * ~1 tile px ≈ 1 screen px (with slight oversampling for sharpness).
     * We used to do `bboxSpan <= 280`, but the HiPS tile is a rhombus and its
     * axis-aligned bbox is ~1.4x the side, so it subdivided one order too many: at
     * moderate FoV (e.g. 2°) it spawned hundreds of ~60px tiles (452!) and
     * loading at 3s/tile became eternal. With scalePx the count stays at
     * ~8-30 tiles per screen across the whole zoom range.
     *
     * The actual value is provided by the `targetScale` param in computeVisibleTiles,
     * adjustable from Settings (TileQuality).
     */
    private const val DEFAULT_TARGET_SCALE = 1.5f

    /** Hard limit of visible tiles: avoids pathological avalanches (poles / extreme FoV). */
    private const val MAX_TILES = 600

    fun computeVisibleTiles(
        proj: Projection,
        props: HiPSProperties,
        targetScale: Float = DEFAULT_TARGET_SCALE,
        maxTiles: Int = MAX_TILES,
    ): List<VisibleTile> {
        val frame = props.skyFrame
        val startOrder = props.orderMin.coerceIn(0, props.order.coerceAtLeast(props.orderMin))
        val maxOrder = props.order
        val tileW = props.tileWidth.toFloat()

        // BFS by levels (order), NOT DFS. We used to traverse the NESTED tree
        // depth-first for each base tile (12 at order 0): a single branch could
        // go down to order 9 and exhaust the MAX_TILES budget on its own. At normal
        // FoV (~2°) there is only 1 base tile on screen, so it was never noticed.
        // But when zooming out completely there are up to 12 base tiles visible at
        // once, and with DFS the last ones in iteration order (0 until npix)
        // were left WITHOUT ANY tile -not even the coarse one- because the first
        // branch had consumed the quota: hence the "it goes totally black"
        // when zooming out a lot (those areas weren't even requested). With BFS
        // we ALWAYS complete a full level (everything visible at that order)
        // before subdividing to the next, so a wide FoV simply stays at a coarser
        // order showing the COMPLETE sky instead of black parts. This is also
        // what Aladin desktop does (PlanBG.drawLosangesAsync iterates order by order, cmin..max).
        val visible = VisibleCone.of(proj)
        var level = ArrayList<Long>(Healpix.npix(startOrder).toInt().coerceAtMost(12))
        for (ipix in 0 until Healpix.npix(startOrder)) level.add(ipix)

        val out = ArrayList<VisibleTile>()
        var order = startOrder
        while (level.isNotEmpty()) {
            val survivors = ArrayList<Long>()
            for (ipix in level) {
                val centerRadec = Healpix.center(order, ipix, frame)
                // Angular prefilter: a tile farther from the view centre than the screen reaches (plus the
                // tile's own radius) cannot be visible. Needed with the all-sky oval, where every point of
                // the sphere has a screen position, so "behind the view" can no longer be told by the
                // projection returning null.
                if (!visible.mayContain(centerRadec[0], centerRadec[1], tileRadiusRad(order))) continue
                val corners = Healpix.corners(order, ipix, frame)
                val pts = ArrayList<FloatArray>(4)
                for (c in corners) proj.forward(c[0], c[1])?.let { pts.add(it) }
                val centerPx = proj.forward(centerRadec[0], centerRadec[1])

                // A tile with NO corner and no center passing the strict cutoff is
                // genuinely fully on the far side and must be excluded outright.
                // Using forwardClamped for those would still produce a (heavily
                // distorted) on-screen position, pulling completely unrelated,
                // wrong sky onto the view -- a second "layer" of stars overlapping
                // the real one.
                if (pts.isEmpty() && centerPx == null) continue

                // For the tiles that DO have some part in the visible hemisphere,
                // build the culling bbox from all 4 corners, substituting
                // forwardClamped only for the ones that individually failed the
                // strict cutoff. A coarse/large tile can have its center (and even
                // most corners) fail while a real edge is still on screen; bbox-ing
                // from `pts` alone (only the successful ones, possibly zero) used
                // to drop those tiles entirely -- a whole-tile-shaped black gap at
                // wide FoV, distinct from (and not fixed by) placementMesh's
                // clamping of the draw mesh itself.
                val boundsCorners = corners.map { c -> proj.forward(c[0], c[1]) ?: proj.forwardClamped(c[0], c[1]) }
                val margin = CULL_MARGIN_PX
                val bbox = bbox(boundsCorners)
                val intersects = bbox != null &&
                    bbox[2] >= -margin && bbox[0] <= proj.widthPx + margin &&
                    bbox[3] >= -margin && bbox[1] <= proj.heightPx + margin
                if (!intersects) continue

                // Representative scale (screen px per native tile px).
                //
                // The projection is EQUAL-AREA (ZEA: area element rho d(rho) d(phi) = dOmega), so a
                // HEALPix tile of a given order covers the same number of screen pixels wherever it
                // is on screen. Its scale is therefore a function of the order alone, which gives a
                // uniform level of detail across the screen by construction. (It used to be measured
                // on ONE tile edge, which the projection distorts by up to 2.5x depending on the
                // tile's orientation: neighbouring tiles picked different orders and zoomed-out views
                // came out as a patchwork of resolutions.)
                val scalePx = tileScale(order, proj.radPerPixel, tileW)

                // Leaf when the tile is at native resolution or finer (scalePx <= targetScale).
                // Previously, tiles straddling the horizon (pts.size < 4) were forced to keep
                // subdividing all the way down to maxOrder to mask distortion caused by a bug
                // in forwardClamped. That endless horizon subdivision quickly exceeded MAX_TILES,
                // aborting the level-by-level BFS mid-way and causing cmin (branchMax - 3) to
                // discard coarse base layers, leaving solid-black holes across the sky. With
                // forwardClamped projecting correctly to the perimeter, horizon tiles render
                // smoothly at their proper resolution without forced subdivision.
                val enoughDetail = scalePx <= targetScale
                val willSubdivide = order < maxOrder && !enoughDetail
                val isLeaf = !willSubdivide

                // We emit ALL on-screen nodes (not just leaves): draw uses the
                // coarse ancestor as a base for progressive refinement.
                out.add(VisibleTile(order, ipix, corners, centerRadec, scalePx, isLeaf, frame))

                if (willSubdivide) {
                    survivors.add(ipix)
                }
            }
            // We only check the budget AFTER completing the whole level
            // (never halfway): this way a coarse level is always complete, without
            // gaps, even if it slightly exceeds MAX_TILES (e.g. order 3 = 768
            // base tiles on the whole sphere, > 600, but still allowed).
            if (survivors.isEmpty() || out.size >= maxTiles) break
            val nextLevel = ArrayList<Long>(survivors.size * 4)
            for (p in survivors) {
                nextLevel.add(4 * p); nextLevel.add(4 * p + 1)
                nextLevel.add(4 * p + 2); nextLevel.add(4 * p + 3)
            }
            level = nextLevel
            order++
        }

        // Progressive refinement (identical to Aladin desktop —see
        // PlanBG.drawLosangesAsync, which draws ready tiles from every order
        // cmin..max, coarse first as base and fine on top). We keep only
        // the top ~4 levels of EACH BRANCH (base cell from startOrder),
        // NOT a global cmin.
        //
        // BUG this fixes: with a global cmin (maxObs of ALL out, cmin =
        // maxObs-3 applied to ALL tiles), at a wide FoV different branches
        // (different base cells visible at once) reach very different depths
        // -one near the center might reach order 8 while another, more
        // deformed/smaller on screen, already has "enoughDetail" at order 2-.
        // With global cmin = 8-3 = 5, the branch that stopped at order 2 loses ALL
        // its tiles (2 < 5): that screen region gets no tiles at all, not even
        // the coarse one -> total black. That is exactly what happened when zooming
        // out completely. By computing cmin PER BRANCH, each base cell keeps its
        // own top ~4 levels relative to ITS OWN max depth, so every visible branch
        // always has at least one tile.
        if (out.isEmpty()) return out
        val floorOrder = props.orderMin.coerceAtLeast(0)
        val branchMaxOrder = HashMap<Long, Int>()
        for (t in out) {
            val shift = 2 * (t.order - startOrder)
            val branch = if (shift >= 0) t.npix shr shift else t.npix
            val cur = branchMaxOrder[branch]
            if (cur == null || t.order > cur) branchMaxOrder[branch] = t.order
        }
        // Keep the top ~4 levels of each branch as progressive-refinement layers
        // (coarse base -> fine top). BUT a leaf -- a tile that stopped subdividing
        // because it already had enough on-screen detail (scalePx <= targetScale,
        // all 4 corners project) -- is the *finest* content layer for ITS specific
        // sub-area, and it has no descendants in `out` (it never subdivided). If a
        // sibling sub-area of the same branch reached a much higher order, cmin =
        // branchMax-3 would drop this leaf even though nothing finer covers its
        // area -> that patch renders solid black with the bitmap already downloaded.
        // So leaves are always kept, independent of cmin; cmin only prunes redundant
        // coarse *non-leaf* ancestors below the kept window.
        val filtered = ArrayList<VisibleTile>(out.size)
        for (t in out) {
            val shift = 2 * (t.order - startOrder)
            val branch = if (shift >= 0) t.npix shr shift else t.npix
            val branchMax = branchMaxOrder[branch] ?: t.order
            val cmin = (branchMax - 3).coerceAtLeast(floorOrder)
            if (t.isLeaf || t.order >= cmin) filtered.add(t)
        }
        // Ascending order: coarse (low order) first = base; fine (high order)
        // after = drawn on top. Without this, a coarse tile drawn after a fine
        // one would cover it.
        filtered.sortBy { it.order }
        return filtered
    }

    /** Grid size (cells per side) for tiles of [order]: about 40 px per cell, so a cell is almost flat. */
    internal fun meshCells(order: Int, radPerPixel: Double, minCells: Int = 1): Int {
        val sidePx = HEALPIX_SIDE_RAD / (1L shl order).toDouble() / radPerPixel
        return maxOf(minCells, kotlin.math.ceil(sidePx / CELL_PX).toInt().coerceIn(MIN_CELLS, MAX_CELLS))
    }

    /**
     * Triangle mesh of the tile for placement on screen.
     *
     * Bitmap orientation (HiPS convention, identical to Aladin desktop, see CDSHealpix.corners() which
     * returns [S,E,W,N] and HealpixKey.drawTriangle which maps TL=S, TR=W, BL=E, BR=N): the bitmap X axis
     * runs along +fy (corner S->W) and the Y axis along +fx (corner S->E). The grid is indexed
     * [fy_i][fx_j]; if the axes were swapped each tile would be rotated 90 deg.
     *
     * Vertices use [Projection.forwardClamped] (never null), so a tile straddling the edge of the ZEA
     * disc still gets a mesh. Triangles with an edge far longer than a cell are dropped: they are the
     * ones that cross the seam of the oval (opposite side of the sky) or the limb of the disc.
     */
    fun placementTriangles(
        proj: Projection,
        tile: VisibleTile,
        bmpW: Int,
        bmpH: Int,
        minCells: Int = 1,
    ): TileMesh? {
        val n = meshCells(tile.order, proj.radPerPixel, minCells)
        val positions = FloatArray((n + 1) * (n + 1) * 2)
        val tex = FloatArray(positions.size)
        for (i in 0..n) {
            for (j in 0..n) {
                val ra = Healpix.radecAt(tile.order, tile.npix, j.toDouble() / n, i.toDouble() / n, tile.frame)
                val p = proj.forwardClamped(ra[0], ra[1])
                val v = (i * (n + 1) + j) * 2
                positions[v] = p[0]
                positions[v + 1] = p[1]
                // Integer cell boundaries from the full bitmap size, so the n cells cover the WHOLE tile.
                tex[v] = (bmpW * i / n).toFloat()
                tex[v + 1] = (bmpH * j / n).toFloat()
            }
        }
        val sidePx = HEALPIX_SIDE_RAD / (1L shl tile.order).toDouble() / proj.radPerPixel
        // A normal cell edge is ~sidePx/n; distortion (oval border, ZEA limb) stretches it a few times.
        val maxEdge = (6.0 * sidePx / n + 24.0).toFloat()
        val margin = 600f
        val idx = ArrayList<Short>(n * n * 6)
        fun ok(a: Int, b: Int, c: Int): Boolean {
            val ax = positions[a * 2]; val ay = positions[a * 2 + 1]
            val bx = positions[b * 2]; val by = positions[b * 2 + 1]
            val cx = positions[c * 2]; val cy = positions[c * 2 + 1]
            if (hypot(bx - ax, by - ay) > maxEdge || hypot(cx - ax, cy - ay) > maxEdge || hypot(cx - bx, cy - by) > maxEdge) return false
            // skip triangles entirely off screen (cheap and keeps the draw call small when zoomed in)
            return !(maxOf(ax, bx, cx) < -margin || minOf(ax, bx, cx) > proj.widthPx + margin ||
                maxOf(ay, by, cy) < -margin || minOf(ay, by, cy) > proj.heightPx + margin)
        }
        for (i in 0 until n) {
            for (j in 0 until n) {
                val v00 = i * (n + 1) + j
                val v10 = (i + 1) * (n + 1) + j
                val v11 = (i + 1) * (n + 1) + j + 1
                val v01 = i * (n + 1) + j + 1
                if (ok(v00, v10, v11)) { idx.add(v00.toShort()); idx.add(v10.toShort()); idx.add(v11.toShort()) }
                if (ok(v00, v11, v01)) { idx.add(v00.toShort()); idx.add(v11.toShort()); idx.add(v01.toShort()) }
            }
        }
        if (idx.isEmpty()) return null
        return TileMesh(positions, tex, idx.toShortArray())
    }

    private const val CELL_PX = 40.0
    private const val MAX_CELLS = 24
    private const val MIN_CELLS = 6 // smooth borders of the oval and the disc when zoomed out

    /** Upper bound of the angular radius of a HEALPix tile of [order] (centre to farthest corner), radians. */
    internal fun tileRadiusRad(order: Int): Double = 1.15 * HEALPIX_SIDE_RAD / (1L shl order).toDouble()

    /** Side of a HEALPix pixel in radians at nside = 1: sqrt(4*pi / 12). */
    internal val HEALPIX_SIDE_RAD = kotlin.math.sqrt(Math.PI / 3.0)

    /** Screen pixels per native tile pixel for a tile of [order] under an equal-area projection. */
    internal fun tileScale(order: Int, radPerPixel: Double, tileW: Float): Float =
        (HEALPIX_SIDE_RAD / (1L shl order).toDouble() / radPerPixel / tileW).toFloat()

    private fun bbox(pts: List<FloatArray>): FloatArray? {
        if (pts.isEmpty()) return null
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (p in pts) {
            if (p[0] < minX) minX = p[0]
            if (p[1] < minY) minY = p[1]
            if (p[0] > maxX) maxX = p[0]
            if (p[1] > maxY) maxY = p[1]
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }
}

/**
 * The cone of directions the screen can show: the largest angle between the view centre and any
 * screen point (corners and edge middles, grown by the culling margin).
 */
internal class VisibleCone(private val center: Vec3, private val maxAngleRad: Double) {
    fun mayContain(raDeg: Double, decDeg: Double, tileRadiusRad: Double): Boolean {
        if (maxAngleRad >= Math.PI) return true
        val d = Vec3.fromRaDec(raDeg, decDeg)
        val ang = Math.acos((center dot d).coerceIn(-1.0, 1.0))
        return ang <= maxAngleRad + tileRadiusRad
    }

    companion object {
        fun of(proj: Projection, marginPx: Float = 520f): VisibleCone {
            val w = proj.widthPx
            val h = proj.heightPx
            var maxAng = 0.0
            val xs = floatArrayOf(-marginPx, w / 2f, w + marginPx)
            val ys = floatArrayOf(-marginPx, h / 2f, h + marginPx)
            for (x in xs) for (y in ys) {
                val v = proj.inverseVec(x, y)
                val a = Math.acos((proj.center dot v).coerceIn(-1.0, 1.0))
                if (a > maxAng) maxAng = a
            }
            // sample points beyond the oval/disc clamp to its border: if any sample was clamped the
            // whole sphere (or hemisphere) can be on screen, so do not cut anything
            val whole = proj.kind == ProjectionKind.HAMMER && proj.hammerBorderVisible(marginPx)
            return VisibleCone(proj.center, if (whole) Math.PI else maxAng + 0.02)
        }
    }
}
