@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.kroryan.astrovia.SkyUiState
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.data.hips.TileRepository
import com.kroryan.astrovia.model.SkyPosition
import com.kroryan.astrovia.sky.CoordGrid
import com.kroryan.astrovia.sky.DisplayFilter
import com.kroryan.astrovia.sky.SkyRenderer
import com.kroryan.astrovia.sky.VisibleTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kroryan.astrovia.ui.theme.GridLine
import com.kroryan.astrovia.ui.theme.MarkerGreen
import com.kroryan.astrovia.ui.theme.MarkerRed
import com.kroryan.astrovia.ui.theme.SkyBlack
import com.kroryan.astrovia.ui.theme.TextPrimary
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Builds the Compose `ColorFilter` for view modes (brightness/contrast/inversion) from settings.
 * Returns null if everything is at defaults (no cost in draw). The matrix itself lives in
 * [DisplayFilter] so the screenshot export applies exactly the same transformation.
 */
private object SkyCanvasColorFilter {
    fun fromSettings(s: com.kroryan.astrovia.AppSettings): ColorFilter? =
        DisplayFilter.matrix(s.invert, s.contrast, s.brightness)?.let { ColorFilter.colorMatrix(ColorMatrix(it)) }
}

/**
 * Draws the RA/Dec grid. The segments come from [CoordGrid], which only samples the part of the
 * sphere that is on screen (the old code projected ~780,000 points per frame at high zoom).
 */
private const val LONG_PRESS_RADIUS_PX = 28f

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawCoordGrid(
    proj: com.kroryan.astrovia.sky.Projection,
    fovDeg: Double,
) {
    val seg = CoordGrid.segments(proj, fovDeg)
    val color = GridLine.copy(alpha = 0.35f)
    var i = 0
    while (i + 3 < seg.size) {
        drawLine(color, Offset(seg[i], seg[i + 1]), Offset(seg[i + 2], seg[i + 3]), strokeWidth = 1f)
        i += 4
    }
}

/**
 * Full-screen Canvas rendering the HiPS sky + overlays and managing
 * touch gestures (pan, zoom, tap). 100% native Android UI.
 *
 * Tiles are requested outside of draw (in a LaunchedEffect) and draw only reads
 * the cache; invalidation is triggered by TileRepository (coalesced).
 */
