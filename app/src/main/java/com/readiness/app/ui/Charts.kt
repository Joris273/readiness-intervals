package com.readiness.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.data.Snapshot
import kotlin.math.roundToInt

/**
 * Verlaufsdiagramme mit Achsenbeschriftung und antippbaren Datenpunkten.
 *
 * Bewusst ohne Diagramm-Bibliothek: die Darstellungen sind einfach genug, und eine
 * zusätzliche Abhängigkeit brächte Versionsrisiko in den Build.
 *
 * Die Werte stehen als Ableseleiste ÜBER dem Diagramm, nicht als schwebende Sprechblase.
 * Auf einem Telefon verdeckt eine Blase genau die Stelle, die man betrachtet, und der
 * Finger liegt ohnehin darauf.
 */

private data class Scale(val min: Double, val max: Double) {
    val span get() = (max - min).takeIf { it > 1e-9 } ?: 1.0
    fun y(v: Double, h: Float) = h - ((v - min) / span * h).toFloat()
}

private fun scaleOf(values: List<Double?>, pad: Double = 0.1, forceZero: Boolean = false): Scale {
    val vs = values.filterNotNull()
    if (vs.isEmpty()) return Scale(0.0, 1.0)
    var lo = vs.min(); var hi = vs.max()
    if (forceZero) lo = minOf(lo, 0.0)
    val p = (hi - lo).takeIf { it > 1e-9 }?.times(pad) ?: 1.0
    return Scale(lo - p, hi + p)
}

private fun DrawScope.polyline(values: List<Double?>, s: Scale, color: Color, width: Float) {
    if (values.count { it != null } < 2) return
    val stepX = size.width / (values.size - 1).coerceAtLeast(1)
    val path = Path(); var started = false
    values.forEachIndexed { i, v ->
        if (v == null) { started = false; return@forEachIndexed }
        val x = i * stepX; val y = s.y(v, size.height)
        if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
    }
    drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
}

