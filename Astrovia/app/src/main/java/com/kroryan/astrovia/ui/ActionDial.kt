package com.kroryan.astrovia.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.ui.theme.SkyPanel
import com.kroryan.astrovia.ui.theme.TextPrimary

/** One entry of the [ActionDial]. */
class DialAction(
    val icon: ImageVector,
    val label: String,
    val highlighted: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A single floating button that unfolds into the sky tools (capture, measure, marker, catalogue
 * visibility...). It replaces the loose column of 3-6 buttons that used to cover the picture.
 */
@Composable
fun ActionDial(actions: List<DialAction>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = modifier) {
        AnimatedVisibility(
            visible = open,
            enter = fadeIn() + slideInVertically { it / 4 },
            exit = fadeOut() + slideOutVertically { it / 4 },
        ) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (a in actions) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = SkyPanel, shape = RoundedCornerShape(12.dp)) {
                            Text(
                                a.label, color = TextPrimary, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        SmallFloatingActionButton(
                            onClick = {
                                open = false
                                a.onClick()
                            },
                            containerColor = if (a.highlighted) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary,
                        ) {
                            Icon(a.icon, contentDescription = a.label, tint = if (a.highlighted) Color.Black else TextPrimary)
                        }
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { open = !open },
            containerColor = MaterialTheme.colorScheme.primary,
        ) {
            Icon(if (open) Icons.Filled.Close else Icons.Filled.MoreVert, contentDescription = if (open) "Close tools" else "Tools", tint = TextPrimary)
        }
    }
}

/** Compass: the arrow always points to celestial north; tapping it turns the picture north-up. */
@Composable
fun CompassButton(northAngleDeg: Double, onClick: () -> Unit, modifier: Modifier = Modifier) {
    SmallFloatingActionButton(onClick = onClick, containerColor = MaterialTheme.colorScheme.primary, modifier = modifier) {
        Icon(
            Icons.Filled.Navigation,
            contentDescription = "North (tap to align)",
            tint = TextPrimary,
            modifier = Modifier.rotate(northAngleDeg.toFloat()),
        )
    }
}

/** The tools in the order the user asked for: capture first, then the old three, then the contextual ones. */
fun skyDialActions(
    onCapture: () -> Unit,
    measureMode: Boolean,
    onMeasure: () -> Unit,
    onMarker: () -> Unit,
    catalogVisible: Boolean,
    onToggleCatalog: () -> Unit,
    onReloadCatalog: (() -> Unit)?,
    onClearCatalogs: (() -> Unit)? = null,
    onClearMarkers: (() -> Unit)?,
    onClearMeasurements: (() -> Unit)?,
    onOpenFile: (() -> Unit)? = null,
    onClearImage: (() -> Unit)? = null,
): List<DialAction> = buildList {
    add(DialAction(Icons.Filled.PhotoCamera, "Capture the sky", onClick = onCapture))
    onOpenFile?.let { add(DialAction(Icons.Filled.FolderOpen, "Open file (FITS, VOTable, CSV)", onClick = it)) }
    onClearImage?.let { add(DialAction(Icons.Filled.Clear, "Remove FITS image", onClick = it)) }
    add(DialAction(Icons.Filled.Straighten, "Measure", highlighted = measureMode, onClick = onMeasure))
    add(DialAction(Icons.Filled.Add, "Marker", onClick = onMarker))
    add(
        DialAction(
            if (catalogVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
            if (catalogVisible) "Hide catalogue" else "Show catalogue",
            highlighted = !catalogVisible, onClick = onToggleCatalog,
        ),
    )
    onReloadCatalog?.let { add(DialAction(Icons.Filled.Refresh, "Reload catalogue", onClick = it)) }
    onClearCatalogs?.let { add(DialAction(Icons.Filled.Delete, "Remove catalogues", onClick = it)) }
    onClearMarkers?.let { add(DialAction(Icons.Filled.Delete, "Clear markers", onClick = it)) }
    onClearMeasurements?.let { add(DialAction(Icons.Filled.Clear, "Clear measurements", onClick = it)) }
}
