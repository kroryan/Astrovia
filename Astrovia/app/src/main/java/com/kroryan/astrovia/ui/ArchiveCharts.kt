@file:Suppress("EmptyCatchBlock", "TooGenericExceptionThrown", "VariableNaming", "ImplicitDefaultLocale", "WildcardImport", "MagicNumber", "MaxLineLength", "ComplexCondition", "CyclomaticComplexMethod", "LongMethod", "ReturnCount", "LongParameterList", "SwallowedException", "TooGenericExceptionCaught", "NestedBlockDepth", "FunctionNaming", "LoopWithTooManyJumpStatements", "TooManyFunctions")
package com.kroryan.astrovia.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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

/** Charts for the current archive results: counts per instrument, time coverage and spectral coverage. */
@Composable
fun ArchiveCharts(hits: List<ArchiveHit>, filter: com.kroryan.astrovia.HitFilter?, onFilter: (com.kroryan.astrovia.HitFilter?) -> Unit, modifier: Modifier = Modifier) {
    if (hits.isEmpty()) {
        Text("Run a search to see charts of the results.", color = TextSecondary, modifier = modifier.padding(16.dp))
        return
    }
    val groups = remember(hits) { ChartData.groups(hits) }
    val years = remember(hits) { ChartData.years(hits) }
    val waves = remember(hits) { hits.mapNotNull { ChartData.wavelength(it) } }
    val srcBand = hits.first().source.band
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text("${hits.size} results · ${hits.first().source.label}", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
        if (groups != null) {
            ChartTitle("By ${groups.first.replace('_', ' ')}  (tap a bar to show only those)")
            val gk = groups.first
            BarChart(groups.second.map { it.first to it.second.toDouble() }, Color(0xFF38BDF8), selected = filter?.label?.removePrefix("$gk = ")) { value ->
                onFilter(com.kroryan.astrovia.HitFilter("$gk = $value") { h -> h.fields.entries.firstOrNull { it.key.equals(gk, true) }?.value?.trim() == value })
            }
        }
        if (years != null) {
            ChartTitle("Time coverage (${years.first.replace('_', ' ')}, per year; tap a year)")
            val yk = years.first
            ColumnChart(years.second.map { it.key.toString() to it.value.toDouble() }, Color(0xFFA78BFA), selected = filter?.label?.removePrefix("year = ")) { y ->
                onFilter(com.kroryan.astrovia.HitFilter("year = $y") { h -> h.fields.entries.firstOrNull { it.key.equals(yk, true) }?.value?.let { v -> ChartData.year(v)?.toString() == y } == true })
            }
        }
        ChartTitle("Spectral coverage")
        SpectrumChart(waves, if (waves.isEmpty()) ChartData.bandRange(srcBand) else null, srcBand)
        if (groups == null && years == null) Text("This archive does not give instruments or dates in the table.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ChartTitle(t: String) = Text(t, color = TextPrimary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))

@Composable
private fun BarChart(items: List<Pair<String, Double>>, color: Color, selected: String? = null, onTap: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val style = TextStyle(color = TextPrimary, fontSize = 11.sp)
    val rowH = 22.dp
    Canvas(Modifier.fillMaxWidth().height(rowH * items.size).pointerInput(items) {
        detectTapGestures { p -> val i = (p.y / rowH.toPx()).toInt(); items.getOrNull(i)?.let { onTap(it.first) } }
    }) {
        val max = items.maxOf { it.second }.coerceAtLeast(1.0)
        val labelW = size.width * 0.38f
        val rh = rowH.toPx()
        items.forEachIndexed { i, (label, v) ->
            val y = i * rh
            val txt = tm.measure(label.take(24), style)
            drawText(txt, topLeft = Offset(0f, y + (rh - txt.size.height) / 2f))
            val bw = ((size.width - labelW - 40.dp.toPx()) * (v / max)).toFloat().coerceAtLeast(2f)
            drawRect(if (selected == null || selected == label) color else color.copy(alpha = 0.3f), Offset(labelW, y + 4.dp.toPx()), Size(bw, rh - 8.dp.toPx()))
            val vt = tm.measure(v.toInt().toString(), style)
            drawText(vt, topLeft = Offset(labelW + bw + 4.dp.toPx(), y + (rh - vt.size.height) / 2f))
        }
    }
}

@Composable
private fun ColumnChart(items: List<Pair<String, Double>>, color: Color, selected: String? = null, onTap: (String) -> Unit) {
    val tm = rememberTextMeasurer()
    val style = TextStyle(color = TextSecondary, fontSize = 10.sp)
    Canvas(Modifier.fillMaxWidth().height(140.dp).pointerInput(items) {
        detectTapGestures { p -> val i = (p.x / (size.width / items.size)).toInt(); items.getOrNull(i)?.let { onTap(it.first) } }
    }) {
        val max = items.maxOf { it.second }.coerceAtLeast(1.0)
        val n = items.size
        val bottom = size.height - 16.dp.toPx()
        val bw = size.width / n
        items.forEachIndexed { i, (label, v) ->
            val h = ((bottom - 4.dp.toPx()) * (v / max)).toFloat()
            drawRect(if (selected == null || selected == label) color else color.copy(alpha = 0.3f), Offset(i * bw + bw * 0.12f, bottom - h), Size(bw * 0.76f, h))
        }
        val step = (n / 6).coerceAtLeast(1)
        for (i in items.indices step step) {
            val t = tm.measure(items[i].first, style)
            drawText(t, topLeft = Offset((i * bw + bw / 2f - t.size.width / 2f).coerceIn(0f, size.width - t.size.width), bottom + 2.dp.toPx()))
        }
        val mt = tm.measure("max ${max.toInt()}", style)
        drawText(mt, topLeft = Offset(0f, 0f))
    }
}

/** Log wavelength axis from gamma rays to radio, with the covered interval(s) highlighted. */
@Composable
private fun SpectrumChart(intervals: List<Pair<Double, Double>>, fallback: Pair<Double, Double>?, band: String) {
    val tm = rememberTextMeasurer()
    val style = TextStyle(color = TextSecondary, fontSize = 10.sp)
    val fills = listOf(Color(0xFF7C3AED), Color(0xFFDC2626), Color(0xFF2563EB), Color(0xFF16A34A), Color(0xFFD97706), Color(0xFF0EA5E9), Color(0xFF9CA3AF))
    Canvas(Modifier.fillMaxWidth().height(86.dp)) {
        val lo = -14.0; val hi = 3.0
        fun x(m: Double) = (((log10(m.coerceIn(1e-14, 1e3)) - lo) / (hi - lo)) * size.width).toFloat()
        val barY = 34.dp.toPx(); val barH = 14.dp.toPx()
        ChartData.bands.forEachIndexed { i, (name, r) ->
            val x0 = x(r.first); val x1 = x(r.second)
            drawRect(fills[i].copy(alpha = 0.28f), Offset(x0, barY), Size(x1 - x0, barH))
            val t = tm.measure(name, style)
            if (x1 - x0 > t.size.width) drawText(t, topLeft = Offset(x0 + (x1 - x0 - t.size.width) / 2f, barY + barH + 3.dp.toPx()))
        }
        val shown = if (intervals.isNotEmpty()) intervals else listOfNotNull(fallback)
        if (shown.isNotEmpty()) {
            val a = shown.minOf { it.first }; val b = shown.maxOf { it.second }
            drawRect(Color(0xFFFACC15), Offset(x(a), barY - 12.dp.toPx()), Size((x(b) - x(a)).coerceAtLeast(3f), 10.dp.toPx()))
            val label = if (intervals.isNotEmpty()) "%.3g – %.3g µm".format(a * 1e6, b * 1e6) else "$band (typical)"
            val t = tm.measure(label, style)
            drawText(t, topLeft = Offset(x(a).coerceIn(0f, size.width - t.size.width), 0f))
        }
        drawLine(Color.Gray, Offset(0f, barY + barH), Offset(size.width, barY + barH), strokeWidth = 1f)
    }
}
