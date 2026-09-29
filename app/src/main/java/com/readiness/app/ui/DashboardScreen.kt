package com.readiness.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.readiness.app.data.Snapshot
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Confounders

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: UiState,
    onReload: () -> Unit,
    onSaveSettings: (SettingsState) -> Unit,
    onSetCycles: (Int) -> Unit,
    onSetDayEntry: (String, List<String>, Int) -> Unit,
) {
    var showSettings by remember { mutableStateOf(false) }
    var showConfounder by remember { mutableStateOf(false) }
    var progOpen by remember { mutableStateOf(false) }
    val snap = state.snapshot

    /* Tippen auf den Kompaktstreifen soll zur Detailkarte springen. Der naive Weg über
       gemerkte Y-Koordinaten geht schief, sobald sich darüber liegende Karten in der Höhe
       ändern — etwa wenn die Zusammensetzung aufgeklappt ist oder eine Warnzeile
       erscheint. BringIntoViewRequester rechnet die Position zum Zeitpunkt des Aufrufs
       aus und trifft deshalb immer. */
    val bring = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()

    /* Die Activity zeichnet bewusst randlos (enableEdgeToEdge), damit der Hintergrund
       bis unter Status- und Navigationsleiste durchläuft. Ohne Inset-Einrückung liegt
       die Kopfzeile dann aber UNTER der Uhr und der Akkuanzeige — die Schaltflächen
       sind dort weder sichtbar noch antippbar, weil die Systemleiste die Berührung
       abfängt. Deshalb: Hintergrund über die volle Fläche, Inhalt eingerückt.
       safeDrawing deckt Statusleiste, Navigationsleiste und Display-Aussparung
       (Notch/Punch-Hole) gemeinsam ab. */
    /* Ziehen zum Aktualisieren ist auf Android die erwartete Geste. Sie ersetzt das
       Symbol nicht, sondern ergänzt es — und liefert nebenbei die Rückmeldung, die beim
       reinen Symboldruck gefehlt hat. */
    /* Der Indikator gehört zur GESTE, nicht zum Ladevorgang. Bindet man ihn an den
       allgemeinen Ladezustand, erscheint er auch beim Start, beim Zyklenwechsel und beim
       Speichern eines Tageseintrags — dort hat er nichts verloren und wirkt wie ein
       Fehler. Deshalb ein eigener Zustand, der nur beim Ziehen gesetzt und mit dem Ende
       des Ladens zurückgenommen wird. */
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(state.loading) { if (!state.loading) pulled = false }

    PullToRefreshBox(
        isRefreshing = pulled && state.loading,
        onRefresh = { pulled = true; onReload() },
        modifier = Modifier.fillMaxSize().background(T.Bg),
    ) {
        Column(
            Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            // Kopfzeile mit etwas Abstand nach oben; IconButton liefert bereits 48 dp
            // Grundfläche, was der empfohlenen Mindestgröße für Berührungsziele entspricht.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Readiness", color = T.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton({ showSettings = true }) { Icon(Icons.Filled.Settings, "Einstellungen", tint = T.Text) }
                // Sichtbare Rückmeldung: das Symbol dreht sich, solange gearbeitet wird
                val spin = rememberInfiniteTransition(label = "spin")
                val angle by spin.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                    label = "angle")
                val busy = state.loading || state.filling
                IconButton(onReload, enabled = !state.loading) {
                    Icon(Icons.Filled.Refresh, "Neu laden",
                        tint = if (busy) T.Green else T.Text,
                        modifier = if (busy) Modifier.rotate(angle) else Modifier)
                }
            }

            if (state.settings.apiKey.isBlank() && !state.settings.demo) {
                Spacer(Modifier.height(12.dp))
                Card2 { Text("Trage zuerst deinen intervals.icu-API-Key unter ⚙ ein.", color = T.Text, fontSize = 14.sp) }
            }

            snap?.thresholds?.staleMessage?.let {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(T.Amber.copy(alpha = .12f)).padding(12.dp)) {
                    Text("⚠ $it", color = T.Amber, fontSize = 12.5.sp)
                }
            }

            Spacer(Modifier.height(12.dp))
            ScoreCard(snap)

            // Schwellenwerte direkt unter dem Zustand — sie ordnen alle Zonenangaben ein
            snap?.thresholds?.let {
                Spacer(Modifier.height(10.dp))
                ThresholdChips(it)
            }

            Spacer(Modifier.height(12.dp))
            snap?.let { RecoCard(it) }
            snap?.progression?.let {
                Spacer(Modifier.height(12.dp))
                ProgressionStrip(it) {
                    progOpen = true
                    scope.launch { bring.bringIntoView() }
                }
            }
            Spacer(Modifier.height(12.dp))
            snap?.let { TileGrid(it.tiles) }

            snap?.hrvDate?.let { date ->
                Spacer(Modifier.height(12.dp))
                DayEntryCard(date, snap.confounders, snap.napMinutesToday) { showConfounder = true }
            }

            snap?.progression?.let {
                Spacer(Modifier.height(12.dp))
                Box(Modifier.bringIntoViewRequester(bring)) {
                    ProgressionCard(it, progOpen, { progOpen = !progOpen }, onSetCycles)
                }
            }

            // Verlaufsdiagramme
            snap?.chart?.takeIf { it.size >= 2 }?.let { pts ->
                Spacer(Modifier.height(12.dp))
                TsbHrvChart(pts)
                Spacer(Modifier.height(12.dp))
                LoadChart(pts)
            }

            if (state.loading) { Spacer(Modifier.height(14.dp)); Centered("Lade Daten …", T.Muted) }
            else if (state.filling) { Spacer(Modifier.height(14.dp)); Centered("Kraftdaten werden im Hintergrund ergänzt …", T.Faint) }
            state.error?.let { Spacer(Modifier.height(14.dp)); Centered("Fehler: $it", T.Red) }

            Spacer(Modifier.height(24.dp))
            /* Version direkt in der Fußzeile, nicht nur im Einstellungsdialog.
               Ein falsch installierter Stand hat schon zweimal zu einer Fehlersuche am
               Modell geführt, obwohl schlicht eine ältere APK lief. Die Zahl gehört
               dorthin, wo man sie ohne Suchen sieht. */
            Centered("Readiness ${state.settings.appVersion} · " +
                (if (state.settings.demo) "Demo mit synthetischen Daten" else "Datenquelle intervals.icu") +
                " · kein Medizinprodukt", T.Faint, 11.sp)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSettings) SettingsDialog(state.settings, state.cacheKb, { showSettings = false }) { showSettings = false; onSaveSettings(it) }
    if (showConfounder && snap?.hrvDate != null)
        DayEntryDialog(snap.hrvDate, snap.confounders, snap.napMinutesToday, { showConfounder = false }) { causes, nap ->
            showConfounder = false; onSetDayEntry(snap.hrvDate, causes, nap)
        }
}

