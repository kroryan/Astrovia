package com.aladin.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.aladin.android.SkyUiState
import com.aladin.android.SkyViewModel
import com.aladin.android.data.hips.TileRepository
import com.aladin.android.model.SkyPosition
import com.aladin.android.sky.SkyRenderer
import com.aladin.android.sky.VisibleTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import com.aladin.android.ui.theme.GridLine
import com.aladin.android.ui.theme.MarkerGreen
import com.aladin.android.ui.theme.MarkerRed
import com.aladin.android.ui.theme.SkyBlack
import com.aladin.android.ui.theme.TextPrimary
import kotlin.math.hypot

/**
 * Construye el `ColorFilter` de Compose para los modos de vista
 * (brillo/contraste/inversión) a partir de los ajustes. Devuelve null si todo
 * está por defecto (sin coste en el draw).
 *
 * La matriz opera sobre colores en [0,1]: out = sign·c·(v − 0.5) + 0.5 + b,
 * con sign = −1 si invertido, c = contraste, b = brillo. Identidad con los
 * valores por defecto (sign=1, c=1, b=0).
 */
private object SkyCanvasColorFilter {
    fun fromSettings(s: com.aladin.android.AppSettings): ColorFilter? {
        val invert = s.invert
        val c = s.contrast
        val b = s.brightness
        if (!invert && c == 1f && b == 0f) return null
        val sign = if (invert) -1f else 1f
        val off = -sign * c * 0.5f + 0.5f + b
        val m = floatArrayOf(
            sign * c, 0f, 0f, 0f, off,
            0f, sign * c, 0f, 0f, off,
            0f, 0f, sign * c, 0f, off,
            0f, 0f, 0f, 1f, 0f,
        )
        return ColorFilter.colorMatrix(ColorMatrix(m))
    }
}

/** Pasos de rejilla en grados; se elige el más pequeño que deje ~6 líneas en pantalla. */
private val GRID_STEPS_DEG = doubleArrayOf(0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 45.0, 90.0)

private fun gridStepDeg(fovDeg: Double): Double {
    for (s in GRID_STEPS_DEG) if (fovDeg / s <= 6.0) return s
    return 90.0
}

/**
 * Dibuja meridianos (RA cte.) y paralelos (Dec cte.) proyectados con la cámara
 * en vivo. El paso se adapta al FoV (como la rejilla de Aladin desktop, ver
 * `ViewSimple.calculGrid`): a más zoom-out, líneas más espaciadas. Cada curva
 * se aproxima por segmentos entre puntos muestreados; los segmentos con un
 * salto de pantalla enorme (paso por el limbo/antípoda o wrap de RA) se
 * descartan para no cruzar la vista con una línea espuria.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCoordGrid(
    proj: com.aladin.android.sky.Projection,
    fovDeg: Double,
) {
    val step = gridStepDeg(fovDeg)
    val sampleStep = step.coerceAtLeast(2.0) / 6.0
    val maxJump = maxOf(size.width, size.height) * 1.5f
    val marginPx = 200f
    val minX = -marginPx; val maxX = size.width + marginPx
    val minY = -marginPx; val maxY = size.height + marginPx

    fun onScreen(p: Offset) = p.x in minX..maxX && p.y in minY..maxY

    fun drawCurve(points: List<FloatArray?>) {
        var prev: Offset? = null
        for (raw in points) {
            val pt = raw?.let { Offset(it[0], it[1]) }
            if (prev != null && pt != null) {
                if (hypot(pt.x - prev.x, pt.y - prev.y) < maxJump && (onScreen(prev) || onScreen(pt))) {
                    drawLine(GridLine.copy(alpha = 0.35f), prev, pt, strokeWidth = 1f)
                }
            }
            prev = pt
        }
    }

    // Meridianos: RA constante, Dec de -90 a 90.
    var ra = 0.0
    while (ra < 360.0) {
        val pts = ArrayList<FloatArray?>()
        var dec = -90.0
        while (dec <= 90.0) {
            pts.add(proj.forward(ra, dec))
            dec += sampleStep
        }
        drawCurve(pts)
        ra += step
    }

    // Paralelos: Dec constante (sin los polos exactos), RA de 0 a 360.
    var dec = -90.0 + step
    while (dec < 90.0) {
        val pts = ArrayList<FloatArray?>()
        var raS = 0.0
        while (raS <= 360.0) {
            pts.add(proj.forward(raS, dec))
            raS += sampleStep
        }
        drawCurve(pts)
        dec += step
    }
}

/**
 * Canvas a pantalla completa que renderiza el cielo HiPS + overlays y gestiona
 * los gestos táctiles (pan, zoom, tap). UI 100% Android nativa.
 *
 * Los tiles se piden fuera del draw (en un LaunchedEffect) y el draw solo lee
 * el cache; la invalidación la dispara el TileRepository (coalescida).
 */
