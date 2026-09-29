package com.readiness.app

import com.readiness.app.data.ScoreHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Einordnung in die eigene Verteilung: nur Morgenwerte, nur das gewählte Fenster. */
class ScoreHistoryTest {

    @Test fun morgenwertBleibtNachDemTrainingStehen() {
        val morning = ScoreHistory.merge(emptyMap(), "2026-07-27", 84, "2026-01-01", overwrite = true)
        val after = ScoreHistory.merge(morning, "2026-07-27", 71, "2026-01-01", overwrite = false)
        assertEquals(84, after["2026-07-27"])
        // noch kein Wert vorhanden: auch nach dem Training wird er erfasst
        val first = ScoreHistory.merge(emptyMap(), "2026-07-27", 71, "2026-01-01", overwrite = false)
        assertEquals(71, first["2026-07-27"])
    }

    @Test fun nurDasFensterZaehlt() {
        val old = (100 downTo 70).associate { d(it) to 40 }          // weit außerhalb von 60 Tagen
        val recent = (20 downTo 1).associate { d(it) to 80 }
        val ctx = ScoreHistory.classify(old + recent, 70, d(0), window = 60)
        assertNotNull(ctx)
        assertEquals(20, ctx!!.days)
        assertEquals(0, ctx.percentile)                               // unter allen 20 jüngeren Werten
        assertNull(ScoreHistory.classify(old, 70, d(0), window = 60)) // alte Werte allein reichen nicht
    }
}
