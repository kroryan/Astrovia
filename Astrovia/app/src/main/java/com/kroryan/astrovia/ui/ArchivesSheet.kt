@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.SkyUiState
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.data.archive.ArchiveHit
import com.kroryan.astrovia.data.archive.ArchiveKind
import com.kroryan.astrovia.sky.Visibility
import com.kroryan.astrovia.sky.VisibilityConstraint
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val dayFmt = DateTimeFormatter.ofPattern("d MMM yyyy").withZone(ZoneOffset.UTC)

private fun jdToDate(jd: Double): String = dayFmt.format(Instant.ofEpochMilli(((jd - 2440587.5) * 86_400_000.0).toLong()))

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun shareText(ctx: Context, text: String, title: String) {
    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { ctx.startActivity(Intent.createChooser(i, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** Multi-archive search, results list and science tools (visibility, targets, publications, sessions). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchivesSheet(vm: SkyViewModel, state: SkyUiState, initialTab: Int = 0, initialSection: Int = 0, onDismiss: () -> Unit) {
    var tab by remember { mutableIntStateOf(initialTab) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            Text("Archives", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp))
            if (state.archiveLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
            TabRow(selectedTabIndex = tab) {
                listOf("Search", "Results (${state.archiveHits.size})", "Tools").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> SourcesTab(vm, onSearched = { tab = 1 })
                1 -> ResultsTab(vm, state, onPicked = onDismiss)
                else -> ToolsTab(vm, state, initialSection)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesTab(vm: SkyViewModel, onSearched: () -> Unit) {
    val sources = remember { vm.archives.sources }
    var kind by remember { mutableStateOf<ArchiveKind?>(null) }
    var filter by remember { mutableStateOf("") }
    val shown = remember(kind, filter, sources) {
        sources.filter { s ->
            (kind == null || s.kind == kind) &&
                (filter.isBlank() || s.label.contains(filter, true) || s.mission.contains(filter, true) || s.band.contains(filter, true))
        }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Text("Searches the field you are looking at.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All") })
            ArchiveKind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        OutlinedTextField(
            value = filter, onValueChange = { filter = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Filter: mission, band (IR, X-ray...)") },
        )
        LazyColumn(Modifier.weight(1f, fill = false)) {
            if (kind == null || kind == ArchiveKind.SOLAR_SYSTEM) {
                item {
                    ListItem(
                        headlineContent = { Text("Solar-system objects in the field (now)") },
                        supportingContent = { Text("Asteroids, comets, planets · IMCCE SkyBoT") },
                        modifier = Modifier.clickable { vm.searchSolarSystem(); onSearched() },
                    )
                }
            }
            items(shown, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.label) },
                    supportingContent = { Text("${s.kind.label} · ${s.mission} · ${s.band}") },
                    modifier = Modifier.clickable { vm.searchArchive(s); onSearched() },
                )
            }
        }
    }
}

@Composable
private fun ResultsTab(vm: SkyViewModel, state: SkyUiState, onPicked: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(state.archiveLabel ?: "No search yet", Modifier.weight(1f), color = TextPrimary)
            TextButton(onClick = { vm.toggleArchiveVisibility() }, enabled = state.archiveHits.isNotEmpty()) {
                Text(if (state.archiveVisible) "Hide" else "Show")
            }
            TextButton(onClick = { vm.clearArchive() }, enabled = state.archiveHits.isNotEmpty()) { Text("Clear") }
        }
        LazyColumn {
            items(state.archiveHits) { h ->
                ListItem(
                    headlineContent = { Text(h.title, maxLines = 1) },
                    supportingContent = { Text(hitSubtitle(h), maxLines = 2, color = TextSecondary) },
                    modifier = Modifier.clickable { vm.selectHit(h); onPicked() },
                )
            }
        }
    }
}

private fun hitSubtitle(h: ArchiveHit): String {
    val keys = listOf("instrument", "instrument_name", "filter", "start_time", "t_min", "obs_collection", "class", "Class", "V mag", "disc_year", "phot_g_mean_mag")
    val extra = keys.mapNotNull { k -> h.fields.entries.firstOrNull { it.key.equals(k, true) }?.value?.takeIf { it.isNotBlank() }?.take(40) }.take(3)
    return (listOf(h.id.takeIf { it != h.title }) + extra).filterNotNull().joinToString(" · ").ifEmpty { "%.4f, %+.4f".format(h.raDeg, h.decDeg) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToolsTab(vm: SkyViewModel, state: SkyUiState, initialSection: Int) {
    val ctx = LocalContext.current
    var section by remember { mutableIntStateOf(initialSection) }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Visibility", "Targets", "Publications", "Session").forEachIndexed { i, t ->
                FilterChip(selected = section == i, onClick = { section = i }, label = { Text(t) })
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (section) {
                0 -> VisibilityTool(state)
                1 -> TargetsTool(vm, state)
                2 -> PublicationsTool(vm, state, ctx)
                else -> SessionTool(vm, ctx)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VisibilityTool(state: SkyUiState) {
    var idx by remember { mutableIntStateOf(0) }
    val c = state.camera.center
    val constraint = VisibilityConstraint.presets[idx]
    val start = remember { Visibility.julianDate(System.currentTimeMillis()) }
    val windows = remember(c.raDeg, c.decDeg, idx) { Visibility.windows(c.raDeg, c.decDeg, constraint, start) }
    Text("When can the centre of the view (RA ${"%.3f".format(c.raDeg)}°, Dec ${"%+.3f".format(c.decDeg)}°) be observed in the next 365 days?", color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VisibilityConstraint.presets.forEachIndexed { i, p -> FilterChip(selected = idx == i, onClick = { idx = i }, label = { Text(p.name) }) }
    }
    Text("Sun angle now: %.1f°".format(Visibility.elongation(c.raDeg, c.decDeg, start)), color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
    if (windows.isEmpty()) Text("Not observable in the next year with these limits.", color = TextPrimary)
    windows.forEach { w -> Text("${jdToDate(w.startJd)} → ${jdToDate(w.endJd)}  (${w.days.toInt()} days)", color = TextPrimary) }
    Text("Approximate: Sun-angle limit only; real schedules also depend on roll, moon, zodiacal light and other constraints.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun TargetsTool(vm: SkyViewModel, state: SkyUiState) {
    var text by remember { mutableStateOf("") }
    OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3,
        label = { Text("Object names, one per line (SIMBAD)") })
    Row(Modifier.padding(vertical = 4.dp)) {
        Button(onClick = { vm.addTargets(text); text = "" }, enabled = text.isNotBlank() && !state.archiveLoading) { Text("Add to list") }
    }
    state.targets.forEach { t ->
        ListItem(
            headlineContent = { Text(t.name) },
            supportingContent = { Text("%.4f, %+.4f".format(t.raDeg, t.decDeg), color = TextSecondary) },
            trailingContent = { IconButton(onClick = { vm.removeTarget(t) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") } },
            modifier = Modifier.clickable { vm.goToTarget(t) },
        )
    }
    if (state.targets.isEmpty()) Text("Your target list is empty. Tap a target to fly to it.", color = TextSecondary)
}

@Composable
private fun PublicationsTool(vm: SkyViewModel, state: SkyUiState, ctx: Context) {
    var name by remember { mutableStateOf(state.selected?.mainId ?: state.selectedHit?.title ?: "") }
    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("SIMBAD object, e.g. M 42") })
    Button(onClick = { vm.loadPublications(name) }, enabled = name.isNotBlank() && !state.archiveLoading, modifier = Modifier.padding(vertical = 4.dp)) { Text("Find publications") }
    val pubs = state.publications
    if (pubs != null && pubs.isEmpty()) Text("No references found for ${state.publicationsFor}.", color = TextSecondary)
    pubs?.forEach { p ->
        ListItem(
            headlineContent = { Text(p.title, maxLines = 3) },
            supportingContent = { Text("${p.year} · ${p.bibcode}", color = TextSecondary) },
            modifier = Modifier.clickable { openUrl(ctx, p.url) },
        )
    }
}

@Composable
private fun SessionTool(vm: SkyViewModel, ctx: Context) {
    var imported by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    Text("Share this view or keep a whole session (view, survey, markers, targets).", color = TextSecondary)
    Button(onClick = { shareText(ctx, vm.viewLink(), "Share view link") }, modifier = Modifier.padding(top = 8.dp)) { Text("Share view link") }
    OutlinedButton(onClick = { shareText(ctx, vm.exportSession(), "Export session") }, modifier = Modifier.padding(top = 4.dp)) { Text("Export session (JSON)") }
    OutlinedTextField(value = imported, onValueChange = { imported = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 3,
        label = { Text("Paste a session or an astrovia:// link") })
    Button(onClick = {
        val t = imported.trim()
        val ok = if (t.startsWith("astrovia://")) vm.openViewLink(Uri.parse(t)) else vm.importSession(t)
        msg = if (ok) "Session loaded" else "That text is not a valid Astrovia session or link"
    }, enabled = imported.isNotBlank(), modifier = Modifier.padding(top = 4.dp)) { Text("Load") }
    msg?.let { Text(it, color = TextPrimary, modifier = Modifier.padding(top = 4.dp)) }
}

/** Details of the selected archive result, with its links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HitSheet(hit: ArchiveHit, onCenter: () -> Unit, onPublications: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(hit.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text("${hit.source.label} · ${hit.source.kind.label}", color = TextSecondary)
            Text("RA ${com.kroryan.astrovia.sky.AstroMath.formatRa(hit.raDeg)}  Dec ${com.kroryan.astrovia.sky.AstroMath.formatDec(hit.decDeg)}", color = TextSecondary)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hit.link?.let { l -> Button(onClick = { openUrl(ctx, l) }) { Text(if (hit.source.kind == ArchiveKind.SOLAR_SYSTEM) "Open card" else "Open / download") } }
                OutlinedButton(onClick = onCenter) { Text("Center") }
                OutlinedButton(onClick = onPublications) { Text("Papers") }
            }
            hit.fields.entries.filter { it.key.lowercase() !in setOf("stc_s", "s_region", "footprint_fov", "polygon_fov") }.take(40).forEach { (k, v) ->
                Text("$k: ${v.take(160)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