@Composable
fun SkyCanvas(
    state: SkyUiState,
    vm: SkyViewModel,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val camera = state.camera
    val props = state.props
    val survey = state.survey
    val tileRepo = vm.tileRepo

    // Tiles visibles: cálculo pesado (recursión HEALPix) FUERA del hilo principal
    // para no bloquear la UI ni disparar ANR.
    //
    // IMPORTANTE: colecionamos el StateFlow en vez de keyar LaunchedEffect por la
    // cámara. StateFlow ya es conflado y collect procesa las emisiones en SECUENCIA
    // SIN cancelar el recálculo en curso. Si keyáramos por `camera`, cada tick de
    // pan/zoom (~60/s) cancelaría el recálculo anterior antes de que terminara, así
    // que los tiles nuevos solo se pedirían al parar el gesto ("solo carga al parar").
    // Con collect, cada recálculo llega a completarse y asignarse -> los tiles entran
    // progresivamente mientras te mueves y al hacer zoom sube de orden (más calidad),
    // igual que el Aladin original.
    var visibleTiles by remember { mutableStateOf(emptyList<VisibleTile>()) }
    LaunchedEffect(canvasSize) {
        vm.state.collect { s ->
            val p = s.props
            if (p != null && canvasSize.width > 0 && canvasSize.height > 0) {
                val proj = s.camera.projection(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                val tiles = withContext(Dispatchers.Default) {
                    SkyRenderer.computeVisibleTiles(proj, p, s.settings.quality.targetScale)
                }
                visibleTiles = tiles
            } else {
                visibleTiles = emptyList()
            }
        }
    }

    // Pedir los tiles que falten (fuera del draw). Se reejecuta al cambiar el set visible.
    LaunchedEffect(visibleTiles, survey, props) {
        if (props != null) {
            for (t in visibleTiles) {
                val sample = TileRepository.sampleSizeFor(t.scalePx)
                if (tileRepo.get(survey.id, t.order, t.npix, sample) == null) {
                    tileRepo.request(survey, props.ext, t.order, t.npix, t.scalePx)
                }
            }
        }
    }

    // Valores siempre frescos para el detector de tap (que no se reinicia con la cámara).
    val cameraState = rememberUpdatedState(camera)
    val propsState = rememberUpdatedState(props)
    val measureModeState = rememberUpdatedState(state.measureMode)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            // Detector unificado de gestos (pan de 1 dedo, pinza-zoom de 2 dedos
            // alrededor del centroide, y tap). En un solo pointerInput para evitar
            // conflictos entre detectores que hacían que el pinza se interpretara
            // como pan. Comportamiento estándar tipo visor de imágenes.
            .pointerInput(Unit) {
                // Estado de doble-tap persistente entre gestos (para zoom estándar
                // tipo visor de imágenes: doble toque acerca/aleja la vista).
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val firstPos = first.position
                    var lastCentroid = firstPos
                    var lastPinchDist = 0f
                    var totalMove = 0f

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        active.forEach { it.consume() }
                        val cnt = active.size
                        val centroid = if (cnt == 1) active[0].position
                        else active.fold(Offset.Zero) { acc, c -> acc + c.position } / cnt.toFloat()

                        if (canvasSize.width > 0 && canvasSize.height > 0) {
                            val w = canvasSize.width.toFloat()
                            val h = canvasSize.height.toFloat()
                            if (cnt >= 2) {
                                // Pinza: zoom por la variación de distancia entre los 2 dedos.
                                val dist = (active[0].position - active[1].position).getDistance()
                                if (lastPinchDist > 1f && dist > 1f) {
                                    val zoomFactor = (dist / lastPinchDist).coerceIn(0.5f, 2.5f)
                                    if (zoomFactor != 1f) {
                                        vm.zoomBy(zoomFactor, w, h, centroid.x, centroid.y)
                                    }
                                }
                                // Pan por el desplazamiento del centroide de los 2 dedos.
                                val panDelta = centroid - lastCentroid
                                if (panDelta != Offset.Zero) vm.panBy(panDelta.x, panDelta.y, w, h)
                                lastPinchDist = dist
                            } else {
                                // 1 dedo: pan.
                                val panDelta = centroid - lastCentroid
                                if (panDelta != Offset.Zero) {
                                    vm.panBy(panDelta.x, panDelta.y, w, h)
                                    totalMove += panDelta.getDistance()
                                }
                            }
                        }
                        lastCentroid = centroid
                    }
                    // Tap: toque rápido sin apenas movimiento.
                    if (totalMove < 24f && System.currentTimeMillis() - downTime < 300) {
                        val now = System.currentTimeMillis()
                        val isDouble = (now - lastTapTime < 320) &&
                            (firstPos - lastTapPos).getDistance() < 48f
                        if (isDouble) {
                            // Doble toque: zoom estándar tipo visor de imágenes.
                            // Si ya estamos muy cerca (≤1°), alejamos; si no, acercamos x2,5
                            // hacia el punto tocado. Mismo camino que el pinza (vm.zoomBy),
                            // así que verifica toda la pipeline de zoom.
                            val w = canvasSize.width.toFloat()
                            val h = canvasSize.height.toFloat()
                            if (w > 0 && h > 0) {
                                val factor = if (cameraState.value.fovDeg <= 1.0) 0.4f else 2.5f
                                vm.zoomBy(factor, w, h, firstPos.x, firstPos.y)
                            }
                            lastTapTime = 0L
                        } else {
                            // Toque simple (posible primero de un doble): identificar/medir.
                            lastTapTime = now
                            lastTapPos = firstPos
                            val w = canvasSize.width; val h = canvasSize.height
                            val p = propsState.value
                            if (w > 0 && h > 0 && p != null) {
                                val proj = cameraState.value.projection(w.toFloat(), h.toFloat())
                                val radec = proj.inverse(firstPos.x, firstPos.y)
                                val pos = SkyPosition(radec[0], radec[1])
                                // Toque simple: solo actúa en modo medida. La
                                // identificación por tap usaba Simbad, cuyo endpoint
                                // no responde -> se quitó para no mostrar "sin
                                // resultados" engañoso. El modo medida sí funciona.
                                if (measureModeState.value) vm.measureTap(pos)
                            }
                        }
                    }
                }
            }
    ) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@Canvas
        drawRect(SkyBlack, size = size)

        val proj = camera.projection(size.width, size.height)
        // Filtro de color para los modos de vista (brillo/contraste/inversión).
        // Null si todo está por defecto -> sin coste.
        val tileColorFilter = SkyCanvasColorFilter.fromSettings(state.settings)
        if (props != null) {
            for (t in visibleTiles) {
                val sample = TileRepository.sampleSizeFor(t.scalePx)
                val bmp = tileRepo.get(survey.id, t.order, t.npix, sample) ?: continue
                // Guardia: un bitmap reciclado (expulsado de la caché entre frames)
                // provocaría un crash nativo al dibujar; lo saltamos este frame.
                if (bmp.isRecycled) continue
                // Malla de celdas con la cámara EN VIVO: cada celda se coloca con su
                // propio afín (paralelogramo) y el trozo de bitmap que le toca. Así
                // los bordes entre tiles coinciden (sin huecos) y el tile se mueve
                // fluido al hacer pan/zoom sin esperar a recalcular el conjunto.
                val cells = SkyRenderer.placementMesh(proj, t, bmp.width, bmp.height) ?: continue
                val img = bmp.asImageBitmap()
                for (cell in cells) {
                    withTransform({
                        transform(Matrix(cell.matrix))
                    }) {
                        drawImage(
                            image = img,
                            srcOffset = IntOffset(cell.srcX, cell.srcY),
                            srcSize = IntSize(cell.srcW, cell.srcH),
                            dstOffset = IntOffset.Zero,
                            dstSize = IntSize(cell.srcW, cell.srcH),
                            colorFilter = tileColorFilter,
                        )
                    }
                }
            }
        }

        // Rejilla de coordenadas (RA/Dec), si está activada en Settings.
        if (state.settings.showGrid) {
            drawCoordGrid(proj, camera.fovDeg)
        }

        // Overlays de catálogo
        for (row in state.catalogRows) {
            val p = proj.forward(row.raDeg, row.decDeg) ?: continue
            val r = row.mag?.let { (8f - it.toFloat().coerceIn(0f, 20f) * 0.3f).coerceIn(1.5f, 5f) } ?: 2.5f
            drawCircle(color = MarkerGreen.copy(alpha = 0.85f), radius = r, center = Offset(p[0], p[1]))
        }

        // Marcadores manuales
        for (m in state.markers) {
            val p = proj.forward(m.position.raDeg, m.position.decDeg) ?: continue
            drawCircle(color = androidx.compose.ui.graphics.Color(m.color), radius = 6f, center = Offset(p[0], p[1]))
            drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = 6f, center = Offset(p[0], p[1]), style = Stroke(width = 1.5f))
        }

        // Medidas
        for (meas in state.measurements) {
            val a = proj.forward(meas.from.raDeg, meas.from.decDeg) ?: continue
            val b = proj.forward(meas.to.raDeg, meas.to.decDeg) ?: continue
            drawLine(MarkerRed, Offset(a[0], a[1]), Offset(b[0], b[1]), strokeWidth = 2f)
            drawCircle(MarkerRed, 4f, Offset(a[0], a[1]))
            drawCircle(MarkerRed, 4f, Offset(b[0], b[1]))
        }
        state.pendingMeasureFrom?.let { from ->
            proj.forward(from.raDeg, from.decDeg)?.let { p ->
                drawCircle(TextPrimary, 6f, Offset(p[0], p[1]))
            }
        }

        // Crosshair en el centro
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLine(GridLine, Offset(c.x - 12f, c.y), Offset(c.x + 12f, c.y), strokeWidth = 1f)
        drawLine(GridLine, Offset(c.x, c.y - 12f), Offset(c.x, c.y + 12f), strokeWidth = 1f)
    }
}