@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.kroryan.astrovia.SkyViewModel
import com.kroryan.astrovia.data.gallery.DeepZoomPyramid
import com.kroryan.astrovia.data.gallery.OutreachImage
import com.kroryan.astrovia.data.gallery.TileLoader
import com.kroryan.astrovia.data.gallery.TilePyramid
import com.kroryan.astrovia.data.gallery.TilePyramids
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary
import kotlin.math.ceil
import kotlin.math.ln

/** Gallery of outreach pictures (Webb, Hubble, Euclid): thumbnails, search, tap to explore the picture at full resolution. */
@Composable
fun GalleryTab(vm: SkyViewModel, images: List<OutreachImage>?, loading: Boolean, mission: String, onMission: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("webb" to "James Webb", "euclid" to "Euclid", "hst" to "Hubble").forEach { (k, l) -> FilterChip(selected = mission == k, onClick = { onMission(k) }, label = { Text(l) }) }
        }
        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Search pictures: nebula, galaxy, NGC 602...") })
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        val shown = remember(images, query) { images?.filter { query.isBlank() || it.title.contains(query, true) || it.description.contains(query, true) || it.id.contains(query, true) }.orEmpty() }
        Text("${shown.size} pictures", color = TextSecondary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 4.dp))
        LazyColumn {
            items(shown, key = { it.id + it.mission }) { img ->
                Row(Modifier.fillMaxWidth().clickable { vm.openImage(img) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = img.thumbnailUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(72.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(img.title, color = TextPrimary, maxLines = 2)
                        Text(img.description.take(110), color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Full-resolution explorer of an outreach picture: only the tiles on screen are downloaded. */
@Composable
fun ImageExplorer(vm: SkyViewModel, img: OutreachImage, topInset: androidx.compose.ui.unit.Dp, bottomInset: androidx.compose.ui.unit.Dp) {
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val loader = remember(img) { TileLoader(scope) { version++ } }
    var pyr by remember(img) { mutableStateOf<TilePyramid?>(null) }
    var failed by remember(img) { mutableStateOf(false) }
    var scale by remember(img) { mutableFloatStateOf(1f) }
    var offset by remember(img) { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var placed by remember(img) { mutableStateOf(false) }
    var info by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    LaunchedEffect(pyr, box) {
        val p = pyr
        if (p != null && box.width > 0 && !placed) {
            val fit = minOf(box.width / p.width.toFloat(), box.height / p.height.toFloat())
            offset = Offset((box.width - p.width * fit) / 2f, (box.height - p.height * fit) / 2f)
            placed = true
        }
    }
    LaunchedEffect(img) {
        pyr = TilePyramids.open(img.tilesUrl) { u -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { TileLoader.fetchText(u) } }
        failed = pyr == null
    }
    BackHandler { vm.closeImage() }
    Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { }) {
        Surface(color = Color.Black, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(top = topInset, bottom = bottomInset)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(img.title, color = TextPrimary, maxLines = 1); Text(img.mission + " · " + img.id, color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1) }
                    IconButton(onClick = { info = true }) { Icon(Icons.Filled.Info, contentDescription = "Information", tint = Color.White) }
                    IconButton(onClick = { vm.goToImage(img) }) { Icon(Icons.Filled.MyLocation, contentDescription = "Go to its position", tint = Color.White) }
                    img.webPage?.let { w -> IconButton(onClick = { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(w)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Icon(Icons.Filled.OpenInBrowser, contentDescription = "Web page", tint = Color.White) } }
                    IconButton(onClick = { vm.closeImage() }) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.White) }
                }
                Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { box = it }) {
                    val p = pyr
                    if (p != null) {
                        @Suppress("UNUSED_EXPRESSION") version
                        Canvas(Modifier.fillMaxSize().pointerInput(p) {
                            detectTransformGestures { centroid, pan, zoom, _ ->
                                val ns = (scale * zoom).coerceIn(0.6f, 400f)
                                val k = ns / scale
                                offset = (offset - centroid) * k + centroid + pan
                                scale = ns
                            }
                        }) {
                            val fit = minOf(size.width / p.width, size.height / p.height)
                            val s = fit * scale                           // screen px per full-resolution px
                            val ox = offset.x
                            val oy = offset.y
                            fun drawLevel(level: Int, fetch: Boolean) {
                                val f = Math.pow(2.0, (p.maxLevel - level).toDouble()).toFloat()   // full-res px per level px
                                for (row in 0 until p.rows(level)) for (col in 0 until p.cols(level)) {
                                    val r = p.tileRect(level, col, row)
                                    val sx = ox + r[0] * f * s
                                    val sy = oy + r[1] * f * s
                                    val dw = (r[2] - r[0]) * f * s; val dh = (r[3] - r[1]) * f * s
                                    if (sx + dw < 0 || sy + dh < 0 || sx > size.width || sy > size.height) continue
                                    val url = p.tileUrl(level, col, row)
                                    val bmp = if (fetch) loader.request(url) else loader.peek(url)
                                    if (bmp != null) {
                                        val lo = (p as? DeepZoomPyramid)?.leftOverlap(col) ?: 0; val to = (p as? DeepZoomPyramid)?.topOverlap(row) ?: 0
                                        drawImage(bmp.asImageBitmap(), srcOffset = IntOffset(lo, to), srcSize = IntSize(r[2] - r[0], r[3] - r[1]),
                                            dstOffset = IntOffset(sx.toInt(), sy.toInt()), dstSize = IntSize(ceil(dw).toInt().coerceAtLeast(1), ceil(dh).toInt().coerceAtLeast(1)),
                                            filterQuality = FilterQuality.Medium)
                                    }
                                }
                            }
                            // backdrop: the smallest tier / a coarse level that is already there, then the sharp level for this zoom
                            val want = (p.maxLevel + ln(s.toDouble()) / ln(2.0)).let { ceil(it).toInt() }.coerceIn(0, p.maxLevel)
                            val back = minOf(p.thumbnailLevel() + 1, want).coerceAtLeast(p.thumbnailLevel())
                            drawLevel(p.thumbnailLevel(), true)
                            if (back > p.thumbnailLevel()) drawLevel(back, true)
                            if (want > back) { if (want - 2 > back) drawLevel(want - 2, true); drawLevel(want, true) }
                        }
                    } else {
                        Text(if (failed) "Could not open this picture" else "Opening picture...", color = TextSecondary, modifier = Modifier.align(Alignment.Center))
                    }
                }
            }
        }
    }
    if (info) {
        AlertDialog(
            onDismissRequest = { info = false }, confirmButton = { TextButton(onClick = { info = false }) { Text("Close") } },
            title = { Text(img.title) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(img.description.ifBlank { "No description" }, style = MaterialTheme.typography.bodyMedium); Spacer(Modifier.height(8.dp)); Text("Credit: ${img.credit}", style = MaterialTheme.typography.bodySmall) } },
        )
    }
}
