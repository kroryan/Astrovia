package com.aladin.android.sky

import com.aladin.android.data.hips.HiPSProperties
import kotlin.math.hypot

/**
 * Tile visible. Guardamos las esquinas y el centro en coordenadas celestes
 * (ra/dec grados), NO en píxeles de pantalla: así el draw los proyecta con la
 * cámara EN VIVO y el pan/zoom mueve los tiles de forma fluida sin esperar a
 * recalcular el conjunto de tiles visibles.
 *
 * `scalePx` es un valor representativo (px de pantalla por px de tile nativo)
 * calculado en el momento del cómputo, solo para decidir el `inSampleSize`.
 */
data class VisibleTile(
    val order: Int,
    val npix: Long,
    val cornersRadec: Array<DoubleArray>,
    val centerRadec: DoubleArray,
    val scalePx: Float,
)

/**
 * Una celda de la malla en que se subdivide un tile para colocarlo: la matriz
 * afín 4×4 que la coloca en pantalla y el trozo del bitmap (srcOffset/srcSize)
 * que hay que pintar en ella.
 */
data class MeshCell(
    val matrix: FloatArray,
    val srcX: Int,
    val srcY: Int,
    val srcW: Int,
    val srcH: Int,
)

/**
 * Colocación de un tile en pantalla como matriz afín 4×4 (16 floats, layout de
 * `androidx.compose.ui.graphics.Matrix`). El bitmap se dibuja en (0,0) y la
 * matriz mapea sus coordenadas de píxel a pantalla.
 */
private fun affineMatrix(o: FloatArray, e1: FloatArray, e3: FloatArray, w: Float, h: Float): FloatArray {
    // x' = ax*u + bx*v + ox ; y' = ay*u + by*v + oy  (u,v = coords píxel del bitmap)
    val ax = (e1[0] - o[0]) / w
    val ay = (e1[1] - o[1]) / w
    val bx = (e3[0] - o[0]) / h
    val by = (e3[1] - o[1]) / h
    return floatArrayOf(
        ax, ay, 0f, 0f,
        bx, by, 0f, 0f,
        0f, 0f, 1f, 0f,
        o[0], o[1], 0f, 1f,
    )
}

/**
 * Calcula qué tiles HiPS hay que dibujar para una proyección dada, por recursión
 * del árbol NESTED (los hijos de (order,npix) son 4*npix+{0,1,2,3} en order+1).
 * Solo necesita HEALPix forward (centro y esquinas).
 */
object SkyRenderer {

    /**
     * Margen de culling (px) más allá de la pantalla: precarga el borde
     * inmediato para que el pan no muestre negro.
     */
    private const val CULL_MARGIN_PX = 512f

    /**
     * Umbral de escala por defecto para considerar un tile "suficiente detalle" y
     * dejar de subdividir. `scalePx` = px de pantalla por px de tile nativo
     * (proyectado). Subdividimos solo mientras el tile está UPSCALED más allá de
     * su resolución nativa (scalePx > targetScale); a partir de ahí subdividir no
     * aporta detalle real (más tiles para la MISMA imagen nativa) y solo dispara
     * la cuenta de tiles -> carga eterna.
     *
     * Esto reproduce el `maxOrder(v)` de Aladin desktop, que elige el orden donde
     * ~1 px de tile ≈ 1 px de pantalla (con un ligero oversample para nitidez).
     * Antes usábamos `bboxSpan <= 280`, pero el tile HiPS es un rombo y su bbox
     * alineado a ejes es ~1.4× el lado, así que subdividía un orden de más: a
     * FoV moderado (p.ej. 2°) generaba cientos de tiles de ~60px (¡452!) y la
     * carga a 3s/tile se volvía eterna. Con scalePx el recuento se queda en
     * ~8-30 tiles por pantalla en todo el rango de zoom.
     *
     * El valor real lo aporta el parámetro `targetScale` de computeVisibleTiles,
     * ajustable desde Settings (TileQuality).
     */
    private const val DEFAULT_TARGET_SCALE = 1.5f

