package com.readiness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.data.Snapshot
import com.readiness.app.domain.AnalysisConfig

/** Kompaktstreifen direkt unter der Ampel: Verdikt plus drei Kennzahlen auf einen Blick. */
@Composable
fun ProgressionStrip(p: Snapshot.Progression, onOpen: () -> Unit) {
    val opt = AnalysisConfig.CYCLE_OPTIONS.firstOrNull { it.n == p.cycles } ?: AnalysisConfig.CYCLE_OPTIONS[0]
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel)
        .clickable(onClick = onOpen).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(T.hex(p.colorHex)))
            Spacer(Modifier.width(9.dp))
            Text(p.title, color = T.hex(p.colorHex), fontSize = 14.5.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${opt.sub} · Details ▾", color = T.Muted, fontSize = 11.5.sp)
        }
        if (p.chips.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            p.chips.forEach { c ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(T.Chip).padding(7.dp, 7.dp)) {
                    Text(c.key, color = T.Muted, fontSize = 10.5.sp)
                    Text(c.value, color = T.deltaColor(c.kind), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Ausführliche Karte, standardmäßig eingeklappt. */
@Composable
fun ProgressionCard(p: Snapshot.Progression, open: Boolean, onToggle: () -> Unit, onSetCycles: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Formaufbau im Detail", color = T.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("Dosis vs. Antwort, ${p.windowDays}-Tage-Fenster", color = T.Muted, fontSize = 11.5.sp)
            }
            Text(if (open) "▴" else "▾", color = T.Muted, fontSize = 14.sp)
        }
        if (!open) return@Column

        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            // Vergleichszeitraum wählbar
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.Bg).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AnalysisConfig.CYCLE_OPTIONS.forEach { o ->
                    val sel = o.n == p.cycles
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                        .background(if (sel) T.Sel else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable { if (!sel) onSetCycles(o.n) }.padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(o.label, color = if (sel) T.Text else T.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(o.sub, color = if (sel) T.Text else T.Muted, fontSize = 10.5.sp)
                    }
                }
            }
            val opt = AnalysisConfig.CYCLE_OPTIONS.firstOrNull { it.n == p.cycles } ?: AnalysisConfig.CYCLE_OPTIONS[0]
            Text("Verglichen werden die letzten ${opt.sub} mit den ${opt.sub} davor — ${opt.hint}.",
                color = T.Faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))

            /* Der Farbbalken muss die Höhe des Textes annehmen, nicht eine geratene feste
               Höhe — bei längeren Begründungen reichte er sonst nur bis zur Hälfte.
               IntrinsicSize.Min misst die Zeile am Inhalt, fillMaxHeight streckt den Balken. */
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(T.hex(p.colorHex)))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(p.title, color = T.hex(p.colorHex), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(p.text, color = T.Muted, fontSize = 12.5.sp, modifier = Modifier.padding(top = 3.dp))
                }
            }

            Spacer(Modifier.height(12.dp))
            p.rows.forEach { r -> ProgRow(r) }

            p.share12?.let { s12 ->
                Spacer(Modifier.height(12.dp))
                Text("Intensitätsverteilung · 28 Tage · ${p.zoneHours?.toInt() ?: 0} h in Zonen",
                    color = T.Text, fontSize = 13.sp)
                Row(Modifier.fillMaxWidth().height(9.dp).padding(top = 6.dp).clip(RoundedCornerShape(5.dp)).background(T.Chip)) {
                    Box(Modifier.weight(s12.toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(T.Green))
                    Box(Modifier.weight((p.share3 ?: 0.0).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(T.Amber))
                    Box(Modifier.weight((p.share4 ?: 0.0).toFloat().coerceAtLeast(0.01f)).fillMaxHeight().background(T.Red))
                }
                Text("Z1–2 ${s12.toInt()} % · Z3 ${(p.share3 ?: 0.0).toInt()} % · Z4+ ${(p.share4 ?: 0.0).toInt()} % · " +
                    "Referenz ~80 / 20${p.distributionNote}", color = T.Muted, fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 6.dp))
            }

            if (p.anyThin) Text("° in einem der Zeiträume fehlt ein Maximalversuch dieser Dauer — der Wert wird angezeigt, " +
                "seine Höhe ist aber nicht belastbar.",
                color = T.Faint, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
            Text(p.hint, color = T.Faint, fontSize = 11.5.sp, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

@Composable
private fun ProgRow(r: Snapshot.Row) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(r.label, color = T.Text, fontSize = 13.sp)
            Text(r.note, color = T.Muted, fontSize = 11.sp)
        }
        if (r.value.isNotBlank()) Text(r.value, color = T.Muted, fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp))
        if (r.delta != null) Text(r.delta, color = T.deltaColor(r.deltaKind), fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.widthIn(min = 62.dp))
        else Text(r.reason ?: "–", color = T.Faint, fontSize = 10.5.sp, modifier = Modifier.widthIn(max = 108.dp))
    }
}
