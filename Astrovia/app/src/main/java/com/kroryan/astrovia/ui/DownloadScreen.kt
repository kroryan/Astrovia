@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.data.hips.HiPSDownloader
import com.kroryan.astrovia.data.hips.HiPSPropertiesParser
import com.kroryan.astrovia.data.hips.SkyRegion
import com.kroryan.astrovia.data.hips.HiPSSurvey
import com.kroryan.astrovia.data.vizier.CatalogDownloader
import com.kroryan.astrovia.model.SkyPosition
import com.kroryan.astrovia.data.vizier.VizieRService

/**
 * Returns a function that runs a download-starting action, first asking (once) for the notification
 * permission on Android 13+. The downloads keep running in the background either way; the permission
 * only decides whether their progress notification is visible. The action runs after the answer.
 */
@Composable
fun rememberStartWithNotifications(): (() -> Unit) -> Unit {
    val ctx = LocalContext.current
    var asked by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val action = pending
        pending = null
        action?.invoke()
    }
    return { action ->
        val needs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs) {
            asked = true
            pending = action
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            action()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    downloader: HiPSDownloader,
    catalogDownloader: CatalogDownloader,
    currentSurvey: HiPSSurvey,
    cameraCenter: SkyPosition,
    cameraFovDeg: Double = 10.0,
    onDismiss: () -> Unit,
    onAddCustomDir: (String) -> Unit = {},
    onShowCatalogFile: (String) -> Unit = {},
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Offline Downloader",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
            }
        }
        
        Text(
            "Downloads are saved on this device. Open the menu (top left) and your HiPS appear in the list with \"(offline)\" after the name: type \"offline\" in the search box to find them. Catalogues: use \"Show it on the sky\" when the download ends.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(12.dp))

        TabRow(selectedTabIndex = selectedTabIndex) {
            Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("HiPS Tiles") })
            Tab(selected = selectedTabIndex == 1, onClick = { selectedTabIndex = 1 }, text = { Text("Catalogs") })
        }
        Spacer(modifier = Modifier.height(16.dp))

        if (selectedTabIndex == 0) {
            HiPSTabContent(downloader, currentSurvey, cameraCenter, cameraFovDeg, onDismiss, onAddCustomDir)
        } else {
            CatalogTabContent(catalogDownloader, cameraCenter, onDismiss, onShowCatalogFile)
        }
    }
}

@Composable
fun HiPSTabContent(
    downloader: HiPSDownloader,
    currentSurvey: HiPSSurvey,
    cameraCenter: SkyPosition,
    cameraFovDeg: Double,
    onDismiss: () -> Unit,
    onAddCustomDir: (String) -> Unit
) {
    val state by downloader.getState(currentSurvey.id).collectAsState()
    var selectedOrder by remember { mutableFloatStateOf(6f) }
    // Only the area in view by default: the whole sky to a useful order is hundreds of GB.
    var onlyVisibleArea by remember { mutableStateOf(true) }
    var maxThreads by remember { mutableFloatStateOf(8f) }
    var fastCheck by remember { mutableStateOf(true) }
    var customPath by remember { mutableStateOf("") }
    val startWithNotifications = rememberStartWithNotifications()

    if (state.isRunning) {
        Text("Downloading: ${state.datasetName}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        
        Text("Tiles scanned: ${state.tilesScanned} / ${state.totalTilesQueued}")
        Text("Tiles ready (downloaded + skipped): ${state.tilesDownloaded}")
        Text("Data downloaded: ${formatBytes(state.bytesDownloaded)}")
        Text("Active threads: ${state.activeThreads}")
        if (state.tilesNotFound > 0) Text("Outside the survey coverage: ${state.tilesNotFound}")
        if (state.tilesFailed > 0) Text("Failed (will be retried on the next run): ${state.tilesFailed}", color = MaterialTheme.colorScheme.error)
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { downloader.stopDownload(currentSurvey.id) },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Download")
        }
    } else {
        Text("Target Dataset: ${currentSurvey.title}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        state.message?.let {
            Text(it, color = if (state.incomplete || it.startsWith("Needs") || it.startsWith("About") || it.startsWith("Cannot") || it.startsWith("Could not")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.height(8.dp))
        }
        Text("Select Maximum Order (Depth): ${selectedOrder.toInt()}")
        Slider(
            value = selectedOrder,
            onValueChange = { selectedOrder = it },
            valueRange = 0f..14f,
            steps = 13,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = onlyVisibleArea, onCheckedChange = { onlyVisibleArea = it })
            Text("Only the area in view (${"%.1f".format(visibleRadius(cameraFovDeg))}\u00b0 around the centre)")
        }
        val ext = HiPSPropertiesParser.pickFormat(currentSurvey.formats.ifBlank { "jpeg" }).let { if (it == "jpeg") "jpg" else it }
        val region = if (onlyVisibleArea) SkyRegion(cameraCenter.raDeg, cameraCenter.decDeg, visibleRadius(cameraFovDeg)) else null
        val est = remember(selectedOrder, onlyVisibleArea, cameraCenter, cameraFovDeg, ext) {
            HiPSDownloader.estimate(selectedOrder.toInt(), region, ext)
        }
        val free = remember(selectedOrder) { HiPSDownloader.freeBytes(downloader.defaultDir(currentSurvey)) }
        val tooBig = est.bytes > free * 0.9 || est.tiles > HiPSDownloader.MAX_TILES_PER_RUN
        Text(
            "About ${est.tiles} tiles, ${HiPSDownloader.human(est.bytes)} (${HiPSDownloader.human(free)} free)",
            style = MaterialTheme.typography.bodyMedium,
            color = if (tooBig) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            "Higher order means more detail, but exponentially more files. Limiting it to the area in view lets you go much deeper.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Max Threads: ${maxThreads.toInt()}")
        Slider(
            value = maxThreads,
            onValueChange = { maxThreads = it },
            valueRange = 1f..16f,
            steps = 14,
            modifier = Modifier.fillMaxWidth()
        )
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = fastCheck, onCheckedChange = { fastCheck = it })
            Text("Fast Check (skip existing files)")
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = customPath,
            onValueChange = { customPath = it },
            label = { Text("Custom Output/Input Directory (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Button(
                onClick = {
                    if (customPath.isNotBlank()) onAddCustomDir(customPath)
                    onDismiss()
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Add Folder to Catalog")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { 
                    if (customPath.isNotBlank()) onAddCustomDir(customPath)
                    startWithNotifications { downloader.startDownload(currentSurvey, selectedOrder.toInt(), fastCheck, maxThreads.toInt(), customPath.ifBlank { null }, region) }
                },
                enabled = !tooBig,
                modifier = Modifier.weight(1f)
            ) {
                Text("Start Download")
            }
        }
    }
}