    /** Límite duro de tiles visibles: evita avalanchas patológicas (polos / FoV extremo). */
    private const val MAX_TILES = 600

    fun computeVisibleTiles(
        proj: Projection,
        props: HiPSProperties,
        targetScale: Float = DEFAULT_TARGET_SCALE,
    ): List<VisibleTile> {
        val startOrder = props.orderMin.coerceIn(0, props.order.coerceAtLeast(props.orderMin))
        val maxOrder = props.order
        val tileW = props.tileWidth.toFloat()

        // BFS por niveles (orden), NO DFS. Antes se recorría el árbol NESTED en
        // profundidad por cada tile base (12 a order 0): un solo branch podía
        // bajar hasta order 9 y agotar el presupuesto MAX_TILES él solo. A FoV
        // normal (~2°) solo hay 1 tile base en pantalla, así que nunca se notaba.
        // Pero al alejar el zoom del todo hay hasta 12 tiles base visibles a la
        // vez, y con DFS los últimos en el orden de iteración (0 until npix)
        // se quedaban SIN NINGÚN tile -ni siquiera el grueso- porque el primer
        // branch ya había consumido el cupo: de ahí el "se pone negro
        // totalmente" al alejar mucho (esas zonas ni se piden). Con BFS
        // completamos SIEMPRE un nivel entero (todo lo visible a ese orden)
        // antes de subdividir al siguiente, así que un FoV amplio simplemente se
        // queda en un orden más grueso mostrando el cielo COMPLETO en vez de
        // partes en negro. Esto es además lo que hace Aladin desktop
        // (PlanBG.drawLosangesAsync itera orden a orden, cmin..max).
        var level = ArrayList<Long>(Healpix.npix(startOrder).toInt().coerceAtMost(12))
        for (ipix in 0 until Healpix.npix(startOrder)) level.add(ipix)

        val out = ArrayList<VisibleTile>()
        var order = startOrder
        while (level.isNotEmpty()) {
            val survivors = ArrayList<Long>()
            for (ipix in level) {
                val corners = Healpix.corners(order, ipix)
                val centerRadec = Healpix.center(order, ipix)
                val pts = ArrayList<FloatArray>(4)
                for (c in corners) proj.forward(c[0], c[1])?.let { pts.add(it) }
                val centerPx = proj.forward(centerRadec[0], centerRadec[1])

                // Culling: solo procesamos tiles cuyo bbox proyectado interseca la
                // pantalla (con margen de ~1 tile), para precargar el borde
                // inmediato y que el pan no muestre negro.
                val margin = CULL_MARGIN_PX
                val bbox = bbox(pts)
                val intersects = if (bbox != null) {
                    bbox[2] >= -margin && bbox[0] <= proj.widthPx + margin &&
                        bbox[3] >= -margin && bbox[1] <= proj.heightPx + margin
                } else {
                    centerPx != null &&
                        centerPx[0] >= -margin && centerPx[0] <= proj.widthPx + margin &&
                        centerPx[1] >= -margin && centerPx[1] <= proj.heightPx + margin
                }
                if (!intersects) continue

                // Escala representativa (px de pantalla por px de tile nativo).
                val scalePx = if (pts.size >= 2) {
                    val len = hypot(pts[1][0] - pts[0][0], pts[1][1] - pts[0][1])
                    if (len > 0f) len / tileW else 1f
                } else {
                    1f
                }

                // Emitimos TODOS los nodos on-screen (no solo los leaves): el draw
                // dispone del ancestro grueso como base de refinamiento progresivo.
                out.add(VisibleTile(order, ipix, corners, centerRadec, scalePx))

                // Leaf cuando el tile ya está a su resolución nativa o más fino
                // (scalePx <= targetScale) Y las 4 esquinas proyectan. Los tiles
                // del limbo (pts<4) se subdividen en vez de quedar como leaf: bajan
                // a order max, donde los off-screen se podan y los on-screen se
                // dibujan a la resolución correcta.
                val enoughDetail = pts.size >= 2 && scalePx <= targetScale
                if (order < maxOrder && !(enoughDetail && pts.size >= 4)) {
                    survivors.add(ipix)
                }
            }
            // Solo comprobamos el presupuesto DESPUÉS de completar el nivel entero
            // (nunca a mitad): así un nivel grueso siempre queda completo, sin
            // huecos, aunque se pase un poco de MAX_TILES (p.ej. order 3 = 768
            // tiles base en toda la esfera, > 600, pero se admite igualmente).
            if (survivors.isEmpty() || out.size >= MAX_TILES) break
            val nextLevel = ArrayList<Long>(survivors.size * 4)
            for (p in survivors) {
                nextLevel.add(4 * p); nextLevel.add(4 * p + 1)
                nextLevel.add(4 * p + 2); nextLevel.add(4 * p + 3)
            }
            level = nextLevel
            order++
        }

        // Refinamiento progresivo (idéntico a Aladin desktop —ver
        // PlanBG.drawLosangesAsync, que dibuja los tiles listos de cada orden de
        // cmin..max, los gruesos primero como base y los finos encima). Nos
        // quedamos solo con los ~4 niveles superiores de CADA RAMA (célula base
        // de startOrder), NO un cmin global.
        //
        // BUG que esto corrige: con un cmin global (maxObs de TODO out, cmin =
        // maxObs-3 aplicado a TODOS los tiles), en FoV amplio distintas ramas
        // (distintas células base visibles a la vez) alcanzan profundidades muy
        // distintas -una cerca del centro puede llegar a order 8 mientras otra,
        // más deformada/pequeña en pantalla, ya tiene "enoughDetail" en order 2-.
        // Con cmin global = 8-3 = 5, la rama que se quedó en order 2 pierde TODOS
        // sus tiles (2 < 5): esa región de pantalla se queda sin ningún tile, ni
        // siquiera el grueso -> negro total. Es justo lo que se veía al alejar el
        // zoom del todo. Calculando cmin POR RAMA, cada célula base conserva sus
        // propios ~4 niveles superiores relativos a SU PROPIA profundidad máxima,
        // así que toda rama visible siempre tiene al menos un tile.
        if (out.isEmpty()) return out
        val floorOrder = props.orderMin.coerceAtLeast(0)
        val branchMaxOrder = HashMap<Long, Int>()
        for (t in out) {
            val shift = 2 * (t.order - startOrder)
            val branch = if (shift >= 0) t.npix shr shift else t.npix
            val cur = branchMaxOrder[branch]
            if (cur == null || t.order > cur) branchMaxOrder[branch] = t.order
        }
        val filtered = ArrayList<VisibleTile>(out.size)
        for (t in out) {
            val shift = 2 * (t.order - startOrder)
            val branch = if (shift >= 0) t.npix shr shift else t.npix
            val branchMax = branchMaxOrder[branch] ?: t.order
            val cmin = (branchMax - 3).coerceAtLeast(floorOrder)
            if (t.order >= cmin) filtered.add(t)
        }
        // Orden ascendente: gruesos (orden bajo) primero = base; finos (orden
        // alto) después = se dibujan encima. Sin esto, un tile grueso dibujado
        // después de uno fino lo taparía.
        filtered.sortBy { it.order }
        return filtered
    }

