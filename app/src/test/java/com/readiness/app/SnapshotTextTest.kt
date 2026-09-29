package com.readiness.app

import com.readiness.app.data.SnapshotMapper
import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Die Texte entstehen seit dem Umbau ausschließlich im SnapshotMapper. Geprüft wird, dass
 * jede Ampel ihren festen Titel trägt (Widget und Benachrichtigung zeigen ihn) und dass
 * kein Befund ohne Text bleibt.
 */
class SnapshotTextTest {

    private fun snap(r: ReadinessResult, cfg: AnalysisConfig = AnalysisConfig()) = SnapshotMapper.map(r, cfg)

    @Test fun titelJeAmpel() {
        assertEquals("Grünes Licht für Intensität", snap(evaluate()).recoTitle)
        assertEquals("Nur Grundlage / Z2", snap(evaluate(wl = wellnessSeries().today { it.copy(hrv = 32.0) })).recoTitle)
        assertEquals("Ruhetag empfohlen", snap(evaluate(wl = wellnessSeries(ctl = 60.0, atl = 85.0))).recoTitle)
        assertEquals("Zu wenig Daten", snap(evaluate(wl = emptyList())).recoTitle)
        assertEquals("Qualitätseinheit erledigt", snap(evaluate(listOf(
            ride(0, 72.0, 0.84, 3600.0, mapOf(4 to 1200), "VirtualRide", torqueWork = 1200)))).recoTitle)
        assertEquals("Einheit erledigt", snap(evaluate(listOf(ride(0, 40.0, 0.6, 3600.0, mapOf(2 to 3600))))).recoTitle)
    }

    /* Die frühere starre 48-h-Regel widersprach der Blocklogik und darf nicht mehr auftauchen (M4). */
    @Test fun keineStarre48hRegelMehr() {
        val s = snap(evaluate(listOf(ride(0, 72.0, 0.84, 3600.0, mapOf(4 to 1200), "VirtualRide", torqueWork = 1200))))
        assertFalse(s.recoText.contains("48-h"))
        assertTrue(s.recoText.contains("Morgenwerte"))
    }

    @Test fun keinBefundOhneText() {
        val cases = listOf(
            evaluate(wl = wellnessSeries().today { it.copy(hrv = 30.0, restingHr = 55.0, sleepScore = 20.0) }),
            evaluate((1..6).map { ride(it, 90.0, 0.75, 5400.0, mapOf(2 to 5000, 4 to 200)) }),
            evaluate(listOf(intervals(1))),
            evaluate(cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("alcohol", "artifact")))),
        ) + (1..30).map { evaluate(wl = noisyWellness(it), sessions = listOf(intervals(1))) }
        cases.forEach { r ->
            val s = snap(r)
            assertTrue(s.recoText.isNotBlank())
            assertFalse("Platzhalter im Text: ${s.recoText}", s.recoText.contains("null"))
            s.components.forEach { assertTrue(it.name.isNotBlank() && it.explanation.isNotBlank()) }
            s.limits.forEach { assertTrue(it.isNotBlank()) }
            assertEquals(r.limitingFactors.size, s.limits.size)
        }
    }
}