@Composable
fun CatalogTabContent(
    catalogDownloader: CatalogDownloader,
    cameraCenter: SkyPosition,
    onDismiss: () -> Unit = {},
    onShowFile: (String) -> Unit = {},
) {
    val state by catalogDownloader.state.collectAsState()
    var raText by remember { mutableStateOf(cameraCenter.raDeg.toString()) }
    var decText by remember { mutableStateOf(cameraCenter.decDeg.toString()) }
    var radiusText by remember { mutableStateOf("15.0") } // arcmin
    var allSky by remember { mutableStateOf(false) }
    
    val catalogs = remember { VizieRService().catalogs }
    var selectedCatalogIndex by remember { mutableIntStateOf(0) }
    val startWithNotifications = rememberStartWithNotifications()

    if (state.isRunning) {
        Text("Downloading: ${state.catalogName}", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text("Data downloaded: ${formatBytes(state.bytesDownloaded)}")
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { catalogDownloader.stopDownload() },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) {
            Text("Stop Download")
        }
    } else {
        if (state.isSuccess) {
            Text("Download Complete!", color = MaterialTheme.colorScheme.primary)
            state.warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.savedPath?.let { path ->
                Text(path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { onShowFile(path); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Show it on the sky")
                }
            }
        }
        if (state.errorMessage != null) {
            Text("Error: ${state.errorMessage}", color = MaterialTheme.colorScheme.error)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = allSky, onCheckedChange = { allSky = it })
            Text("Download Full Catalog (All-Sky)")
        }
        if (allSky) {
            Text(
                "Full catalogues can be gigabytes (Gaia has billions of rows). The download stops by itself if the storage runs low.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        
        OutlinedTextField(
            value = raText,
            onValueChange = { raText = it },
            label = { Text("RA (Degrees)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !allSky
        )
        OutlinedTextField(
            value = decText,
            onValueChange = { decText = it },
            label = { Text("Dec (Degrees)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !allSky
        )
        OutlinedTextField(
            value = radiusText,
            onValueChange = { radiusText = it },
            label = { Text("Radius (Arcmin)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !allSky
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        Text("Select Catalog:")
        catalogs.forEachIndexed { index, cat ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = index == selectedCatalogIndex,
                    onClick = { selectedCatalogIndex = index }
                )
                Text(cat.name)
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { startWithNotifications {
                val r = raText.toDoubleOrNull() ?: 0.0
                val d = decText.toDoubleOrNull() ?: 0.0
                val rad = radiusText.toDoubleOrNull() ?: 0.0
                val cat = catalogs[selectedCatalogIndex]
                if (cat.id.uppercase() == "SIMBAD") {
                    if (allSky) {
                        // Simbad doesn't easily support unbounded queries without criteria
                        catalogDownloader.startSimbadDownload(r, d, 999999.0)
                    } else {
                        catalogDownloader.startSimbadDownload(r, d, rad)
                    }
                } else {
                    catalogDownloader.startVizieRDownload(cat.id, r, d, rad, allSky)
                }
            } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Start Catalog Download")
        }
    }
}

/** Radius (deg) of the circle that covers the screen: half the vertical FoV with margin for the corners. */
private fun visibleRadius(fovDeg: Double): Double = (fovDeg / 2.0 * 1.25).coerceIn(0.01, 90.0)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(java.util.Locale.getDefault(), "%.1f MB", mb)
    val gb = mb / 1024.0
    return String.format(java.util.Locale.getDefault(), "%.2f GB", gb)
}
