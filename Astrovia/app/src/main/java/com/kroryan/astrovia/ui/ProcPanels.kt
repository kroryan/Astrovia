@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cds.astrovia.imgproc.Ops
import cds.astrovia.imgproc.Stacker
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.model.FitsDocument
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

/** The processing tools. A tool is only offered when it makes sense for the open picture. */
internal enum class Tool(val label: String) {
    STRETCH("Stretch"), BACKGROUND("Background"), NOISE("Noise"), SHARPEN("Sharpen"), COSMETIC("Hot pixels"), STARS("Stars"),
    COLOR("Colour"), DEBAYER("Debayer"), GEOMETRY("Geometry"), STACK("Stack"),
}

@Composable
private fun Param(label: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row { Text(label, color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f)); Text(value, color = TextPrimary, style = MaterialTheme.typography.labelMedium) }
        content()
    }
}

/** Compact controls of the processing tools; one tool at a time, with a single Apply button. */
@Composable
internal fun ProcessPanel(vm: SkyViewModel, doc: FitsDocument, busy: String?, visible: () -> FloatArray?) {
    val proc = doc.proc
    val img = proc?.current
    val header = doc.hdu?.header
    val isColor = img?.isColor ?: ((header?.int("NAXIS3") ?: 1) >= 3)
    val bayer = img?.bayerPattern() ?: header?.str("BAYERPAT")?.trim()?.uppercase()
    val hasBayer = !isColor && bayer != null
    val tools = remember(isColor, hasBayer) { Tool.entries.filter { (it != Tool.COLOR || isColor) && (it != Tool.DEBAYER || hasBayer) } }
    var tool by remember { mutableStateOf(if (hasBayer) Tool.DEBAYER else Tool.STRETCH) }
    var showStack by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tools.forEach { t -> FilterChip(selected = tool == t, onClick = { if (t == Tool.STACK) showStack = true else tool = t }, label = { Text(t.label) }) }
        }
        val enabled = busy == null
        when (tool) {
            Tool.STRETCH -> {
                var target by remember { mutableFloatStateOf(0.25f) }; var shadow by remember { mutableFloatStateOf(-2.8f) }; var linked by remember { mutableStateOf(true) }
                var strength by remember { mutableFloatStateOf(50f) }; var useAsinh by remember { mutableStateOf(false) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !useAsinh, onClick = { useAsinh = false }, label = { Text("Auto (midtones)") })
                    FilterChip(selected = useAsinh, onClick = { useAsinh = true }, label = { Text("Asinh") })
                }
                if (!useAsinh) {
                    Param("Brightness of the background", "%.2f".format(target)) { Slider(target, { target = it }, valueRange = 0.1f..0.5f) }
                    Param("Black point (sigmas below the sky)", "%.1f".format(shadow)) { Slider(shadow, { shadow = it }, valueRange = -5f..0f) }
                    if (isColor) Row(verticalAlignment = Alignment.CenterVertically) { Switch(linked, { linked = it }); Spacer(Modifier.width(8.dp)); Text("Same stretch on all colours", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                } else Param("Strength", strength.toInt().toString()) { Slider(strength, { strength = it }, valueRange = 2f..500f) }
                Button(onClick = { if (useAsinh) vm.procRun("Asinh stretch", true) { Ops.asinh(it, strength.toDouble(), 0.0) } else vm.procRun("Auto stretch", true) { Ops.autoStretch(it, target.toDouble(), shadow.toDouble(), linked) } }, enabled = enabled) { Text("Apply") }
                Text("Makes the faint signal visible. It changes the data: keep the original file to stack or measure.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Tool.BACKGROUND -> {
                var grid by remember { mutableFloatStateOf(8f) }; var order by remember { mutableFloatStateOf(2f) }
                Param("Sample boxes per side", grid.toInt().toString()) { Slider(grid, { grid = it }, valueRange = 4f..16f, steps = 11) }
                Param("Gradient complexity (polynomial order)", order.toInt().toString()) { Slider(order, { order = it }, valueRange = 1f..4f, steps = 2) }
                Button(onClick = { vm.procRun("Background removal") { Ops.removeBackground(it, grid.toInt(), order.toInt()) } }, enabled = enabled) { Text("Remove gradient") }
                Text("Models the sky background away from the stars and subtracts it (light pollution, vignetting).", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Tool.NOISE -> {
                var method by remember { mutableIntStateOf(0) }; var sigma by remember { mutableFloatStateOf(1.2f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Median 3×3", "Median 5×5", "Gaussian").forEachIndexed { i, l -> FilterChip(selected = method == i, onClick = { method = i }, label = { Text(l) }) } }
                if (method == 2) Param("Blur radius (pixels)", "%.1f".format(sigma)) { Slider(sigma, { sigma = it }, valueRange = 0.5f..4f) }
                Button(onClick = { when (method) { 0 -> vm.procRun("Median 3x3") { Ops.median(it, 1) }; 1 -> vm.procRun("Median 5x5") { Ops.median(it, 2) }; else -> vm.procRun("Gaussian blur") { Ops.gaussianBlur(it, sigma.toDouble()) } } }, enabled = enabled) { Text("Apply") }
            }
            Tool.SHARPEN -> {
                var method by remember { mutableIntStateOf(0) }; var sigma by remember { mutableFloatStateOf(1.5f) }; var amount by remember { mutableFloatStateOf(1f) }; var iters by remember { mutableFloatStateOf(15f) }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Unsharp mask", "Deconvolution").forEachIndexed { i, l -> FilterChip(selected = method == i, onClick = { method = i }, label = { Text(l) }) } }
                Param(if (method == 0) "Radius (pixels)" else "Star size, sigma (pixels)", "%.1f".format(sigma)) { Slider(sigma, { sigma = it }, valueRange = 0.8f..5f) }
                if (method == 0) Param("Amount", "%.1f".format(amount)) { Slider(amount, { amount = it }, valueRange = 0.2f..3f) }
                else Param("Iterations", iters.toInt().toString()) { Slider(iters, { iters = it }, valueRange = 5f..40f) }
                Button(onClick = { if (method == 0) vm.procRun("Unsharp mask") { Ops.unsharp(it, sigma.toDouble(), amount.toDouble()) } else vm.procRun("Deconvolution") { Ops.deconvolve(it, sigma.toDouble(), iters.toInt()) } }, enabled = enabled) { Text("Apply") }
                if (method == 1) Text("Works best on linear data (before stretching). Measure the star FWHM in the Stars tool: sigma ≈ FWHM / 2.35.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
            Tool.COSMETIC -> {
                var hot by remember { mutableFloatStateOf(8f) }; var cold by remember { mutableStateOf(false) }
                Param("Sensitivity (sigmas above the neighbours)", hot.toInt().toString()) { Slider(hot, { hot = it }, valueRange = 3f..20f) }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(cold, { cold = it }); Spacer(Modifier.width(8.dp)); Text("Also fix dead (cold) pixels", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Button(onClick = { vm.procRun("Hot pixels") { Ops.cosmetic(it, hot.toDouble(), if (cold) hot.toDouble() else 0.0, null) } }, enabled = enabled) { Text("Fix") }
            }
            Tool.STARS -> {
                var sens by remember { mutableFloatStateOf(6f) }
                Param("Sensitivity (sigmas above the sky)", sens.toInt().toString()) { Slider(sens, { sens = it }, valueRange = 3f..15f) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { vm.procMeasure(sens.toDouble()) }, enabled = enabled) { Text("Measure stars and noise") }
                    if (proc != null && proc.stars.isNotEmpty()) { Switch(proc.showStars, { vm.procShowStars(it) }); Text("Mark", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                }
                proc?.info?.let { Text(it, color = TextPrimary, style = MaterialTheme.typography.bodySmall) }
            }
            Tool.COLOR -> {
                var scnr by remember { mutableFloatStateOf(1f) }; var sat by remember { mutableFloatStateOf(0.3f) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.procRun("White balance") { Ops.whiteBalance(it) } }, enabled = enabled) { Text("White balance") }
                }
                Param("Remove green cast", "%.1f".format(scnr)) { Slider(scnr, { scnr = it }, valueRange = 0.1f..1f) }
                Button(onClick = { vm.procRun("Green cast removal") { Ops.scnr(it, scnr.toDouble()) } }, enabled = enabled) { Text("Apply green removal") }
                Param("Saturation", "%+.1f".format(sat)) { Slider(sat, { sat = it }, valueRange = -1f..1f) }
                Button(onClick = { vm.procRun("Saturation") { Ops.saturation(it, sat.toDouble()) } }, enabled = enabled) { Text("Apply saturation") }
            }
            Tool.DEBAYER -> {
                var pat by remember(bayer) { mutableStateOf(bayer ?: "RGGB") }
                Text("This picture comes from a colour camera (raw mosaic). Debayering turns it into a colour picture.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("RGGB", "BGGR", "GRBG", "GBRG").forEach { p -> FilterChip(selected = pat == p, onClick = { pat = p }, label = { Text(p) }) } }
                Button(onClick = { vm.procRun("Debayer $pat", true) { Ops.debayer(it, pat) } }, enabled = enabled) { Text("Debayer") }
            }
            Tool.GEOMETRY -> {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.procRun("Rotate left") { Ops.rotate90(it, false) } }, enabled = enabled) { Text("⟲ 90°") }
                    OutlinedButton(onClick = { vm.procRun("Rotate right") { Ops.rotate90(it, true) } }, enabled = enabled) { Text("⟳ 90°") }
                    OutlinedButton(onClick = { vm.procRun("Flip horizontally") { Ops.flip(it, true) } }, enabled = enabled) { Text("Flip ↔") }
                    OutlinedButton(onClick = { vm.procRun("Flip vertically") { Ops.flip(it, false) } }, enabled = enabled) { Text("Flip ↕") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { vm.procRun("Bin 2x2") { Ops.bin(it, 2) } }, enabled = enabled) { Text("Bin 2×2") }
                    OutlinedButton(onClick = { vm.procRun("Bin 3x3") { Ops.bin(it, 3) } }, enabled = enabled) { Text("Bin 3×3") }
                    OutlinedButton(onClick = { vm.procRun("Invert") { Ops.invert(it) } }, enabled = enabled) { Text("Invert") }
                    OutlinedButton(onClick = { val r = visible(); if (r != null) vm.procRun("Crop to view") { im -> Ops.crop(im, r[0].toInt(), (im.h - r[3]).toInt(), (r[2] - r[0]).toInt(), (r[3] - r[1]).toInt()) } }, enabled = enabled) { Text("Crop to view") }
                }
            }
            Tool.STACK -> {}
        }
        if (proc != null) Text("Steps: " + (proc.steps.takeIf { it.isNotEmpty() }?.joinToString(" → ") ?: "none yet"), color = TextSecondary, style = MaterialTheme.typography.labelSmall)
    }
    if (showStack) StackDialog(vm, doc, onDismiss = { showStack = false })
}

/** Stacking wizard: lights (required), darks, flats and bias (optional), then one button. */
@Composable
private fun StackDialog(vm: SkyViewModel, doc: FitsDocument, onDismiss: () -> Unit) {
    val lights = remember { mutableStateListOf<Uri>().also { l -> doc.file?.let { l.add(Uri.fromFile(it)) } } }
    val darks = remember { mutableStateListOf<Uri>() }; val flats = remember { mutableStateListOf<Uri>() }; val bias = remember { mutableStateListOf<Uri>() }
    var mode by remember { mutableIntStateOf(Stacker.SIGMA_CLIP) }; var register by remember { mutableStateOf(true) }
    val bayer = doc.hdu?.header?.str("BAYERPAT") != null && (doc.hdu.header.int("NAXIS3") ?: 1) < 3
    var debayer by remember { mutableStateOf(bayer) }
    var target by remember { mutableStateOf(lights) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> target.addAll(uris.filter { it !in target }) }
    fun add(l: SnapshotStateList<Uri>) { target = l; picker.launch(arrayOf("*/*")) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(16.dp)) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Stack pictures", style = MaterialTheme.typography.titleLarge)
                Text("Combine several exposures of the same field to reduce noise. Add the lights; darks, flats and bias are optional.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                for ((name, list) in listOf("Lights (at least 2)" to lights, "Darks" to darks, "Flats" to flats, "Bias" to bias)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name, color = TextPrimary); Text("${list.size} files", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                        if (list.isNotEmpty()) TextButton(onClick = { list.clear() }) { Text("Clear") }
                        OutlinedButton(onClick = { add(list) }) { Text("Add…") }
                    }
                }
                Text("Combine with", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Stacker.SIGMA_CLIP to "Sigma clipping", Stacker.MEDIAN to "Median", Stacker.AVERAGE to "Average").forEach { (m, l) -> FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(l) }) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(register, { register = it }); Spacer(Modifier.width(8.dp)); Text("Align the pictures on the stars", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                if (bayer) Row(verticalAlignment = Alignment.CenterVertically) { Switch(debayer, { debayer = it }); Spacer(Modifier.width(8.dp)); Text("Debayer (colour camera)", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.stackRun(SkyViewModel.StackRequest(lights.toList(), darks.toList(), flats.toList(), bias.toList(), mode, register, debayer)); onDismiss() }, enabled = lights.size >= 2) { Text("Stack") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
