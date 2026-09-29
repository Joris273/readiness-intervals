package com.readiness.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.readiness.app.MainActivity
import com.readiness.app.data.Snapshot
import com.readiness.app.data.SnapshotStore

/**
 * Homescreen-Widget, responsiv über drei Größen. Liest ausschließlich den gespeicherten
 * Snapshot — kein Netzwerkzugriff, damit es sofort und auch offline erscheint.
 */
class ScoreWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = SnapshotStore(context).load()
        provideContent { GlanceTheme { Body(snap) } }
    }

    @Composable
    private fun Body(snap: Snapshot?) {
        val size = LocalSize.current
        val accent = parse(snap?.colorHex ?: "#8A97A8")
        val root = GlanceModifier.fillMaxSize().background(Color(0xFF131A24)).cornerRadius(20.dp)
            .clickable(actionStartActivity<MainActivity>()).padding(12.dp)
        when {
            size.height < MEDIUM.height && size.width < MEDIUM.width -> Small(snap, accent, root)
            size.height < LARGE.height -> Medium(snap, accent, root)
            else -> Large(snap, accent, root)
        }
    }

    @Composable private fun Small(s: Snapshot?, a: Color, root: GlanceModifier) =
        Column(root, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Badge(s, a, 64.sp)
            Text(s?.word ?: "—", style = TextStyle(ColorProvider(a), 12.sp, FontWeight.Medium))
        }

    @Composable private fun Medium(s: Snapshot?, a: Color, root: GlanceModifier) =
        Row(root, verticalAlignment = Alignment.CenterVertically) {
            Badge(s, a, 44.sp)
            Column(GlanceModifier.padding(start = 12.dp)) {
                Text(s?.recoTitle ?: "Keine Daten", maxLines = 2,
                    style = TextStyle(ColorProvider(a), 15.sp, FontWeight.Bold))
                Text(keyLine(s), maxLines = 2, style = TextStyle(ColorProvider(Color(0xFF8A97A8)), 12.sp))
            }
        }

    @Composable private fun Large(s: Snapshot?, a: Color, root: GlanceModifier) =
        Column(root) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Badge(s, a, 44.sp)
                Column(GlanceModifier.padding(start = 12.dp)) {
                    Text(s?.recoTitle ?: "Keine Daten", style = TextStyle(ColorProvider(a), 16.sp, FontWeight.Bold))
                    Text(keyLine(s), style = TextStyle(ColorProvider(Color(0xFF8A97A8)), 12.sp))
                }
            }
            Text(shortText(s), maxLines = 4, modifier = GlanceModifier.padding(top = 8.dp),
                style = TextStyle(ColorProvider(Color(0xFFC7D0DC)), 12.sp))
        }

    @Composable private fun Badge(s: Snapshot?, a: Color, fs: TextUnit) =
        Box(GlanceModifier.size(if (fs.value > 50) 96.dp else 68.dp).background(a).cornerRadius(18.dp),
            contentAlignment = Alignment.Center) {
            Text(s?.score?.toString() ?: "–", style = TextStyle(ColorProvider(Color(0xFF04121C)), fs, FontWeight.Bold))
        }

    private fun keyLine(s: Snapshot?): String {
        val tsb = s?.tiles?.firstOrNull { it.label.contains("TSB") }?.value
        val hrv = s?.tiles?.firstOrNull { it.label.startsWith("HRV") }?.sub
        return listOfNotNull(tsb?.let { "TSB $it" }, hrv).joinToString(" · ").ifEmpty { "Zum Aktualisieren tippen" }
    }
    private fun shortText(s: Snapshot?): String {
        val t = s?.recoText ?: "Öffne die App und aktualisiere den Score."
        return if (t.length > 180) t.take(177) + "…" else t
    }
    private fun parse(hex: String) = runCatching { Color(android.graphics.Color.parseColor(hex)) }
        .getOrDefault(Color(0xFF8A97A8))

    companion object {
        val SMALL = DpSize(120.dp, 120.dp)
        val MEDIUM = DpSize(220.dp, 120.dp)
        val LARGE = DpSize(250.dp, 200.dp)
    }
}