@Composable
fun SkyCanvas(
    state: SkyUiState,
    vm: SkyViewModel,
    modifier: Modifier = Modifier,
) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val camera = state.camera
    val props = state.props
    val survey = state.survey
    val tileRepo = vm.tileRepo

    // Visible tiles: heavy computation (HEALPix recursion) OUTSIDE the main thread
    // to avoid blocking UI or triggering ANR.
    //
    // IMPORTANT: we collect the StateFlow instead of keying LaunchedEffect by the
    // camera. StateFlow is already conflated and collect processes emissions in
    // SEQUENCE WITHOUT canceling the ongoing recalculation. If we keyed by `camera`,
    // every pan/zoom tick (~60/s) would cancel the previous recalculation before
    // it finished, so new tiles would only be requested when stopping the gesture
    // ("only loads on stop"). With collect, each recalculation runs to completion
    // and gets assigned -> tiles come in progressively as you move, and zooming in
    // increases order (more quality), just like the original Aladin.
    var visibleTiles by remember { mutableStateOf(emptyList<VisibleTile>()) }
    LaunchedEffect(canvasSize) {
        if (canvasSize.width > 0 && canvasSize.height > 0) {
            vm.setViewport(canvasSize.width.toFloat(), canvasSize.height.toFloat())
        }
    }
    LaunchedEffect(canvasSize) {
        // Recompute the tile set ONLY when the camera, survey or quality change. It used to run
        // on every state change (each arriving tile, messages, catalogue...), i.e. a full HEALPix
        // traversal every 80 ms while loading.
        vm.state
            .map { Triple(it.camera, it.props, it.settings.quality) }
            .distinctUntilChanged()
            .collect { (camera, p, quality) ->
                if (p != null && canvasSize.width > 0 && canvasSize.height > 0) {
                    val proj = camera.projection(canvasSize.width.toFloat(), canvasSize.height.toFloat())
                    val tiles = withContext(Dispatchers.Default) {
                        SkyRenderer.computeVisibleTiles(proj, p, quality.targetScale)
                    }
                    visibleTiles = tiles
                } else {
                    visibleTiles = emptyList()
                }
            }
    }

    // Request missing tiles (outside draw). Re-runs when the visible set changes.
    LaunchedEffect(visibleTiles, survey, props) {
        if (props != null) {
            for (t in visibleTiles) {
                val sample = TileRepository.sampleSizeFor(t.scalePx)
                if (tileRepo.get(survey.id, t.order, t.npix, sample) == null) {
                    tileRepo.request(survey, props, t.order, t.npix, t.scalePx)
                }
            }
        }
    }

    // Always-fresh values for the tap detector (which does not restart with camera).
    val cameraState = rememberUpdatedState(camera)
    val propsState = rememberUpdatedState(props)
    val measureModeState = rememberUpdatedState(state.measureMode)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            // Unified gesture detector (1-finger pan, 2-finger pinch-zoom
            // around the centroid, and tap). In a single pointerInput to avoid
            // detector conflicts that made pinch be interpreted as pan.
            // Standard image-viewer behavior.
            .pointerInput(Unit) {
                // Double-tap state persistent across gestures.
                var lastTapTime = 0L
                var lastTapPos = Offset.Zero
                var pendingMeasure: kotlinx.coroutines.Job? = null
                awaitEachGesture {
                    val first = awaitFirstDown(requireUnconsumed = false)
                    val downTime = System.currentTimeMillis()
                    val firstPos = first.position
                    var lastCentroid = firstPos
                    var lastDist = 0f
                    var lastAngle = 0f
                    var totalMove = 0f
                    var lastCount = 1
                    var twistEngaged = false
                    var twistAccum = 0f
                    val velocity = VelocityTracker()
                    velocity.addPosition(first.uptimeMillis, firstPos)
                    var wasMultiTouch = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val active = event.changes.filter { it.pressed }
                        if (active.isEmpty()) break
                        active.forEach { it.consume() }
                        val cnt = active.size
                        val centroid = if (cnt == 1) active[0].position
                        else active.fold(Offset.Zero) { acc, c -> acc + c.position } / cnt.toFloat()
                        if (cnt >= 2) wasMultiTouch = true

                        // Finger count changed: re-anchor so the picture does not jump, and
                        // restart the distance/angle references.
                        if (cnt != lastCount) {
                            lastCentroid = centroid
                            lastDist = 0f
                            lastAngle = 0f
                            twistEngaged = false
                            twistAccum = 0f
                            lastCount = cnt
                            if (cnt >= 2) totalMove = Float.MAX_VALUE
                            velocity.resetTracking()
                        }

                        val w = canvasSize.width.toFloat()
                        val h = canvasSize.height.toFloat()
                        if (w > 0f && h > 0f) {
                            var zoom = 1f
                            var twist = 0.0
                            if (cnt >= 2) {
                                val d = active[1].position - active[0].position
                                val dist = d.getDistance()
                                val angle = atan2(d.y, d.x) // screen y points down: increasing = clockwise
                                if (lastDist > 1f && dist > 1f) {
                                    zoom = (dist / lastDist).coerceIn(0.5f, 2.5f)
                                    var dA = Math.toDegrees((angle - lastAngle).toDouble()).toFloat()
                                    while (dA > 180f) dA -= 360f
                                    while (dA < -180f) dA += 360f
                                    // Twist only engages after ~8 deg so a plain pinch never rotates the sky.
                                    twistAccum += dA
                                    if (!twistEngaged && abs(twistAccum) > 8f) twistEngaged = true
                                    if (twistEngaged) twist = dA.toDouble()
                                }
                                lastDist = dist
                                lastAngle = angle
                            }
                            if (zoom != 1f || twist != 0.0 || centroid != lastCentroid) {
                                // Zoom and twist are anchored at the previous centroid, then the picture
                                // is dragged to the new one: the sky under the fingers stays under them.
                                vm.transform(lastCentroid.x, lastCentroid.y, centroid.x, centroid.y, zoom, twist, w, h)
                            }
                            if (cnt == 1) {
                                totalMove += (centroid - lastCentroid).getDistance()
                                velocity.addPosition(event.changes[0].uptimeMillis, centroid)
                            }
                        }
                        lastCentroid = centroid
                    }

                    val w = canvasSize.width
                    val h = canvasSize.height
                    val duration = System.currentTimeMillis() - downTime

                    // Inertia after a one-finger drag.
                    if (!wasMultiTouch && totalMove >= 24f && w > 0 && h > 0) {
                        val v = velocity.calculateVelocity()
                        vm.fling(v.x, v.y, w.toFloat(), h.toFloat())
                    }

                    // Tap or long press: little movement.
                    if (totalMove < 24f && w > 0 && h > 0) {
                        if (duration < 300) {
                            val now = System.currentTimeMillis()
                            val isDouble = (now - lastTapTime < 320) &&
                                (firstPos - lastTapPos).getDistance() < 48f
                            if (isDouble) {
                                // Second tap of a double tap: cancel the measurement point the first
                                // tap was about to add (it used to be added immediately).
                                pendingMeasure?.cancel()
                                val factor = if (cameraState.value.fovDeg <= 1.0) 0.4f else 2.5f
                                vm.zoomBy(factor, w.toFloat(), h.toFloat(), firstPos.x, firstPos.y)
                                lastTapTime = 0L
                            } else {
                                lastTapTime = now
                                lastTapPos = firstPos
                                if (measureModeState.value && propsState.value != null) {
                                    val proj = cameraState.value.projection(w.toFloat(), h.toFloat())
                                    val radec = proj.inverse(firstPos.x, firstPos.y)
                                    val pos = SkyPosition(radec[0], radec[1])
                                    // Wait out the double-tap window before committing the point.
                                    pendingMeasure?.cancel()
                                    pendingMeasure = scope.launch {
                                        delay(330)
                                        vm.measureTap(pos)
                                    }
                                }
                            }
                        } else if (duration >= 400 && propsState.value != null) {
                            val cam = cameraState.value
                            val proj = cam.projection(w.toFloat(), h.toFloat())
                            val radec = proj.inverse(firstPos.x, firstPos.y)
                            // A finger-sized radius (28 px) converted to degrees at the current zoom.
                            val radiusDeg = LONG_PRESS_RADIUS_PX * cam.fovDeg / h
                            vm.longPressAt(SkyPosition(radec[0], radec[1]), radiusDeg)
                        }
                    }
                }
            }
    ) {
        if (canvasSize.width == 0 || canvasSize.height == 0) return@Canvas
        drawRect(SkyBlack, size = size)

        val proj = camera.projection(size.width, size.height)
        // Color filter for view modes (brightness/contrast/inversion).
        // Null if all at defaults -> zero cost.
        val tileColorFilter = SkyCanvasColorFilter.fromSettings(state.settings)
        if (props != null) {
            for (t in visibleTiles) {
                val sample = TileRepository.sampleSizeFor(t.scalePx)
                val bmp = tileRepo.get(survey.id, t.order, t.npix, sample) ?: continue
                // Guard: a recycled bitmap (evicted from cache between frames)
                // would cause a native crash on draw; skip it this frame.
                if (bmp.isRecycled) continue
                // Cell mesh with LIVE camera: each cell is placed with its
                // own affine (parallelogram) and its corresponding bitmap slice.
                // This way tile edges match (no gaps) and the tile moves
                // smoothly on pan/zoom without waiting to recalculate the set.
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

        // Coordinate grid (RA/Dec), if enabled in Settings.
        if (state.settings.showGrid) {
            drawCoordGrid(proj, camera.fovDeg)
        }

        // Catalog overlays
        if (state.catalogVisible) {
            for (row in state.catalogRows) {
                val p = proj.forward(row.raDeg, row.decDeg) ?: continue
                val r = row.mag?.let { (8f - it.toFloat().coerceIn(0f, 20f) * 0.3f).coerceIn(1.5f, 5f) } ?: 2.5f
                drawCircle(color = MarkerGreen.copy(alpha = 0.85f), radius = r, center = Offset(p[0], p[1]))
            }
        }

        // Manual markers
        for (m in state.markers) {
            val p = proj.forward(m.position.raDeg, m.position.decDeg) ?: continue
            drawCircle(color = androidx.compose.ui.graphics.Color(m.color), radius = 6f, center = Offset(p[0], p[1]))
            drawCircle(color = androidx.compose.ui.graphics.Color.White, radius = 6f, center = Offset(p[0], p[1]), style = Stroke(width = 1.5f))
        }

        // Measurements
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

        // Center crosshair
        val c = Offset(size.width / 2f, size.height / 2f)
        drawLine(GridLine, Offset(c.x - 12f, c.y), Offset(c.x + 12f, c.y), strokeWidth = 1f)
        drawLine(GridLine, Offset(c.x, c.y - 12f), Offset(c.x, c.y + 12f), strokeWidth = 1f)
    }
}
