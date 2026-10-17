@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.drawText
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
                listOf("Search", "Results (${state.visibleHits.size})", "Charts", "Overview", "Gallery", "Tools").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            when (tab) {
                0 -> SourcesTab(vm, state, onSearched = { tab = 1 })
                1 -> ResultsTab(vm, state, onPicked = onDismiss)
                2 -> ArchiveCharts(state.archiveHits, state.hitFilter, onFilter = { vm.setHitFilter(it) })
                3 -> FieldOverview(vm, state, onOpened = { tab = 1 })
                4 -> {
                    LaunchedEffect(Unit) { if (state.gallery == null && !state.archiveLoading) vm.loadGallery(state.galleryMission) }
                    GalleryTab(vm, state.gallery, state.archiveLoading && state.gallery == null, state.galleryMission, onMission = { vm.loadGallery(it) })
                }
                else -> ToolsTab(vm, state, initialSection)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourcesTab(vm: SkyViewModel, state: SkyUiState, onSearched: () -> Unit) {
    val sources = state.archiveSources
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
        state.hitFilter?.let { f ->
            AssistChip(onClick = { vm.setHitFilter(null) }, label = { Text("Showing: ${f.label}  ✕  (${state.visibleHits.size} of ${state.archiveHits.size})") })
        }
        LazyColumn {
            items(state.visibleHits) { h ->
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
            listOf("Visibility", "Plan", "Targets", "Publications", "Services", "Session").forEachIndexed { i, t ->
                FilterChip(selected = section == i, onClick = { section = i }, label = { Text(t) })
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (section) {
                0 -> VisibilityTool(state)
                1 -> PlanTool(vm, state)
                2 -> TargetsTool(vm, state)
                3 -> PublicationsTool(vm, state, ctx)
                4 -> ServicesTool(vm, state)
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
    VisibilityStrip(windows, start)
    windows.forEach { w -> Text("${jdToDate(w.startJd)} → ${jdToDate(w.endJd)}  (${w.days.toInt()} days)", color = TextPrimary) }
    Text("Approximate: Sun-angle limit only; real schedules also depend on roll, moon, zodiacal light and other constraints.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetsTool(vm: SkyViewModel, state: SkyUiState) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    val editable = state.currentList in state.lists
    Text("Lists of objects: your own lists, built-in lists and pictures. Tap an object to fly to it.", color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        state.lists.keys.forEach { n -> FilterChip(selected = n == state.currentList, onClick = { vm.selectList(n) }, label = { Text(n) }) }
        state.builtInLists.keys.filter { it !in state.lists }.forEach { n -> AssistChip(onClick = { vm.copyBuiltIn(n) }, label = { Text("+ $n") }) }
    }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("New list name") })
        TextButton(onClick = { vm.createList(newName); newName = "" }, enabled = newName.isNotBlank()) { Text("Create") }
        TextButton(onClick = { vm.deleteList(state.currentList) }, enabled = editable) { Text("Delete") }
    }
    if (state.gallery != null) {
        val label = when (state.galleryMission) { "webb" -> "Webb"; "euclid" -> "Euclid"; else -> "Hubble" }
        OutlinedButton(onClick = { vm.listFromGallery(label, state.gallery!!) }) { Text("Make a list of all $label pictures") }
    }
    if (editable) {
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, label = { Text("Object names, one per line (SIMBAD), or name,ra,dec") })
        Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.addTargets(text); text = "" }, enabled = text.isNotBlank() && !state.archiveLoading) { Text("Add") }
            OutlinedButton(onClick = { val c = state.camera.center; vm.addToCurrentList(com.kroryan.astrovia.model.Target("View %.3f %+.3f".format(c.raDeg, c.decDeg), c.raDeg, c.decDeg)) }) { Text("Add view centre") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { shareText(ctx, vm.exportCurrentList(true), "Export list (CSV)") }, enabled = state.targets.isNotEmpty()) { Text("Export CSV") }
        OutlinedButton(onClick = { shareText(ctx, vm.exportCurrentList(false), "Export list (JSON)") }, enabled = state.targets.isNotEmpty()) { Text("Export JSON") }
    }
    Text("${state.currentList}: ${state.targets.size} objects", color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
    state.targets.take(400).forEach { t ->
        ListItem(
            headlineContent = { Text(t.name) },
            supportingContent = { Text("%.4f, %+.4f".format(t.raDeg, t.decDeg), color = TextSecondary) },
            trailingContent = { if (editable) IconButton(onClick = { vm.removeTarget(t) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") } },
            modifier = Modifier.clickable { vm.goToTarget(t) },
        )
    }
    if (editable) {
        OutlinedTextField(value = importText, onValueChange = { importText = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, label = { Text("Paste a CSV or JSON list to import") })
        Button(onClick = { vm.addTargets(importText); importText = "" }, enabled = importText.isNotBlank() && !state.archiveLoading) { Text("Import") }
    }
}

@Composable
private fun PublicationsTool(vm: SkyViewModel, state: SkyUiState, ctx: Context) {
    var name by remember { mutableStateOf(state.selected?.mainId ?: state.selectedHit?.title ?: "") }
    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("SIMBAD object, e.g. M 42") })
    Button(onClick = { vm.loadPublications(name) }, enabled = name.isNotBlank() && !state.archiveLoading, modifier = Modifier.padding(vertical = 4.dp)) { Text("Find publications") }
    OutlinedButton(onClick = { vm.loadFieldObjects() }, enabled = !state.archiveLoading) { Text("Most-cited objects in this field") }
    state.fieldObjects?.let { objs ->
        if (objs.isEmpty()) Text("No referenced objects found in this field.", color = TextSecondary)
        objs.forEach { o ->
            ListItem(
                headlineContent = { Text(o.name) },
                supportingContent = { Text("${o.type} · ${o.papers} papers", color = TextSecondary) },
                modifier = Modifier.clickable { name = o.name; vm.loadPublications(o.name) },
            )
        }
    }
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
fun HitSheet(hit: ArchiveHit, onPlot: (() -> Unit)?, onImage: (() -> Unit)?, onCenter: () -> Unit, onPublications: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Text(hit.title, style = MaterialTheme.typography.titleLarge, color = TextPrimary)
            Text("${hit.source.label} · ${hit.source.kind.label}", color = TextSecondary)
            Text("RA ${com.kroryan.astrovia.sky.AstroMath.formatRa(hit.raDeg)}  Dec ${com.kroryan.astrovia.sky.AstroMath.formatDec(hit.decDeg)}", color = TextSecondary)
            Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                hit.link?.let { l -> Button(onClick = { openUrl(ctx, l) }) { Text(if (hit.source.kind == ArchiveKind.SOLAR_SYSTEM) "Open card" else "Open / download") } }
                onPlot?.let { Button(onClick = it) { Text("Plot light curve") } }
                onImage?.let { Button(onClick = it) { Text("View picture") } }
                OutlinedButton(onClick = onCenter) { Text("Center") }
                OutlinedButton(onClick = onPublications) { Text("Papers") }
            }
            hit.fields.entries.filter { it.key.lowercase() !in setOf("stc_s", "s_region", "footprint_fov", "polygon_fov") }.take(40).forEach { (k, v) ->
                Text("$k: ${v.take(160)}", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** A 12-month strip: highlighted days are inside an observable window; the white line is today. */
@Composable
private fun VisibilityStrip(windows: List<com.kroryan.astrovia.sky.VisibilityWindow>, startJd: Double) {
    val tm = androidx.compose.ui.text.rememberTextMeasurer()
    val style = androidx.compose.ui.text.TextStyle(color = TextSecondary, fontSize = 10.sp)
    val months = remember(startJd) {
        val d0 = Instant.ofEpochMilli(((startJd - 2440587.5) * 86_400_000.0).toLong()).atZone(ZoneOffset.UTC).toLocalDate()
        List(12) { d0.plusMonths(it.toLong()).month.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.ENGLISH) }
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(46.dp).padding(vertical = 6.dp)) {
        val w = size.width
        drawRect(Color(0x33808080), androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Size(w, 18.dp.toPx()))
        windows.forEach { win ->
            val x0 = ((win.startJd - startJd) / 365.0 * w).toFloat(); val x1 = ((win.endJd - startJd) / 365.0 * w).toFloat()
            drawRect(Color(0xFF22C55E), androidx.compose.ui.geometry.Offset(x0, 0f), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(2f), 18.dp.toPx()))
        }
        months.forEachIndexed { i, m -> drawText(tm.measure(m, style), topLeft = androidx.compose.ui.geometry.Offset(i * w / 12f + 2f, 20.dp.toPx())) }
        drawLine(Color.White, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, 18.dp.toPx()), strokeWidth = 3f)
    }
}

/** Observation planning: place an instrument field of view on the sky, rotate it, and see when it can be observed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanTool(vm: SkyViewModel, state: SkyUiState) {
    val plan = state.plan
    Text("Project a future observation: pick an instrument, place it where you are looking and rotate it.", color = TextSecondary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        com.kroryan.astrovia.sky.Planner.instruments.forEach { f ->
            FilterChip(selected = plan?.key == f.key, onClick = { vm.setPlan(if (plan?.key == f.key) null else f.key) }, label = { Text(f.label) })
        }
    }
    if (plan != null) {
        val f = com.kroryan.astrovia.sky.Planner.byKey(plan.key)
        Text("Pointing: RA ${"%.4f".format(plan.raDeg)}°  Dec ${"%+.4f".format(plan.decDeg)}°   PA ${plan.paDeg.toInt()}°", color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
        f?.note?.takeIf { it.isNotEmpty() }?.let { Text(it, color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        Slider(value = plan.paDeg.toFloat(), onValueChange = { vm.setPlanPa(it.toDouble()) }, valueRange = 0f..359f)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.placePlanHere() }) { Text("Place at view centre") }
            OutlinedButton(onClick = { vm.setPlan(null) }) { Text("Remove") }
        }
        val start = remember { Visibility.julianDate(System.currentTimeMillis()) }
        val c = VisibilityConstraint.presets[when (f?.mission) { "Euclid" -> 1; "JWST" -> 0; else -> 2 }]
        val windows = remember(plan.raDeg, plan.decDeg, c) { Visibility.windows(plan.raDeg, plan.decDeg, c, start) }
        Text("Observable (${c.name}) in the next year:", color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
        VisibilityStrip(windows, start)
        windows.forEach { w -> Text("${jdToDate(w.startJd)} → ${jdToDate(w.endJd)}  (${w.days.toInt()} days)", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
    }
    Text("Nominal field sizes (rounded). Real apertures have gaps and orientation limits that depend on the date: use the observatory planning tool for proposals.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
}

/** Add any TAP service (external data centre): list its tables, pick one, and it joins the archive list. */
@Composable
private fun ServicesTool(vm: SkyViewModel, state: SkyUiState) {
    var url by remember { mutableStateOf(state.customTap.ifEmpty { "https://" }) }
    var label by remember { mutableStateOf("") }
    Text("Add a table from any TAP service (ESO, CADC, NED, a university archive...). It is searched like the others and appears in Available data.", color = TextSecondary)
    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("TAP service URL") })
    Button(onClick = { vm.loadCustomTables(url.trim()) }, enabled = url.startsWith("http") && !state.archiveLoading, modifier = Modifier.padding(vertical = 4.dp)) { Text("List tables") }
    state.customTables?.let { tables ->
        OutlinedTextField(value = label, onValueChange = { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name (optional)") })
        Text("${tables.size} tables. Tap one to add it:", color = TextSecondary)
        tables.take(150).forEach { t ->
            ListItem(headlineContent = { Text(t, maxLines = 1) }, modifier = Modifier.clickable { vm.addCustomSource(state.customTap, t, label) })
        }
    }
    val custom = state.archiveSources.filter { it.id.startsWith("custom:") }
    if (custom.isNotEmpty()) Text("Your tables", color = TextPrimary, modifier = Modifier.padding(top = 8.dp))
    custom.forEach { c ->
        ListItem(
            headlineContent = { Text(c.label) }, supportingContent = { Text("${c.centre} · ${c.table}", color = TextSecondary) },
            trailingContent = { IconButton(onClick = { vm.removeCustomSource(c.id) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") } },
        )
    }
}

/**
 * What every archive has in the field you are looking at, as bars grouped by type. Tap a bar to open that archive's
 * results (footprints on the sky). This is the equivalent of the counters of the ESA Sky left panel.
 */
@Composable
fun FieldOverview(vm: SkyViewModel, state: SkyUiState, onOpened: () -> Unit) {
    val byId = remember(state.archiveSources) { state.archiveSources.associateBy { it.id } }
    Column(Modifier.padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Text("How many results each archive has in this field. Tap one to see them on the sky.", color = TextSecondary, modifier = Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            Button(onClick = { vm.scanField(false) }, enabled = state.scanProgress == null) { Text("Scan (ESA + MAST)") }
            OutlinedButton(onClick = { vm.scanField(true) }, enabled = state.scanProgress == null) { Text("Scan all archives") }
        }
        state.scanProgress?.let { (d, t) -> LinearProgressIndicator(progress = { if (t == 0) 0f else d.toFloat() / t }, modifier = Modifier.fillMaxWidth()); Text("$d / $t archives", color = TextSecondary, style = MaterialTheme.typography.bodySmall) }
        val rows = state.fieldCounts.filterValues { it > 0 }.entries.mapNotNull { (id, n) -> byId[id]?.let { it to n } }
        val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
        ArchiveKind.entries.forEach { k ->
            val g = rows.filter { it.first.kind == k }.sortedByDescending { it.second }
            if (g.isNotEmpty()) {
                Text("${k.label} (${g.sumOf { it.second }})", color = TextPrimary, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                g.forEach { (src, n) ->
                    Column(Modifier.fillMaxWidth().clickable { vm.searchArchive(src); onOpened() }.padding(vertical = 3.dp)) {
                        Row { Text(src.label, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1); Text(n.toString(), color = TextSecondary) }
                        Box(Modifier.fillMaxWidth(n.toFloat() / max).height(8.dp).background(Color(0xFF38BDF8)))
                    }
                }
            }
        }
        if (state.scanProgress == null && state.fieldCounts.isNotEmpty() && rows.isEmpty()) Text("Nothing found in this field. Zoom out or move the view.", color = TextSecondary, modifier = Modifier.padding(top = 12.dp))
        val failed = state.fieldCounts.count { it.value < 0 }
        if (failed > 0) Text("$failed archives did not answer.", color = TextSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
    }
}
