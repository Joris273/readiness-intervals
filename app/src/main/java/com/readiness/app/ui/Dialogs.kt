package com.readiness.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.domain.Confounders

@Composable
fun SettingsDialog(s: SettingsState, cacheKb: Long, onDismiss: () -> Unit, onSave: (SettingsState) -> Unit) {
    var key by remember { mutableStateOf(s.apiKey) }
    var athlete by remember { mutableStateOf(if (s.athlete == "0") "" else s.athlete) }
    var need by remember { mutableStateOf(s.sleepNeed) }
    var auto by remember { mutableStateOf(s.widgetAutoUpdate) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button({ onSave(s.copy(apiKey = key.trim(), athlete = athlete.trim().ifBlank { "0" },
                sleepNeed = need.trim(), widgetAutoUpdate = auto)) }) { Text("Speichern & laden") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
        title = { Column {
            Text("Einstellungen")
            if (s.appVersion.isNotBlank())
                Text("Version ${s.appVersion}", color = T.Muted, fontSize = 11.sp)
        } },
        text = {
            Column {
                OutlinedTextField(key, { key = it }, label = { Text("intervals.icu API-Key") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(athlete, { athlete = it }, label = { Text("Athlete-ID (leer = 0)") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(need, { need = it }, label = { Text("Schlafbedarf h/Nacht (leer = aus Historie)") }, singleLine = true)
                Text("FTP-Werte werden automatisch aus intervals.icu gelesen.",
                    color = T.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth().clickable { auto = !auto },
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(auto, { auto = it })
                    Column {
                        Text("Widget im Hintergrund aktualisieren", fontSize = 13.sp)
                        Text("Alle sechs Stunden, nur mit Netz und bei ausreichendem Akku.",
                            color = T.Muted, fontSize = 10.5.sp)
                    }
                }
                if (cacheKb > 0) Text(
                    "Zwischengespeicherte Rohdaten: $cacheKb KB — sie machen App-Start und " +
                        "Zeitraumwechsel ohne Netzabruf möglich.",
                    color = T.Faint, fontSize = 10.5.sp, modifier = Modifier.padding(top = 6.dp))
            }
        })
}

@Composable
fun DayEntryCard(date: String, active: List<String>, napMinutes: Int, onOpen: () -> Unit) {
    val parts = buildList {
        if (napMinutes > 0) add("Nap $napMinutes min")
        if (active.isNotEmpty()) add(active.joinToString(", ") { Confounders.label(it) })
    }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel)
        .clickable(onClick = onOpen).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (parts.isEmpty()) "Eintrag für heute: Powernap oder Störfaktor"
                else parts.joinToString(" · "),
                color = if (parts.isEmpty()) T.Text else T.Amber, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(date, color = T.Muted, fontSize = 11.5.sp)
        }
        Text("▾", color = T.Muted, fontSize = 14.sp)
    }
}

/**
 * Ein Dialog für alles, was den Tag von einem Normaltag unterscheidet — aber in zwei
 * klar getrennten Abschnitten.
 *
 * Ein Powernap gehört nicht in dieselbe Liste wie Alkohol oder Krankheit: Störfaktoren
 * ERKLÄREN eine Absenkung und schließen den Tag aus der Baseline aus, ein Nickerchen
 * ergänzt dagegen die Schlafbilanz. Beides in eine Auswahlliste zu werfen, würde zwei
 * gegensätzliche Bedeutungen vermischen. Gemeinsam ist ihnen nur, dass sie tagesbezogen
 * sind — deshalb ein Dialog, zwei Abschnitte.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DayEntryDialog(
    date: String, active: List<String>, napMinutes: Int,
    onDismiss: () -> Unit, onSave: (List<String>, Int) -> Unit,
) {
    val sel = remember { mutableStateListOf<String>().apply { addAll(active) } }
    var nap by remember { mutableStateOf(napMinutes) }
    // Feste Stufen statt Tastatureingabe: ein Tippen genügt, und Nickerchen sind ohnehin
    // keine minutengenaue Größe.
    val steps = listOf(0, 15, 20, 30, 45, 60, 90)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Button({ onSave(sel.toList(), nap) }) { Text("Speichern") } },
        dismissButton = { TextButton(onDismiss) { Text("Abbrechen") } },
        title = { Text("Eintrag für $date") },
        text = {
            Column {
                Text("POWERNAP", color = T.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                Text("Zählt zur Schlafbilanz dieses Tages. intervals.icu erfasst Nickerchen nicht.",
                    color = T.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    steps.forEach { v ->
                        val on = nap == v
                        Text(
                            if (v == 0) "keiner" else "$v min",
                            color = if (on) T.Bg else T.Text, fontSize = 12.5.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.clip(RoundedCornerShape(20.dp))
                                .background(if (on) T.Green else T.Chip)
                                .clickable { nap = v }
                                .padding(horizontal = 12.dp, vertical = 7.dp))
                    }
                }

                Spacer(Modifier.height(16.dp))
                Text("STÖRFAKTOR", color = T.Muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                Text("Bekannte, nicht trainingsbedingte Ursachen einer HRV-Absenkung. Der Wert bleibt " +
                    "im Score sichtbar, wird aber nicht als Trainingsüberlastung gedeutet und aus " +
                    "Baseline und Bandbreite ausgeschlossen.",
                    color = T.Muted, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
                Confounders.ALL.forEach { c ->
                    Row(Modifier.fillMaxWidth().clickable {
                        if (sel.contains(c.key)) sel.remove(c.key) else sel.add(c.key)
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(sel.contains(c.key), { if (it) sel.add(c.key) else sel.remove(c.key) })
                        Text(c.label, fontSize = 13.sp,
                            fontWeight = if (sel.contains(c.key)) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        })
}