@Composable private fun Centered(t: String, c: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.TextUnit = 13.sp) =
    Text(t, color = c, fontSize = size, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())

@Composable private fun Card2(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(16.dp), content = content)

@Composable
private fun ScoreCard(snap: Snapshot?) {
    val accent = T.hex(snap?.colorHex ?: "#8A97A8")
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(190.dp)) {
            val pct = (snap?.score ?: 0) / 100f
            Canvas(Modifier.size(190.dp)) {
                val stroke = Stroke(width = 24f, cap = StrokeCap.Round)
                drawArc(T.Line, -90f, 360f, false, style = stroke, size = Size(size.width, size.height))
                drawArc(accent, -90f, 360f * pct, false, style = stroke, size = Size(size.width, size.height))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(snap?.score?.toString() ?: "–", color = T.Text, fontSize = 56.sp, fontWeight = FontWeight.Bold)
                Text("Zustandsscore / 100", color = T.Muted, fontSize = 12.sp)
                Text(snap?.word ?: "", color = accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        snap?.let {
            Spacer(Modifier.height(14.dp))
            it.scoreContext?.let { ctx ->
                Text(ctx, color = T.Text, fontSize = 11.5.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
            }
            Text((if (it.renormalized) "Teilweise fehlende Daten — Gewichte neu normiert · " else "") + "Datenstand ${it.dataDate}",
                color = T.Muted, fontSize = 11.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = { open = !open },
                border = BorderStroke(1.dp, T.Line),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = T.Text),
            ) {
                Text(if (open) "Zusammensetzung verbergen ▴" else "Zusammensetzung anzeigen ▾", fontSize = 13.sp)
            }
            if (open) Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(8.dp))
                it.components.forEach { c -> ComponentRow(c) }
                Text("Basis-Score ${it.baseScore ?: "–"}" + (if (it.deduction > 0) " − ${it.deduction} Belastung" else "") +
                    " = ${it.score ?: "–"}", color = T.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
                if (it.limits.isNotEmpty()) Text("Limitierende Faktoren: " + it.limits.joinToString("; "),
                    color = T.Amber, fontSize = 11.5.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun ComponentRow(c: Snapshot.Component) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${c.name} · ${c.weightPct} %", color = T.Text, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(c.sub?.let { "$it/100" } ?: "–", color = T.hex(c.colorHex), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
        Text(c.explanation, color = T.Muted, fontSize = 12.sp)
        val frac = ((c.sub ?: 0).coerceIn(0, 100)) / 100f
        Box(Modifier.fillMaxWidth().height(5.dp).padding(top = 3.dp).clip(RoundedCornerShape(3.dp)).background(T.Line)) {
            Box(Modifier.fillMaxWidth(frac).height(5.dp).clip(RoundedCornerShape(3.dp)).background(T.hex(c.colorHex)))
        }
    }
}

@Composable
private fun RecoCard(snap: Snapshot) {
    val accent = T.hex(snap.colorHex)
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(14.dp)) {
        Box(Modifier.size(14.dp).clip(RoundedCornerShape(7.dp)).background(accent))
        Column(Modifier.padding(start = 12.dp)) {
            Text(snap.recoTitle, color = T.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(snap.recoText, color = T.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThresholdChips(t: Snapshot.Thresholds) {
    val items = buildList {
        t.outdoorFtp?.let { add("FTP outdoor $it W") }
        t.indoorFtp?.let { add("FTP indoor $it W") }
        t.eftp?.let { add("eFTP $it W") }
        t.lthr?.let { add("LTHR $it") }
        t.maxHr?.let { add("HF max $it") }
    }
    if (items.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach {
            Text(it, color = T.Text, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(T.Chip)
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }
    }
}

@Composable
private fun TileGrid(tiles: List<Snapshot.Tile>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { t ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(T.Panel).padding(12.dp)) {
                        Text(t.label.uppercase(), color = T.Muted, fontSize = 10.5.sp)
                        Text(t.value, color = T.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Text(t.sub, color = T.Muted, fontSize = 11.sp)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