    /**
     * Subdivide el tile en una malla N×N y devuelve una celda por subcuadrante,
     * cada una con su matriz afín (paralelogramo) y el trozo de bitmap que le
     * corresponde.
     *
     * Orientación del bitmap (convención HiPS, idéntica a Aladin desktop —ver
     * CDSHealpix.corners() que devuelve [S,E,W,N] y HealpixKey.drawTriangle que
     * mapea TL=S, TR=W, BL=E, BR=N): el eje X del bitmap va a lo largo de +fy
     * (esquina S->W) y el eje Y a lo largo de +fx (esquina S->E). Por eso la
     * rejilla se indexa como grid[fy_i][fx_j] (NO al revés): si se cruzan los
     * ejes, cada tile queda rotado 90° y descuadrado respecto a sus vecinos.
     *
     * `n` es adaptativo: 1 celda si el tile apenas se deforma (cerca del centro
     * del FoV), 4×4=16 si hay distorsión apreciable (cerca del borde del FoV).
     * Con n=1 la única celda es el paralelogramo entero (ancla S, W, E exactos).
     */
    fun placementMesh(
        proj: Projection,
        tile: VisibleTile,
        bmpW: Int,
        bmpH: Int,
    ): List<MeshCell>? {
        val n = meshSubdivisions(proj, tile)
        val cw = bmpW / n
        val ch = bmpH / n
        // grid[i][j] = punto proyectado en (fy=i/n, fx=j/n) del tile.
        val grid = Array(n + 1) { i ->
            Array<FloatArray?>(n + 1) { j ->
                val ra = Healpix.radecAt(tile.order, tile.npix, j.toDouble() / n, i.toDouble() / n)
                proj.forward(ra[0], ra[1])
            }
        }
        val cells = ArrayList<MeshCell>(n * n)
        for (i in 0 until n) {
            for (j in 0 until n) {
                val o = grid[i][j] ?: continue       // (fy=i/n,   fx=j/n)
                val e1 = grid[i + 1][j] ?: continue  // +fy  -> eje X del bitmap
                val e3 = grid[i][j + 1] ?: continue  // +fx  -> eje Y del bitmap
                // Salvaguarda anti-limbo: si la celda proyecta a un paralelogramo
                // MUCHO mayor que la pantalla, es un artefacto del borde/extremo
                // (tile cerca del horizonte gnomónico que se estira brutalmente).
                // Dibujarlo pintaría una "textura rara" cruzando toda la vista. Lo
                // saltamos; sus celdas vecinas válidas cubren la zona real.
                val spanW = hypot(e1[0] - o[0], e1[1] - o[1])
                val spanH = hypot(e3[0] - o[0], e3[1] - o[1])
                val maxSpan = 3f * maxOf(proj.widthPx, proj.heightPx)
                if (spanW > maxSpan || spanH > maxSpan) continue
                cells.add(
                    MeshCell(
                        affineMatrix(o, e1, e3, cw.toFloat(), ch.toFloat()),
                        i * cw, j * ch, cw, ch,
                    )
                )
            }
        }
        return if (cells.isEmpty()) null else cells
    }

    /**
     * Decide cuántas subdivisiones (por eje) necesita el tile: 1 si las 4
     * esquinas proyectan y el 4º vértice del paralelogramo casi coincide con la
     * esquina real (poca distorsión -> 1 afín basta), 4 si la distorsión es
     * apreciable (tile cerca del borde del FoV).
     */
    private fun meshSubdivisions(proj: Projection, tile: VisibleTile): Int {
        val p = Array<FloatArray?>(4) { i ->
            val c = tile.cornersRadec[i]
            proj.forward(c[0], c[1])
        }
        for (anchor in 0..3) {
            val a = p[anchor]
            val b = p[(anchor + 1) % 4]
            val d = p[(anchor + 3) % 4]
            val c2 = p[(anchor + 2) % 4]
            if (a != null && b != null && d != null && c2 != null) {
                // Vértice predicho por el paralelogramo vs esquina real proyectada.
                val predX = b[0] + d[0] - a[0]
                val predY = b[1] + d[1] - a[1]
                val err = hypot(predX - c2[0], predY - c2[1])
                return if (err < 1.5f) 1 else 4
            }
        }
        // No hay 4 esquinas proyectables (limbo): malla para no dejar huecos.
        return 4
    }

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