package com.readiness.app.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll

class ScoreWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScoreWidget()
}

object WidgetUpdater {
    /** Beide Widget-Varianten gemeinsam aktualisieren. */
    suspend fun update(context: Context) {
        runCatching { ScoreWidget().updateAll(context) }
        runCatching { ScoreMiniWidget().updateAll(context) }
    }
}
