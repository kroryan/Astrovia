@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kroryan.astrovia.data.archive.ArchiveHit
import com.kroryan.astrovia.ui.theme.TextPrimary
import com.kroryan.astrovia.ui.theme.TextSecondary
import java.time.LocalDate
import kotlin.math.log10

/** Pure data side of the observation charts (kept apart from the drawing so it can be tested). */
object ChartData {
    private val groupKeys = listOf("instrument", "instrument_name", "instrument_oid", "obs_collection", "observing_mode", "collection", "detector", "filter", "target_name", "targname", "class")
    private val timeKeys = listOf("start_time", "start_utc", "t_min", "v_start_u", "start_date", "obs_date1", "datarelease", "obs_release_date", "disc_year")

    fun groupKey(hits: List<ArchiveHit>): String? = groupKeys.firstOrNull { k -> hits.any { h -> h.fields.keys.any { it.equals(k, true) } } }

    /** Counts per value of the best grouping column, most frequent first. */
    fun groups(hits: List<ArchiveHit>, max: Int = 10): Pair<String, List<Pair<String, Int>>>? {
        val k = groupKey(hits) ?: return null
        val c = hits.mapNotNull { h -> h.fields.entries.firstOrNull { it.key.equals(k, true) }?.value?.trim()?.takeIf { it.isNotEmpty() } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(max).map { it.key to it.value }
        return if (c.isEmpty()) null else k to c
    }

    /** Year of an observation from ISO dates, MJD numbers or plain years. */
    fun year(raw: String): Int? {
        val t = raw.trim()
        Regex("^(\\d{4})-\\d{2}-\\d{2}").find(t)?.let { return it.groupValues[1].toInt() }
        val d = t.toDoubleOrNull() ?: return null
        return when {
            d in 1900.0..2100.0 -> d.toInt()
            d in 15000.0..80000.0 -> LocalDate.of(1858, 11, 17).plusDays(d.toLong()).year   // MJD
            d in 2415000.0..2500000.0 -> LocalDate.of(1858, 11, 17).plusDays((d - 2400000.5).toLong()).year // JD
            else -> null
        }
    }

    fun years(hits: List<ArchiveHit>): Pair<String, Map<Int, Int>>? {
        val k = timeKeys.firstOrNull { key -> hits.any { h -> h.fields.keys.any { it.equals(key, true) } } } ?: return null
        val m = sortedMapOf<Int, Int>()
        for (h in hits) {
            val v = h.fields.entries.firstOrNull { it.key.equals(k, true) }?.value ?: continue
            year(v)?.let { m[it] = (m[it] ?: 0) + 1 }
        }
        return if (m.isEmpty()) null else k to m
    }

    /** Wavelength interval (metres) of a hit from ObsCore em_min/em_max, if present. */
    fun wavelength(h: ArchiveHit): Pair<Double, Double>? {
        val lo = h.fields.entries.firstOrNull { it.key.equals("em_min", true) }?.value?.toDoubleOrNull() ?: return null
        val hi = h.fields.entries.firstOrNull { it.key.equals("em_max", true) }?.value?.toDoubleOrNull() ?: return null
        return if (lo > 0 && hi >= lo) lo to hi else null
    }

    /** Approximate wavelength range (metres) of each band, for sources without per-row wavelengths. */
    val bands = listOf(
        "Gamma" to (1e-14 to 1e-11), "X-ray" to (1e-11 to 1e-8), "UV" to (1e-8 to 4e-7), "Optical" to (4e-7 to 7e-7),
        "IR" to (7e-7 to 1e-3), "mm" to (1e-3 to 1e-2), "Radio" to (1e-2 to 1e3),
    )

    fun bandRange(band: String): Pair<Double, Double>? {
        val b = band.lowercase()
        val parts = bands.filter { (n, _) -> n.lowercase().let { it in b || (it == "ir" && "infra" in b) } }
        if (parts.isEmpty()) return null
        return parts.minOf { it.second.first } to parts.maxOf { it.second.second }
    }
}

/** Which chart is shown. */
private enum class ChartKind(val label: String) { INSTRUMENT("Instruments"), TIME("Time"), SPECTRUM("Wavelength"), MAP("Field map") }

private val palette = listOf(0xFF38BDF8, 0xFFA78BFA, 0xFFFBBF24, 0xFF34D399, 0xFFF472B6, 0xFFFB923C, 0xFF2DD4BF, 0xFFF87171, 0xFF818CF8, 0xFFA3E635).map { Color(it) }

/**
 * Charts of the current results, one at a time chosen with the chips on top: results per instrument (bars), per year (area),
 * wavelength covered (rainbow spectrum with counts) and a map of the field. Tapping a bar, a year, a band or a point
 * filters (or opens) the results listed right below.
 */
@Composable
fun ArchiveCharts(
    hits: List<ArchiveHit>, filter: com.kroryan.astrovia.HitFilter?, onFilter: (com.kroryan.astrovia.HitFilter?) -> Unit,
    modifier: Modifier = Modifier, onPick: (ArchiveHit) -> Unit = {},
) {
    if (hits.isEmpty()) return
    val groups = remember(hits) { ChartData.groups(hits, 40) }
    val years = remember(hits) { ChartData.years(hits) }
    val waves = remember(hits) { hits.mapNotNull { ChartData.wavelength(it) } }
    val srcBand = hits.first().source.band
    val available = remember(hits) { ChartKind.entries.filter { it != ChartKind.INSTRUMENT || groups != null }.filter { it != ChartKind.TIME || years != null } }
    var kind by remember(hits) { mutableStateOf(available.first()) }
    Column(modifier) {
        Row(Modifier.padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k.label) }) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (kind) {
                ChartKind.INSTRUMENT -> groups?.let { g ->
                    val gk = g.first
                    ChartTitle("By ${gk.replace('_', ' ')}")
                    BarChart(g.second.map { it.first to it.second }, hits.size, selected = filter?.label?.removePrefix("$gk = ")) { value ->
                        onFilter(com.kroryan.astrovia.HitFilter("$gk = $value") { h -> h.fields.entries.firstOrNull { it.key.equals(gk, true) }?.value?.trim() == value })
                    }
                }
                ChartKind.TIME -> years?.let { y ->
                    val yk = y.first
                    ChartTitle("Per year (${yk.replace('_', ' ')})")
                    AreaChart(y.second, selected = filter?.label?.removePrefix("year = ")) { yr ->
                        onFilter(com.kroryan.astrovia.HitFilter("year = $yr") { h -> h.fields.entries.firstOrNull { it.key.equals(yk, true) }?.value?.let { v -> ChartData.year(v)?.toString() == yr } == true })
                    }
                }
                ChartKind.SPECTRUM -> {
                    ChartTitle(if (waves.isNotEmpty()) "Wavelength of the results" else "Typical wavelength of this archive")
                    SpectrumChart(hits, waves, if (waves.isEmpty()) ChartData.bandRange(srcBand) else null, srcBand, selected = filter?.label?.removePrefix("band = ")) { band ->
                        val r = ChartData.bands.firstOrNull { it.first == band }?.second
                        if (r != null && waves.isNotEmpty()) onFilter(com.kroryan.astrovia.HitFilter("band = $band") { h -> ChartData.wavelength(h)?.let { w -> Math.sqrt(w.first * w.second) in r.first..r.second } == true })
                    }
                }
                ChartKind.MAP -> {
                    ChartTitle("Where the results are (tap a point)")
                    FieldMap(hits, onPick)
                }
            }
        }
    }
}