private fun DrawScope.marker(index: Int, count: Int, color: Color) {
    val stepX = size.width / (count - 1).coerceAtLeast(1)
    val x = index * stepX
    drawLine(color.copy(alpha = .55f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
}

private fun DrawScope.dot(index: Int, count: Int, v: Double?, s: Scale, color: Color) {
    if (v == null) return
    val stepX = size.width / (count - 1).coerceAtLeast(1)
    drawCircle(color, radius = 5f, center = Offset(index * stepX, s.y(v, size.height)))
}

private fun fmt(v: Double?, d: Int = 0) =
    if (v == null) "–" else String.format("%.${d}f", v).replace('.', ',')

private fun dayLabel(date: String) = date.substring(5).replace('-', '.')

/** Gemeinsames Gerüst: Titel, Legende, Ableseleiste, Y-Achse, Zeichenfläche, X-Achse. */
@Composable
private fun ChartFrame(
    title: String,
    legend: List<Pair<String, Color>>,
    readout: String,
    yLabels: List<String>,
    xLabels: List<String>,
    onPick: (Float, Float) -> Unit,
    draw: DrawScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(14.dp)) {
        Text(title, color = T.Text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            legend.forEach { (label, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
                    Text(" $label", color = T.Muted, fontSize = 11.sp)
                }
            }
        }
        Text(readout, color = T.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))

        Row(Modifier.fillMaxWidth().height(140.dp)) {
            // Y-Achse: drei Marken, oben nach unten
            Column(Modifier.width(38.dp).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End) {
                yLabels.forEach { Text(it, color = T.Faint, fontSize = 9.5.sp) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().padding(start = 6.dp)
                .pointerInput(Unit) { detectTapGestures { onPick(it.x, size.width.toFloat()) } }
                .pointerInput(Unit) {
                    detectDragGestures { change, _ -> onPick(change.position.x, size.width.toFloat()) }
                }
            ) { draw() }
        }
        Row(Modifier.fillMaxWidth().padding(start = 44.dp, top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween) {
            xLabels.forEach { Text(it, color = T.Faint, fontSize = 9.5.sp) }
        }
    }
}

/** Form (TSB) und HRV. Zwei Größen mit unterschiedlichen Einheiten, jeweils eigene Skala. */
@Composable
fun TsbHrvChart(points: List<Snapshot.ChartPoint>) {
    if (points.size < 2) return
    var sel by remember(points.size) { mutableStateOf(points.lastIndex) }
    val tsb = points.map { it.tsb }
    val hrv = points.map { it.hrv }
    val sT = scaleOf(tsb, forceZero = true)
    val sH = scaleOf(hrv, pad = 0.2)
    val cT = T.hex("#A78BFA"); val cH = T.hex("#4CC3FF")
    val p = points[sel]

    ChartFrame(
        title = "Form (TSB) und HRV · 30 Tage",
        legend = listOf("TSB zu Tagesbeginn" to cT, "HRV (ms)" to cH),
        readout = "${dayLabel(p.date)} · TSB ${fmt(p.tsb, 1)} · HRV ${fmt(p.hrv, 0)} ms",
        yLabels = listOf(fmt(sT.max, 0), fmt((sT.min + sT.max) / 2, 0), fmt(sT.min, 0)),
        xLabels = listOf(dayLabel(points.first().date),
            dayLabel(points[points.size / 2].date), dayLabel(points.last().date)),
        onPick = { x, w -> sel = ((x / w) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex) },
    ) {
        // Nulllinie der TSB-Skala als Orientierung
        if (sT.min <= 0 && sT.max >= 0)
            drawLine(T.Line, Offset(0f, sT.y(0.0, size.height)), Offset(size.width, sT.y(0.0, size.height)), 1.5f)
        marker(sel, points.size, T.Muted)
        polyline(hrv, sH, cH, 2.5f)
        polyline(tsb, sT, cT, 3f)
        dot(sel, points.size, points[sel].hrv, sH, cH)
        dot(sel, points.size, points[sel].tsb, sT, cT)
    }
}

/** Fitness (CTL), Ermüdung (ATL) und die Tagesbelastung als Balken. */
@Composable
fun LoadChart(points: List<Snapshot.ChartPoint>) {
    if (points.size < 2) return
    var sel by remember(points.size) { mutableStateOf(points.lastIndex) }
    val ctl = points.map { it.ctl }
    val atl = points.map { it.atl }
    val loads = points.map { it.load }
    val hi = maxOf(ctl.filterNotNull().maxOrNull() ?: 0.0, atl.filterNotNull().maxOrNull() ?: 0.0,
        loads.maxOrNull() ?: 0.0, 1.0) * 1.1
    val s = Scale(0.0, hi)
    val cBar = T.hex("#5B6C85")
    val p = points[sel]

    ChartFrame(
        title = "Fitness, Ermüdung und Tagesbelastung · 30 Tage",
        legend = listOf("CTL" to T.Green, "ATL" to T.Amber, "Belastung" to cBar),
        // Tagesendwerte; die Form zu Tagesbeginn zeigt die Grafik darüber
        readout = "${dayLabel(p.date)} · CTL ${fmt(p.ctl, 0)} · ATL ${fmt(p.atl, 0)} · Belastung ${fmt(p.load, 0)}",
        yLabels = listOf(fmt(hi, 0), fmt(hi / 2, 0), "0"),
        xLabels = listOf(dayLabel(points.first().date),
            dayLabel(points[points.size / 2].date), dayLabel(points.last().date)),
        onPick = { x, w -> sel = ((x / w) * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex) },
    ) {
        val stepX = size.width / (points.size - 1).coerceAtLeast(1)
        val barW = (stepX * 0.55f).coerceAtLeast(2f)
        loads.forEachIndexed { i, l ->
            if (l <= 0) return@forEachIndexed
            val h = (l / s.span * size.height).toFloat()
            drawRect(if (i == sel) cBar.copy(alpha = 1f) else cBar.copy(alpha = .75f),
                Offset(i * stepX - barW / 2, size.height - h), Size(barW, h))
        }
        marker(sel, points.size, T.Muted)
        polyline(atl, s, T.Amber, 2.5f)
        polyline(ctl, s, T.Green, 3f)
        dot(sel, points.size, points[sel].atl, s, T.Amber)
        dot(sel, points.size, points[sel].ctl, s, T.Green)
    }
}
