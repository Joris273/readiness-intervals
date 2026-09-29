package com.readiness.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.readiness.app.MainActivity
import com.readiness.app.data.SnapshotStore

/**
 * Minimales Widget: nur die Zahl, vollflächig in der Ampelfarbe.
 *
 * Auf einem Feld von 1×1 ist jedes zusätzliche Zeichen verlorene Lesbarkeit. Der Score
 * beantwortet die Frage „wie fit bin ich heute?" bereits allein, die Farbe liefert die
 * Einordnung ohne ein einziges Wort. Für alles Weitere genügt ein Tippen — die App ist
 * ohnehin nur eine Berührung entfernt.
 */
class ScoreMiniWidget : GlanceAppWidget() {

    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snap = SnapshotStore(context).load()
        val accent = runCatching { Color(android.graphics.Color.parseColor(snap?.colorHex ?: "#8A97A8")) }
            .getOrDefault(Color(0xFF8A97A8))
        provideContent {
            GlanceTheme {
                Box(
                    GlanceModifier.fillMaxSize().background(accent).cornerRadius(24.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        snap?.score?.toString() ?: "–",
                        style = TextStyle(ColorProvider(Color(0xFF04121C)), 40.sp, FontWeight.Bold),
                    )
                }
            }
        }
    }
}

class ScoreMiniWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScoreMiniWidget()
}
