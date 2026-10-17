@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kroryan.astrovia.data.archive.ArchiveSource
import com.kroryan.astrovia.data.hips.HiPSBands
import com.kroryan.astrovia.data.hips.HiPSSurvey
import com.kroryan.astrovia.data.hips.Organization
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary

/** A leaf of the Available data tree: a sky survey (HiPS) or an archive/catalogue you can search in the view. */
class DataEntry(val folders: List<String>, val label: String, val survey: HiPSSurvey?, val source: ArchiveSource?, val band: String)

private class TreeRow(val depth: Int, val label: String, val path: String, val entry: DataEntry?, val count: Int, val open: Boolean)

private class Node(val name: String, val path: String) {
    val children = sortedMapOf<String, Node>(String.CASE_INSENSITIVE_ORDER)
    val leaves = ArrayList<DataEntry>()
    fun count(): Int = leaves.size + children.values.sumOf { it.count() }
}

/** Folder path of a survey for the chosen organisation. */
internal fun skyFolders(s: HiPSSurvey, org: Organization): List<String> {
    val cat = s.category.split('/').map { it.trim() }.filter { it.isNotEmpty() }
    if (s.id.endsWith("_local")) return listOf("Offline (downloaded)")
    return when (org) {
        Organization.DEFAULT -> cat.ifEmpty { listOf("Other") }
        Organization.WAVELENGTH -> listOf(s.band, "Skies (HiPS)") + cat.drop(2).take(1)
        Organization.MISSION -> listOf(cat.getOrNull(2) ?: cat.getOrNull(1) ?: s.id.substringBefore('/'), "Skies (HiPS)")
        Organization.CENTRE -> listOf(centreOfSky(s), "Skies (HiPS)")
    }
}

internal fun archiveFolders(a: ArchiveSource, org: Organization): List<String> = when (org) {
    Organization.DEFAULT -> listOf("Archives and catalogues", a.kind.label)
    Organization.WAVELENGTH -> listOf(HiPSBands.ofArchive(a.band), a.kind.label)
    Organization.MISSION -> listOf(a.mission.ifBlank { "Various" }, a.kind.label)
    Organization.CENTRE -> listOf(a.centre.ifBlank { "ESA" }, a.kind.label)
}

/** Data centre behind a sky survey, from its identifier prefix (CDS/..., ESAVO/..., CAMRAS/...). */
internal fun centreOfSky(s: HiPSSurvey): String = when (val p = s.id.substringBefore('/')) {
    "CDS" -> "CDS (Strasbourg)"
    "ESAVO" -> "ESA"
    "CADC" -> "CADC"
    "IRSA" -> "IRSA"
    "NASA", "HEASARC" -> "NASA"
    "" -> "Other"
    else -> p
}

private fun buildTree(list: List<DataEntry>): Node {
    val root = Node("", "")
    for (e in list) {
        var n = root
        var path = ""
        for (part in e.folders) {
            path = if (path.isEmpty()) part else "$path/$part"
            n = n.children.getOrPut(part) { Node(part, path) }
        }
        n.leaves.add(e)
    }
    return root
}

private fun flatten(n: Node, depth: Int, expanded: Set<String>, forceOpen: Boolean, out: MutableList<TreeRow>) {
    val order = HiPSBands.all
    val kids = if (n.path.isEmpty() && n.children.keys.all { it in order }) n.children.values.sortedBy { order.indexOf(it.name) } else n.children.values.toList()
    for (c in kids) {
        val open = forceOpen || c.path in expanded
        out.add(TreeRow(depth, c.name, c.path, null, c.count(), open))
        if (open) flatten(c, depth + 1, expanded, forceOpen, out)
    }
    for (e in n.leaves.sortedBy { it.label.lowercase() }) out.add(TreeRow(depth, e.label, n.path, e, 0, false))
}

/**
 * "Available data": sky surveys (HiPS) and archives/catalogues in ONE tree, organised the way you choose:
 * default folders, by wavelength (gamma, X-ray, UV, optical, infrared, radio...) or by mission. A search box and
 * band chips filter it. Tapping a sky shows it; tapping an archive searches the field you are looking at.
 */
