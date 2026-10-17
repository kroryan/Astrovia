@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.data.fits.ColorMap
import com.kroryan.astrovia.data.fits.Stretch
import com.kroryan.astrovia.model.FitsDocument
import com.kroryan.astrovia.sky.AstroMath
import com.kroryan.astrovia.ui.theme.SkyPanel
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

private enum class Panel(val label: String, val icon: ImageVector) {
    DISPLAY("Display", Icons.Filled.Tune), PROCESS("Process", Icons.Filled.AutoFixHigh), INFO("Info", Icons.Filled.Info), SAVE("Save", Icons.Filled.Save)
}

/**
 * Full-screen FITS viewer. The picture takes all the room; a slim dock of four icons opens one panel at a time (Display,
 * Process, Info, Save) and tapping the same icon again folds it away.
 */
@Composable
fun FitsViewer(vm: SkyViewModel, doc: FitsDocument, busy: String?, stack: Pair<String, Float>?) {
    var scale by remember(doc.name) { mutableFloatStateOf(1f) }
    var offset by remember(doc.name) { mutableStateOf(Offset.Zero) }
    var placed by remember(doc.name, doc.hdu?.index) { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var readout by remember(doc.hdu?.index) { mutableStateOf<String?>(null) }
    var showHeader by remember { mutableStateOf(false) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    val img = doc.image
    val bmp = doc.bitmap
    val proc = doc.proc
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val fitsSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/fits")) { u -> if (u != null) vm.procExportFits(u) }
    val pngSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { u -> if (u != null) vm.procExportPng(u) }
    // the part of the picture on screen, in bitmap pixels (left, top, right, bottom): used by "Crop to view"
    val visible = remember(bmp, box, scale, offset) {
        {
            bmp?.let { b ->
                val fit = minOf(box.width / b.width.toFloat(), box.height / b.height.toFloat()); val s = fit * scale
                floatArrayOf(((0f - offset.x) / s).coerceIn(0f, b.width.toFloat()), ((0f - offset.y) / s).coerceIn(0f, b.height.toFloat()),
                    ((box.width - offset.x) / s).coerceIn(0f, b.width.toFloat()), ((box.height - offset.y) / s).coerceIn(0f, b.height.toFloat()))
            }
        }
    }

    Dialog(onDismissRequest = { if (panel != null) panel = null else vm.closeFits() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().onSizeChanged { box = it }) {
                // ---- the picture
                if (bmp != null && img != null) {
                    val ib = remember(bmp) { bmp.asImageBitmap() }
                    LaunchedEffect(bmp, box, placed) {
                        if (!placed && box.width > 0) {
                            val fit = minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())
                            offset = Offset((box.width - bmp.width * fit) / 2f, (box.height - bmp.height * fit) / 2f); scale = 1f; placed = true
                        }
                    }
                    Canvas(
                        Modifier.fillMaxSize()
                            .pointerInput(bmp) {
                                detectTransformGestures { centroid, pan, zoom, _ ->
                                    val ns = (scale * zoom).coerceIn(0.2f, 60f); val k = ns / scale
                                    offset = (offset - centroid) * k + centroid + pan; scale = ns
                                }
                            }
                            .pointerInput(bmp, img) {
                                detectTapGestures(onTap = { p ->
                                    val fit = minOf(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat()); val s = fit * scale
                                    val bx = ((p.x - offset.x) / s).toInt(); val by = ((p.y - offset.y) / s).toInt()
                                    if (bx in 0 until bmp.width && by in 0 until bmp.height) {
                                        val v = img.values[(bmp.height - 1 - by) * img.width + bx]
                                        val fx = bx * img.step + (img.step + 1) / 2.0; val fy = img.origHeight - (by * img.step) - img.step / 2.0
                                        val sky = img.wcs?.pixelToSky(fx, fy)
                                        readout = "x ${fx.toInt()}  y ${fy.toInt()}  value ${if (v.isNaN()) "NaN" else "%.5g".format(v)}" + (sky?.let { "\nRA ${AstroMath.formatRa(it[0])}  Dec ${AstroMath.formatDec(it[1])}" } ?: "")
                                    }
                                }, onDoubleTap = { scale = 1f; placed = false })
                            },
                    ) {
                        val fit = minOf(size.width / bmp.width, size.height / bmp.height); val s = fit * scale
                        val w = (bmp.width * s).toInt().coerceAtLeast(1); val h = (bmp.height * s).toInt().coerceAtLeast(1)
                        drawImage(ib, dstOffset = IntOffset(offset.x.toInt(), offset.y.toInt()), dstSize = IntSize(w, h), filterQuality = if (s > 3f) FilterQuality.None else FilterQuality.Medium)
                        if (proc != null && proc.showStars) for (st in proc.stars) {
                            val cx = offset.x + (st.x.toFloat() + 0.5f) * s; val cy = offset.y + (bmp.height - 1 - st.y.toFloat() + 0.5f) * s
                            if (cx < 0 || cy < 0 || cx > size.width || cy > size.height) continue
                            drawCircle(Color(0xFF22D3EE), (st.fwhm.toFloat() * 1.6f * s).coerceIn(6f, 60f), Offset(cx, cy), style = Stroke(width = 2f))
                        }
                    }
                } else {
                    Text(doc.note ?: "No image in this HDU", color = TextSecondary, modifier = Modifier.align(Alignment.Center).padding(24.dp))
                }

                // ---- top bar: small, translucent
                Row(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth().background2().statusBarsPadding().padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) {
                        Text(doc.name, color = TextPrimary, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                        Text(buildString {
                            img?.let { append("${it.origWidth} × ${it.origHeight}") ; if (proc?.current?.isColor == true) append(" · colour") }
                            img?.wcs?.let { append(" · WCS ${it.projection}") }
                            if (proc != null && proc.steps.isNotEmpty()) append(" · ${proc.steps.size} step${if (proc.steps.size == 1) "" else "s"}")
                        }, color = TextSecondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    }
                    if (proc != null) {
                        IconButton(onClick = { vm.procUndo() }, enabled = proc.canUndo && busy == null) { Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo", tint = if (proc.canUndo) Color.White else Color.Gray) }
                        IconButton(onClick = { vm.procRedo() }, enabled = proc.canRedo && busy == null) { Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo", tint = if (proc.canRedo) Color.White else Color.Gray) }
                        TextButton(onClick = { vm.procReset() }, enabled = proc.canUndo && busy == null) { Text("Reset") }
                    }
                    IconButton(onClick = { vm.closeFits() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                if (busy != null || stack != null) {
                    Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp).fillMaxWidth().padding(horizontal = 24.dp)) {
                        Surface(color = SkyPanel, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(stack?.first ?: "$busy…", color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
                                if (stack != null) { LinearProgressIndicator(progress = { stack.second }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)); TextButton(onClick = { vm.stackCancel() }) { Text("Cancel") } }
                                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
                            }
                        }
                    }
                }

                // ---- bottom: reading, folding panel, dock
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
                    readout?.let { r ->
                        Surface(color = Color(0xCC000000), shape = RoundedCornerShape(10.dp), modifier = Modifier.padding(start = 10.dp, bottom = 6.dp).clickable { readout = null }) {
                            Text(r, color = TextPrimary, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                        }
                    }
                    AnimatedVisibility(visible = panel != null, enter = expandVertically(), exit = shrinkVertically()) {
                        Surface(color = SkyPanel, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp), tonalElevation = 3.dp) {
                            Column(Modifier.fillMaxWidth().heightIn(max = (box.height * 0.46f).dp.coerceAtLeast(180.dp).coerceAtMost(420.dp)).verticalScroll(rememberScrollState())) {
                                when (panel) {
                                    Panel.DISPLAY -> DisplayPanel(vm, doc)
                                    Panel.PROCESS -> ProcessPanel(vm, doc, busy, visible)
                                    Panel.INFO -> InfoPanel(vm, doc) { showHeader = true }
                                    Panel.SAVE -> SavePanel(doc, onFits = { fitsSaver.launch(doc.name.substringBeforeLast('.') + "_processed.fits") }, onPng = { pngSaver.launch(doc.name.substringBeforeLast('.') + ".png") })
                                    null -> {}
                                }
                            }
                        }
                    }
                    Surface(color = SkyPanel) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                            Panel.entries.forEach { p ->
                                val sel = panel == p
                                Column(Modifier.clickable { panel = if (sel) null else p }.padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(p.icon, contentDescription = p.label, tint = if (sel) MaterialTheme.colorScheme.secondary else Color.White)
                                    Text(p.label, color = if (sel) MaterialTheme.colorScheme.secondary else TextSecondary, fontSize = 11.sp)
                                }
                            }
                            if (img?.wcs != null) Column(Modifier.clickable { vm.showFitsOnSky() }.padding(horizontal = 14.dp, vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Public, contentDescription = "Show on the sky", tint = Color.White)
                                Text("Sky", color = TextSecondary, fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        }
    }
    if (showHeader && doc.hdu != null) {
        AlertDialog(
            onDismissRequest = { showHeader = false }, confirmButton = { TextButton(onClick = { showHeader = false }) { Text("Close") } },
            title = { Text("Header: ${doc.hdu.name}") },
            text = { LazyColumn { items(doc.hdu.header.lines()) { l -> Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) } } },
        )
    }
}

private fun Modifier.background2(): Modifier = this.then(Modifier.background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))

@Composable
private fun DisplayPanel(vm: SkyViewModel, doc: FitsDocument) {
    val img = doc.image
    var lo by remember(doc.lo, doc.hdu?.index, doc.proc?.index) { mutableFloatStateOf(doc.lo) }
    var hi by remember(doc.hi, doc.hdu?.index, doc.proc?.index) { mutableFloatStateOf(doc.hi) }
    val isColor = doc.proc?.current?.isColor == true
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Stretch", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Stretch.entries.forEach { st -> FilterChip(selected = st == doc.stretch, onClick = { vm.setFitsStyle(st, doc.colorMap, lo, hi) }, label = { Text(st.label) }) }
        }
        if (!isColor) {
            Text("Colours", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ColorMap.entries.forEach { cm -> FilterChip(selected = cm == doc.colorMap, onClick = { vm.setFitsStyle(doc.stretch, cm, lo, hi) }, label = { Text(cm.label) }) }
            }
        }
        if (img != null) {
            Text("Black ${"%.4g".format(lo)}   ·   White ${"%.4g".format(hi)}", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            RangeSlider(value = lo..hi, onValueChange = { lo = it.start; hi = it.endInclusive }, valueRange = img.min..maxOf(img.max, img.min + 1e-6f),
                onValueChangeFinished = { vm.setFitsStyle(doc.stretch, doc.colorMap, lo, hi) })
        }
    }
}

@Composable
private fun InfoPanel(vm: SkyViewModel, doc: FitsDocument, onHeader: () -> Unit) {
    val img = doc.image
    val tables = remember(doc) { doc.fits.tableHdus.map { it.index to it.name } }
    val hdus = remember(doc) { doc.fits.imageHdus }
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        img?.let { Text("${it.origWidth} × ${it.origHeight} px" + (if (it.step > 1) " (shown at 1/${it.step})" else "") + (it.wcs?.let { w -> " · sky coordinates: ${w.projection}" } ?: " · no sky coordinates"), color = TextPrimary, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = onHeader, enabled = doc.hdu != null) { Text("FITS header") }
        if (hdus.size > 1) {
            Text("Images in this file", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { hdus.forEach { h -> FilterChip(selected = h.index == doc.hdu?.index, onClick = { vm.setFitsHdu(h.index) }, label = { Text(h.name) }) } }
        }
        if (tables.isNotEmpty()) {
            Text("Tables in this file", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tables.forEach { (i, n) ->
                    AssistChip(onClick = { vm.loadFitsTable(doc.name, doc.fits, i) }, label = { Text("$n → sky") })
                    if (com.kroryan.astrovia.data.fits.LightCurves.detect(com.kroryan.astrovia.data.fits.LightCurves.columnsOf(doc.fits, doc.fits.hdus[i])) != null)
                        AssistChip(onClick = { vm.plotFitsLightCurve(i) }, label = { Text("$n light curve") })
                }
            }
        }
        doc.proc?.info?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun SavePanel(doc: FitsDocument, onFits: () -> Unit, onPng: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onFits, enabled = doc.proc != null) { Text("Save processed data (FITS)") }
        OutlinedButton(onClick = onPng) { Text("Save what you see (PNG)") }
        Text(
            if (doc.proc == null) "Process the picture first to save it as FITS. The PNG keeps the current stretch and colours."
            else "FITS keeps the real values (32-bit float) and the sky coordinates. PNG is the picture as shown.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall,
        )
    }
}
