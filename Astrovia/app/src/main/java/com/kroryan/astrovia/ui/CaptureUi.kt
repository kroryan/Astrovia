package com.kroryan.astrovia.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.capture.CaptureFormat
import com.kroryan.astrovia.capture.CaptureOptions
import com.kroryan.astrovia.capture.CapturePhase
import com.kroryan.astrovia.capture.CaptureState
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

/**
 * Options for "Capture the sky". The picture is rendered off-screen from the survey tiles, so it
 * contains only the HiPS (no buttons, crosshair, panels or system bars) at up to 3x the screen
 * resolution.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureOptionsDialog(
    vm: SkyViewModel,
    hasCatalog: Boolean,
    hasMarkers: Boolean,
    gridOn: Boolean,
    locationLabel: String,
    onChooseFolder: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scales = remember { vm.captureScales() }
    var scale by remember { mutableStateOf(scales.last().coerceAtMost(3)) }
    var format by remember { mutableStateOf(CaptureFormat.PNG) }
    var grid by remember { mutableStateOf(false) }
    var catalog by remember { mutableStateOf(false) }
    var markers by remember { mutableStateOf(false) }
    var filters by remember { mutableStateOf(true) }
    val (w, h) = vm.captureSize(scale)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Capture the sky") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Saves only what the survey shows: no buttons, crosshair or panels.",
                    style = MaterialTheme.typography.bodySmall, color = TextSecondary,
                )
                Text("Resolution", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in scales) {
                        FilterChip(
                            selected = scale == s,
                            onClick = { scale = s },
                            label = { Text(if (s == 1) "Screen" else "${s}x") },
                        )
                    }
                }
                Text("$w x $h px", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                Spacer(Modifier.height(4.dp))
                Text("Format", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                for (f in CaptureFormat.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { format = f },
                    ) {
                        RadioButton(selected = format == f, onClick = { format = f })
                        Text(f.label, color = TextPrimary)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Save to", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(locationLabel, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onChooseFolder) { Text("Change") }
                }
                Spacer(Modifier.height(4.dp))
                Text("Also include", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                CheckRow("Brightness / contrast / invert settings", filters) { filters = it }
                CheckRow(if (gridOn) "Coordinate grid" else "Coordinate grid (off in Settings)", grid) { grid = it }
                if (hasCatalog) CheckRow("Catalogue sources", catalog) { catalog = it }
                if (hasMarkers) CheckRow("Markers and measurements", markers) { markers = it }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                vm.captureSky(
                    CaptureOptions(
                        scale = scale, format = format, includeGrid = grid, includeCatalog = catalog,
                        includeMarkers = markers, applyDisplayFilters = filters,
                    ),
                )
                onDismiss()
            }) { Text("Capture") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable { onChange(!checked) },
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.width(2.dp))
        Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Progress, destination picker (Android 7-9), result and error dialogs of a capture. */
@Composable
fun CaptureStatusDialogs(vm: SkyViewModel, state: CaptureState) {
    val ctx = LocalContext.current
    val pngPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/png")) { vm.saveCaptureTo(it) }
    val jpgPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { vm.saveCaptureTo(it) }

    LaunchedEffect(state) {
        if (state is CaptureState.AwaitingLocation) {
            (if (state.format == CaptureFormat.PNG) pngPicker else jpgPicker).launch(state.suggestedName)
        }
    }

    when (state) {
        CaptureState.Idle, is CaptureState.AwaitingLocation -> Unit
        is CaptureState.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Capturing the sky") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        when (state.phase) {
                            CapturePhase.PREPARING -> "Preparing..."
                            CapturePhase.DOWNLOADING -> "Downloading full-resolution tiles ${state.done}/${state.total}"
                            CapturePhase.FALLBACK -> "Filling missing tiles ${state.done}/${state.total}"
                            CapturePhase.ENCODING -> "Saving the image..."
                        },
                        color = TextPrimary,
                    )
                    if (state.total > 0 && state.phase != CapturePhase.ENCODING) {
                        LinearProgressIndicator(progress = { state.done.toFloat() / state.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.dismissCapture() }) { Text("Cancel") } },
        )
        is CaptureState.Saved -> AlertDialog(
            onDismissRequest = { vm.dismissCapture() },
            title = { Text("Sky saved") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${state.width} x ${state.height} px, ${humanSize(state.bytes)}", color = TextPrimary)
                    Text("Saved in ${state.location}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                    if (state.missingTiles > 0) {
                        Text(
                            "${state.missingTiles} tile(s) were not available at full resolution and were filled with a coarser one.",
                            color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = { share(ctx, state.uri) }) { Text("Share") }
                    TextButton(onClick = { open(ctx, state.uri) }) { Text("Open") }
                    TextButton(onClick = { vm.dismissCapture() }) { Text("Close") }
                }
            },
        )
        is CaptureState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissCapture() },
            title = { Text("Capture failed") },
            text = { Text(state.message, color = TextPrimary) },
            confirmButton = { TextButton(onClick = { vm.dismissCapture() }) { Text("Close") } },
        )
    }
}

private fun mimeOf(ctx: Context, uri: Uri): String = ctx.contentResolver.getType(uri) ?: "image/*"

private fun open(ctx: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(ctx, uri))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: ActivityNotFoundException) { /* no viewer installed */ }
}

private fun share(ctx: Context, uri: Uri) {
    val i = Intent(Intent.ACTION_SEND).setType(mimeOf(ctx, uri)).putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        ctx.startActivity(Intent.createChooser(i, "Share the sky").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) { /* nothing to share with */ }
}

private fun humanSize(b: Long): String = when {
    b >= 1L shl 20 -> String.format(java.util.Locale.getDefault(), "%.1f MB", b / 1048576.0)
    b >= 1L shl 10 -> String.format(java.util.Locale.getDefault(), "%.0f KB", b / 1024.0)
    else -> "$b B"
}