@Composable
fun HipsBrowser(
    surveys: List<HiPSSurvey>, archives: List<ArchiveSource>, selectedId: String, loading: Boolean,
    onSelect: (HiPSSurvey) -> Unit, onArchive: (ArchiveSource) -> Unit, onOpenUrl: (String) -> Unit, modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("astrovia_ui", Context.MODE_PRIVATE) }
    var org by remember { mutableStateOf(runCatching { Organization.valueOf(prefs.getString("data_org", "DEFAULT")!!) }.getOrDefault(Organization.DEFAULT)) }
    var query by remember { mutableStateOf("") }
    var band by remember { mutableStateOf<String?>(null) }
    var offlineOnly by remember { mutableStateOf(false) }
    var showUrl by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf("Popular", "Offline (downloaded)")) }

    val entries = remember(surveys, archives, org) {
        surveys.map { DataEntry(skyFolders(it, org), it.title, it, null, it.band) } +
            archives.map { DataEntry(archiveFolders(it, org), it.label, null, it, HiPSBands.ofArchive(it.band)) }
    }
    val shown = remember(entries, query, band, offlineOnly) {
        entries.filter { e ->
            (band == null || e.band == band) && (!offlineOnly || e.survey?.id?.endsWith("_local") == true) &&
                (query.isBlank() || e.label.contains(query, true) || e.folders.any { it.contains(query, true) } ||
                    (e.survey?.let { it.id.contains(query, true) || it.description.contains(query, true) } ?: false) ||
                    (e.source?.let { it.mission.contains(query, true) || it.band.contains(query, true) } ?: false))
        }
    }
    val forceOpen = query.isNotBlank() || band != null || offlineOnly
    val rows = remember(shown, expanded, forceOpen) { ArrayList<TreeRow>().also { flatten(buildTree(shown), 0, expanded, forceOpen, it) } }
    val bandCounts = remember(entries) { entries.groupingBy { it.band }.eachCount() }

    Column(modifier) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            label = { Text("Search: JWST, radio, gamma, Gaia, offline...") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        )
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Organize", color = TextSecondary, style = MaterialTheme.typography.labelMedium)
            Organization.entries.forEach { o ->
                FilterChip(selected = org == o, onClick = { org = o; prefs.edit().putString("data_org", o.name).apply() }, label = { Text(o.label) })
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = band == null && !offlineOnly, onClick = { band = null; offlineOnly = false }, label = { Text("All") })
            FilterChip(selected = offlineOnly, onClick = { offlineOnly = !offlineOnly }, label = { Text("Offline") })
            HiPSBands.all.filter { (bandCounts[it] ?: 0) > 0 }.forEach { b ->
                FilterChip(selected = band == b, onClick = { band = if (band == b) null else b }, label = { Text("$b (${bandCounts[b]})") })
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} items", color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { showUrl = true }) { Text("Open HiPS by URL") }
        }
        HorizontalDivider()
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(rows, key = { (it.entry?.survey?.id ?: it.entry?.source?.id ?: "dir") + "@" + it.path + "/" + it.label }) { r ->
                val indent = (12 + r.depth * 14).dp
                val e = r.entry
                if (e == null) {
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = if (r.path in expanded) expanded - r.path else expanded + r.path }
                            .padding(start = indent, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = TextSecondary)
                        Spacer(Modifier.width(8.dp))
                        Text(r.label, color = TextPrimary, modifier = Modifier.weight(1f), maxLines = 1)
                        Text("${r.count}", color = TextSecondary, style = MaterialTheme.typography.labelSmall)
                        Icon(if (r.open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = TextSecondary)
                    }
                } else if (e.survey != null) {
                    NavigationDrawerItem(
                        label = { Text(e.label, maxLines = 2) }, selected = e.survey.id == selectedId, onClick = { onSelect(e.survey) },
                        icon = { Icon(Icons.Filled.Public, contentDescription = null) }, modifier = Modifier.padding(start = indent - 8.dp, end = 8.dp),
                    )
                } else if (e.source != null) {
                    NavigationDrawerItem(
                        label = { Column { Text(e.label, maxLines = 2); Text("${e.source.kind.label} · ${e.source.mission} · search this field", style = MaterialTheme.typography.labelSmall, color = TextSecondary) } },
                        selected = false, onClick = { onArchive(e.source) },
                        icon = { Icon(Icons.Filled.TableChart, contentDescription = null) }, modifier = Modifier.padding(start = indent - 8.dp, end = 8.dp),
                    )
                }
            }
        }
    }
    if (showUrl) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrl = false },
            title = { Text("Open a HiPS by URL") },
            text = {
                Column {
                    Text("Address of the HiPS root (the folder that contains the \"properties\" file), e.g. https://alasky.cds.unistra.fr/DSS/DSSColor", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(value = url, onValueChange = { url = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { onOpenUrl(url.trim()); showUrl = false }, enabled = url.startsWith("http")) { Text("Open") } },
            dismissButton = { TextButton(onClick = { showUrl = false }) { Text("Cancel") } },
        )
    }
}