@Composable
private fun ChartTitle(t: String) = Text(t, color = TextPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun BarChart(items: List<Pair<String, Int>>, total: Int, selected: String?, onTap: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val label = TextStyle(color = TextPrimary, fontSize = 12.sp)
    val small = TextStyle(color = TextSecondary, fontSize = 11.sp)
    val rowH = 30.dp
    Canvas(Modifier.fillMaxWidth().height(rowH * items.size).pointerInput(items) {
        detectTapGestures { p -> items.getOrNull((p.y / rowH.toPx()).toInt())?.let { onTap(it.first) } }
    }) {
        val max = items.maxOf { it.second }.coerceAtLeast(1)
        val labelW = size.width * 0.36f; val valueW = 74.dp.toPx()
        val rh = rowH.toPx()
        items.forEachIndexed { i, (name, v) ->
            val y = i * rh
            val sel = selected == null || selected == name
            val col = palette[i % palette.size].copy(alpha = if (sel) 1f else 0.28f)
            val t = tm.measure(name.take(26), label)
            drawText(t, topLeft = Offset(0f, y + (rh - t.size.height) / 2f))
            val bw = ((size.width - labelW - valueW) * v / max).coerceAtLeast(6.dp.toPx())
            drawRoundRect(Color(0x14FFFFFF), Offset(labelW, y + 6.dp.toPx()), Size(size.width - labelW - valueW, rh - 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            drawRoundRect(col, Offset(labelW, y + 6.dp.toPx()), Size(bw, rh - 12.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
            val vt = tm.measure("$v  ${(100.0 * v / total).toInt().coerceAtLeast(if (v > 0) 1 else 0)}%", small)
            drawText(vt, topLeft = Offset(size.width - valueW + 6.dp.toPx(), y + (rh - vt.size.height) / 2f))
        }
    }
}

@Composable
private fun AreaChart(counts: Map<Int, Int>, selected: String?, onTap: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val small = TextStyle(color = TextSecondary, fontSize = 10.sp)
    val y0 = counts.keys.min(); val y1 = counts.keys.max()
    val series = (y0..y1).map { it to (counts[it] ?: 0) }
    Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(series) {
        detectTapGestures { p ->
            val left = 8.dp.toPx(); val w = size.width - left - 8.dp.toPx()
            val i = (((p.x - left) / w) * (series.size - 1).coerceAtLeast(1)).let { Math.round(it).toInt() }.coerceIn(0, series.size - 1)
            if (series[i].second > 0) onTap(series[i].first.toString())
        }
    }) {
        val left = 8.dp.toPx(); val right = 8.dp.toPx(); val top = 18.dp.toPx(); val bottom = 22.dp.toPx()
        val w = size.width - left - right; val h = size.height - top - bottom
        val max = series.maxOf { it.second }.coerceAtLeast(1)
        fun px(i: Int) = left + if (series.size == 1) w / 2 else w * i / (series.size - 1)
        fun py(v: Int) = top + h - h * v / max
        drawLine(Color(0x33FFFFFF), Offset(left, top + h), Offset(left + w, top + h), 1.5f)
        val line = androidx.compose.ui.graphics.Path(); val fill = androidx.compose.ui.graphics.Path()
        series.forEachIndexed { i, (_, v) -> if (i == 0) { line.moveTo(px(i), py(v)); fill.moveTo(px(i), top + h); fill.lineTo(px(i), py(v)) } else { line.lineTo(px(i), py(v)); fill.lineTo(px(i), py(v)) } }
        fill.lineTo(px(series.size - 1), top + h); fill.close()
        drawPath(fill, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0x66A78BFA), Color(0x05A78BFA)), top, top + h))
        drawPath(line, Color(0xFFA78BFA), style = Stroke(width = 3.dp.toPx()))
        series.forEachIndexed { i, (yr, v) ->
            if (v == 0) return@forEachIndexed
            val sel = selected == yr.toString()
            drawCircle(if (sel) Color.White else Color(0xFFA78BFA), if (sel) 6.dp.toPx() else 3.5.dp.toPx(), Offset(px(i), py(v)))
            if (sel || series.size <= 8) { val t = tm.measure(v.toString(), small); drawText(t, topLeft = Offset(px(i) - t.size.width / 2f, py(v) - 16.dp.toPx())) }
        }
        val step = (series.size / 6).coerceAtLeast(1)
        for (i in series.indices step step) { val t = tm.measure(series[i].first.toString(), small); drawText(t, topLeft = Offset((px(i) - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), top + h + 4.dp.toPx())) }
    }
}

/** Gamma rays to radio on a log wavelength axis as a rainbow, with a bubble per band sized by the number of results. */
@Composable
private fun SpectrumChart(hits: List<ArchiveHit>, intervals: List<Pair<Double, Double>>, fallback: Pair<Double, Double>?, band: String, selected: String?, onBand: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val small = TextStyle(color = TextSecondary, fontSize = 10.sp)
    val counts = remember(hits) { ChartData.bands.associate { (n, r) -> n to hits.count { h -> ChartData.wavelength(h)?.let { w -> Math.sqrt(w.first * w.second) in r.first..r.second } == true } } }
    val rainbow = listOf(Color(0xFF7C3AED), Color(0xFF6366F1), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFEAB308), Color(0xFFF97316), Color(0xFFDC2626))
    Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(intervals) {
        detectTapGestures { p ->
            val lg = -14.0 + p.x / size.width * 17.0
            ChartData.bands.firstOrNull { (_, r) -> lg >= log10(r.first) && lg < log10(r.second) }?.let { onBand(it.first) }
        }
    }) {
        fun x(m: Double) = (((log10(m.coerceIn(1e-14, 1e3)) + 14.0) / 17.0) * size.width).toFloat()
        val barY = 72.dp.toPx(); val barH = 26.dp.toPx()
        drawRoundRect(androidx.compose.ui.graphics.Brush.horizontalGradient(rainbow), Offset(0f, barY), Size(size.width, barH), androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), alpha = 0.55f)
        val maxC = counts.values.maxOrNull()?.coerceAtLeast(1) ?: 1
        ChartData.bands.forEachIndexed { i, (name, r) ->
            val x0 = x(r.first); val x1 = x(r.second); val cx = (x0 + x1) / 2f
            if (i > 0) drawLine(Color(0x55000000), Offset(x0, barY), Offset(x0, barY + barH), 1.5f)
            val t = tm.measure(name, small)
            if (x1 - x0 > t.size.width - 6) drawText(t, topLeft = Offset(cx - t.size.width / 2f, barY + barH + 6.dp.toPx()))
            val n = counts[name] ?: 0
            if (n > 0) {
                val rad = (7 + 15 * Math.sqrt(n.toDouble() / maxC)).toFloat().dp.toPx()
                val sel = selected == null || selected == name
                drawCircle(Color(0xFF38BDF8).copy(alpha = if (sel) 0.95f else 0.3f), rad, Offset(cx, barY - 6.dp.toPx() - rad))
                val c = tm.measure(n.toString(), TextStyle(color = Color.Black, fontSize = 11.sp))
                drawText(c, topLeft = Offset(cx - c.size.width / 2f, barY - 6.dp.toPx() - rad - c.size.height / 2f))
            }
        }
        if (counts.values.all { it == 0 }) {
            val shown = if (intervals.isNotEmpty()) intervals else listOfNotNull(fallback)
            if (shown.isNotEmpty()) {
                val a = shown.minOf { it.first }; val b = shown.maxOf { it.second }
                drawRoundRect(Color.White, Offset(x(a), barY - 14.dp.toPx()), Size((x(b) - x(a)).coerceAtLeast(6f), 8.dp.toPx()), androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
                val t = tm.measure("$band (typical)", small); drawText(t, topLeft = Offset(x(a).coerceIn(0f, size.width - t.size.width), barY - 34.dp.toPx()))
            }
        }
    }
}

