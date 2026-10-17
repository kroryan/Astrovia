@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kroryan.astrovia.data.fits.LightCurve
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** "Nice" tick positions for an axis range. */
internal fun niceTicks(lo: Double, hi: Double, target: Int = 5): List<Double> {
    if (!(hi > lo)) return listOf(lo)
    val raw = (hi - lo) / target
    val mag = 10.0.pow(floor(log10(raw)))
    val step = listOf(1.0, 2.0, 5.0, 10.0).map { it * mag }.first { it >= raw }
    val first = ceil(lo / step) * step
    return generateSequence(first) { it + step }.takeWhile { it <= hi + step * 1e-9 }.toList()
}

private fun fmtTick(v: Double, step: Double): String =
    if (abs(v) >= 1e5 || (v != 0.0 && abs(v) < 1e-3)) "%.2e".format(v) else "%.${(-floor(log10(step))).toInt().coerceIn(0, 6)}f".format(v)

/** Light-curve plot: pinch to zoom, drag to pan, double-tap-free; axes with ticks, optional error bars. */
@Composable
fun LightCurveView(lc: LightCurve, topInset: androidx.compose.ui.unit.Dp, bottomInset: androidx.compose.ui.unit.Dp, onClose: () -> Unit) {
    val t0 = lc.time.min(); val t1 = lc.time.max()
    var viewLo by remember(lc) { mutableDoubleStateOf(t0) }
    var viewHi by remember(lc) { mutableDoubleStateOf(t1) }
    val tm = rememberTextMeasurer()
    val style = TextStyle(color = TextSecondary, fontSize = 10.sp)
    BackHandler(onBack = onClose)
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { }) {
        Surface(color = Color(0xFF0B0F1A), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = topInset, bottom = bottomInset)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 8.dp)) {
                        Text(lc.title, color = TextPrimary, maxLines = 1)
                        Text("${lc.time.size} points", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { viewLo = t0; viewHi = t1 }) { Text("Reset") }
                    IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                Canvas(
                    Modifier.weight(1f).fillMaxWidth().pointerInput(lc) {
                        detectTransformGestures { centroid, pan, zoom, _ ->
                            val w = (size.width - 56.dp.toPx()).coerceAtLeast(1f)
                            val span = viewHi - viewLo
                            val fx = ((centroid.x - 48.dp.toPx()) / w).coerceIn(0f, 1f)
                            val newSpan = (span / zoom).coerceIn((t1 - t0) / 2000.0, (t1 - t0) * 1.0)
                            var lo = viewLo + (span - newSpan) * fx - pan.x / w * newSpan
                            lo = lo.coerceIn(t0, maxOf(t0, t1 - newSpan))
                            viewLo = lo; viewHi = lo + newSpan
                        }
                    },
                ) {
                    val left = 48.dp.toPx(); val right = 8.dp.toPx(); val top = 8.dp.toPx(); val bottom = 28.dp.toPx()
                    val w = size.width - left - right; val h = size.height - top - bottom
                    val idx = lc.time.indices.filter { lc.time[it] in viewLo..viewHi }
                    if (idx.isEmpty()) return@Canvas
                    var fl = idx.minOf { lc.flux[it] }; var fh = idx.maxOf { lc.flux[it] }
                    if (fh <= fl) { fl -= 1.0; fh += 1.0 }
                    val pad = (fh - fl) * 0.06; fl -= pad; fh += pad
                    fun px(t: Double) = left + ((t - viewLo) / (viewHi - viewLo) * w).toFloat()
                    fun py(f: Double) = top + h - ((f - fl) / (fh - fl) * h).toFloat()
                    val yt = niceTicks(fl, fh); val xt = niceTicks(viewLo, viewHi)
                    val ystep = if (yt.size > 1) yt[1] - yt[0] else 1.0; val xstep = if (xt.size > 1) xt[1] - xt[0] else 1.0
                    yt.forEach { v ->
                        drawLine(Color(0x22FFFFFF), Offset(left, py(v)), Offset(left + w, py(v)), 1f)
                        val tx = tm.measure(fmtTick(v, ystep), style)
                        drawText(tx, topLeft = Offset(left - tx.size.width - 4.dp.toPx(), py(v) - tx.size.height / 2f))
                    }
                    xt.forEach { v ->
                        drawLine(Color(0x22FFFFFF), Offset(px(v), top), Offset(px(v), top + h), 1f)
                        val tx = tm.measure(fmtTick(v, xstep), style)
                        drawText(tx, topLeft = Offset(px(v) - tx.size.width / 2f, top + h + 3.dp.toPx()))
                    }
                    drawLine(Color.Gray, Offset(left, top + h), Offset(left + w, top + h), 1.5f)
                    drawLine(Color.Gray, Offset(left, top), Offset(left, top + h), 1.5f)
                    val stride = (idx.size / 6000).coerceAtLeast(1)
                    for (k in idx.indices step stride) {
                        val i = idx[k]
                        val x = px(lc.time[i]); val y = py(lc.flux[i])
                        lc.error?.let { e -> if (idx.size < 1500) drawLine(Color(0x5538BDF8), Offset(x, py(lc.flux[i] - e[i])), Offset(x, py(lc.flux[i] + e[i])), 1f) }
                        drawCircle(Color(0xFF38BDF8), 1.8.dp.toPx(), Offset(x, y))
                    }
                }
                Text("${lc.timeLabel}   ·   y: ${lc.fluxLabel}   ·   pinch to zoom, drag to pan", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
}
