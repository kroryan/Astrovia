@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.data.fits.ColorMap
import com.kroryan.astrovia.data.fits.Stretch
import com.kroryan.astrovia.model.FitsDocument
import com.kroryan.astrovia.sky.AstroMath
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

/** Full-screen FITS viewer: pan/zoom, stretch and colour map, cut levels, HDUs, header, pixel readout, "show on sky". */
@Composable
fun FitsViewer(vm: SkyViewModel, doc: FitsDocument) {
    var scale by remember(doc.hdu?.index, doc.name) { mutableFloatStateOf(1f) }
    var offset by remember(doc.hdu?.index, doc.name) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var readout by remember(doc.hdu?.index) { mutableStateOf("Tap the image to read a pixel") }
    var showHeader by remember { mutableStateOf(false) }
    val img = doc.image
    val tables = remember(doc) { doc.fits.tableHdus.map { it.index to it.name } }
    var lo by remember(doc.lo, doc.hdu?.index) { mutableFloatStateOf(doc.lo) }
    var hi by remember(doc.hi, doc.hdu?.index) { mutableFloatStateOf(doc.hi) }

    Dialog(onDismissRequest = { vm.closeFits() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(doc.name, color = TextPrimary, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    TextButton(onClick = { showHeader = true }, enabled = doc.hdu != null) { Text("Header") }
                    IconButton(onClick = { vm.closeFits() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }) {
                    val bmp = doc.bitmap
                    if (bmp != null && img != null) {
                        val ib = remember(bmp) { bmp.asImageBitmap() }
                        Canvas(
                            Modifier.fillMaxSize()
                                .pointerInput(bmp) {
                                    detectTransformGestures { centroid, pan, zoom, _ ->
                                        val ns = (scale * zoom).coerceIn(0.2f, 60f)
                                        val k = ns / scale
                                        offset = (offset - centroid) * k + centroid + pan
                                        scale = ns
                                    }
                                }
                                .pointerInput(bmp, img) {
                                    detectTapGestures { p ->
                                        val fit = minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())
                                        val s = fit * scale
                                        val x0 = (box.width - bmp.width * s) / 2f + offset.x
                                        val y0 = (box.height - bmp.height * s) / 2f + offset.y
                                        val bx = ((p.x - x0) / s).toInt(); val by = ((p.y - y0) / s).toInt()
                                        if (bx in 0 until bmp.width && by in 0 until bmp.height) {
                                            val v = img.values[(bmp.height - 1 - by) * img.width + bx]
                                            val fx = bx * img.step + (img.step + 1) / 2.0
                                            val fy = img.origHeight - (by * img.step) - img.step / 2.0
                                            val sky = img.wcs?.pixelToSky(fx, fy)
                                            readout = "x=%.0f y=%.0f  value=%s".format(fx, fy, if (v.isNaN()) "NaN" else "%.5g".format(v)) +
                                                (sky?.let { "\nRA ${AstroMath.formatRa(it[0])}  Dec ${AstroMath.formatDec(it[1])}" } ?: "")
                                        }
                                    }
                                },
                        ) {
                            val fit = minOf(size.width / bmp.width, size.height / bmp.height)
                            val s = fit * scale
                            val w = (bmp.width * s).toInt().coerceAtLeast(1); val h = (bmp.height * s).toInt().coerceAtLeast(1)
                            drawImage(
                                ib, dstOffset = IntOffset(((size.width - w) / 2f + offset.x).toInt(), ((size.height - h) / 2f + offset.y).toInt()),
                                dstSize = IntSize(w, h), filterQuality = if (s > 3f) FilterQuality.None else FilterQuality.Medium,
                            )
                        }
                    } else {
                        Text(doc.note ?: "No image in this HDU", color = TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                    }
                }
                Text(readout, color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
                    val hdus = remember(doc) { doc.fits.imageHdus }
                    if (hdus.size > 1 || tables.isNotEmpty()) {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            hdus.forEach { h -> FilterChip(selected = h.index == doc.hdu?.index, onClick = { vm.setFitsHdu(h.index) }, label = { Text("Image: ${h.name}") }) }
                            tables.forEach { (i, n) ->
                                AssistChip(onClick = { vm.loadFitsTable(doc.name, doc.fits, i) }, label = { Text("Table: $n → sky") })
                                if (com.kroryan.astrovia.data.fits.LightCurves.detect(com.kroryan.astrovia.data.fits.LightCurves.columnsOf(doc.fits, doc.fits.hdus[i])) != null)
                                    AssistChip(onClick = { vm.plotFitsLightCurve(i) }, label = { Text("Plot light curve: $n") })
                            }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Stretch.entries.forEach { st -> FilterChip(selected = st == doc.stretch, onClick = { vm.setFitsStyle(st, doc.colorMap, lo, hi) }, label = { Text(st.label) }) }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorMap.entries.forEach { cm -> FilterChip(selected = cm == doc.colorMap, onClick = { vm.setFitsStyle(doc.stretch, cm, lo, hi) }, label = { Text(cm.label) }) }
                    }
                    if (img != null) {
                        Text("Black ${"%.4g".format(lo)}   White ${"%.4g".format(hi)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        RangeSlider(
                            value = lo..hi, onValueChange = { lo = it.start; hi = it.endInclusive },
                            valueRange = img.min..maxOf(img.max, img.min + 1e-6f),
                            onValueChangeFinished = { vm.setFitsStyle(doc.stretch, doc.colorMap, lo, hi) },
                        )
                        Text(buildString {
                            append("${img.origWidth} × ${img.origHeight} px")
                            if (img.step > 1) append(" (shown at 1/${img.step})")
                            append(img.wcs?.let { " · WCS ${it.projection}" } ?: " · no sky coordinates")
                        }, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { vm.showFitsOnSky() }, enabled = img.wcs != null, modifier = Modifier.padding(vertical = 6.dp)) { Text("Show on the sky") }
                    }
                }
            }
        }
    }
    if (showHeader && doc.hdu != null) {
        AlertDialog(
            onDismissRequest = { showHeader = false },
            confirmButton = { TextButton(onClick = { showHeader = false }) { Text("Close") } },
            title = { Text("Header: ${doc.hdu.name}") },
            text = {
                LazyColumn {
                    items(doc.hdu.header.lines()) { l -> Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
}