/** The results as points around the centre of the field (east to the left, north up); tap one to open it. */
@Composable
private fun FieldMap(hits: List<ArchiveHit>, onPick: (ArchiveHit) -> Unit) {
    val cra = remember(hits) { hits.map { it.raDeg }.average() }; val cdec = remember(hits) { hits.map { it.decDeg }.average() }
    val cosd = Math.cos(Math.toRadians(cdec))
    val pts = remember(hits) { hits.map { h -> var d = h.raDeg - cra; if (d > 180) d -= 360; if (d < -180) d += 360; (-d * cosd).toFloat() to (h.decDeg - cdec).toFloat() } }
    val ext = remember(pts) { (pts.maxOfOrNull { maxOf(Math.abs(it.first), Math.abs(it.second)) } ?: 1f).coerceAtLeast(1e-4f) * 1.1f }
    val kinds = remember(hits) { hits.map { h -> h.fields.entries.firstOrNull { it.key.equals(ChartData.groupKey(hits) ?: "", true) }?.value ?: "" }.distinct() }
    Canvas(Modifier.fillMaxWidth().height(230.dp).pointerInput(pts) {
        detectTapGestures { p ->
            val s = minOf(size.width, size.height) / 2f
            val cx = size.width / 2f; val cy = size.height / 2f
            var best = -1; var bd = 28.dp.toPx() * 28.dp.toPx()
            pts.forEachIndexed { i, q -> val dx = cx + q.first / ext * s - p.x; val dy = cy - q.second / ext * s - p.y; val d = dx * dx + dy * dy; if (d < bd) { bd = d; best = i } }
            if (best >= 0) onPick(hits[best])
        }
    }) {
        val s = minOf(size.width, size.height) / 2f - 6.dp.toPx(); val cx = size.width / 2f; val cy = size.height / 2f
        drawCircle(Color(0x22FFFFFF), s, Offset(cx, cy), style = Stroke(1.5f)); drawCircle(Color(0x14FFFFFF), s / 2, Offset(cx, cy), style = Stroke(1f))
        drawLine(Color(0x14FFFFFF), Offset(cx - s, cy), Offset(cx + s, cy), 1f); drawLine(Color(0x14FFFFFF), Offset(cx, cy - s), Offset(cx, cy + s), 1f)
        pts.forEachIndexed { i, q ->
            val key = hits[i].fields.entries.firstOrNull { it.key.equals(ChartData.groupKey(hits) ?: "", true) }?.value ?: ""
            drawCircle(palette[kinds.indexOf(key).coerceAtLeast(0) % palette.size].copy(alpha = 0.8f), 4.dp.toPx(), Offset(cx + q.first / ext * s, cy - q.second / ext * s))
        }
    }
}

