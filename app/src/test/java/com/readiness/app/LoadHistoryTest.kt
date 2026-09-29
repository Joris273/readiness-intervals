package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Zonenparser und Belastungserkennung (harte Reize, Serien, Blockgrenze). */
class LoadHistoryTest {

    @Test fun sweetSpotBandZaehltNichtMit() {
        val zt = listOf(
            Zones.ZoneEntry("Z1", null, 3216), Zones.ZoneEntry("Z2", null, 1731),
            Zones.ZoneEntry("Z3", null, 837), Zones.ZoneEntry("Z4", null, 237),
            Zones.ZoneEntry("Z5", null, 85), Zones.ZoneEntry("Z6", null, 50),
            Zones.ZoneEntry("Z7", null, 4), Zones.ZoneEntry("SS", null, 396))
        val p = Zones.parseZoneSeconds(zt)
        assertEquals(237, Zones.secondsWhere(p) { it == 4 })
        assertEquals(139, Zones.secondsWhere(p) { it >= 5 })
        assertEquals(54, Zones.secondsWhere(p) { it >= 6 })
        assertEquals(6160, Zones.secondsWhere(p) { it >= 1 })
    }

    /* Bei unauffälligen Markern ist ein zweiter Qualitätstag zulässig — die starre
       48-Stunden-Regel widersprach der Blockperiodisierungs- und HRV-Steuerungsliteratur. */
    @Test fun gesternIntervalleMarkerUnauffaellig() =
        assertVerdict("Gestern Intervalle", Verdict.GREEN, evaluate(listOf(intervals(1))))

    @Test fun gesternIntervalleRuhepulsErhoeht() = assertVerdict("Gestern Intervalle, RHR +3", Verdict.AMBER,
        evaluate(listOf(intervals(1)), wl = wellnessSeries().today { it.copy(restingHr = 45.0) }))

    @Test fun dreiQualitaetstageInFolge() =
        assertVerdict("Drei Qualitätstage", Verdict.AMBER, evaluate((1..3).map { intervals(it) }))

    @Test fun socialRideIstKeinHarterReiz() = assertVerdict("Social Ride", Verdict.GREEN,
        evaluate(listOf(ride(1, 60.0, 0.68, 10800.0, mapOf(2 to 8000, 4 to 1320, 5 to 240)))))

    @Test fun eBikeZaehltNicht() = assertVerdict("E-Bike", Verdict.GREEN,
        evaluate(listOf(ride(1, 9.0, 0.92, 1500.0, mapOf(4 to 400, 5 to 900), "EBikeRide"))))

    @Test fun lockererDauerlauf() = assertVerdict("Dauerlauf 30 min Z4", Verdict.GREEN,
        evaluate(listOf(ride(1, 55.0, 0.78, 3000.0, mapOf(4 to 1800), "Run"))))

    @Test fun laufintervalleMarkerUnauffaellig() = assertVerdict("Laufintervalle", Verdict.GREEN,
        evaluate(listOf(ride(1, 70.0, 0.92, 3600.0, mapOf(4 to 600, 5 to 1080), "Run"))))

    /* Sechs gleichförmige Tage: Serie UND hohe Monotonie — zwei Signale, Ruhetag. */
    @Test fun sechsMonotoneTrainingstageErzwingenRuhetag() = assertVerdict("6 monotone Tage", Verdict.RED,
        evaluate((1..6).map { ride(it, 90.0, 0.75, 5400.0, mapOf(2 to 5000, 4 to 200)) }))

    /* Sechs lockere, wechselnde Tage: Ruhetag fällig, aber nicht erzwungen — nur Deckel. */
    @Test fun sechsLockereTageDeckelnNur() {
        val loads = listOf(40.0, 95.0, 50.0, 100.0, 45.0, 85.0)
        val r = evaluate(loads.mapIndexed { i, l -> ride(i + 1, l, 0.65, l * 60, mapOf(1 to 1200, 2 to (l * 60).toInt() - 1200)) })
        assertTrue(r.loadHistory.restDayDue)
        assertVerdict("6 lockere Tage", Verdict.AMBER, r)
    }

    /* Foster-Monotonie über abgeschlossene Tage: eine heutige Einheit ändert sie nicht. */
    @Test fun monotonieOhneHeutigenTag() {
        val past = (1..6).map { ride(it, 60.0 + 10 * (it % 3)) }
        val morning = LoadHistoryAnalyzer.analyze(past, 60.0, false, TODAY)
        val evening = LoadHistoryAnalyzer.analyze(past + ride(0, 120.0), 60.0, false, TODAY)
        assertEquals(morning.monotony, evening.monotony, 1e-9)
    }

    /* Serientage stecken in ATL/Form; der Abzug zählt sie nicht noch einmal. */
    @Test fun serieOhneDoppelabzug() {
        val r = LoadHistoryAnalyzer.analyze((1..5).map { ride(it, 60.0 + 15 * (it % 2)) }, 60.0, false, TODAY)
        assertEquals(0, r.deduction)
    }

    /* Ein als Artefakt markierter HRV-Einbruch darf den zweiten Qualitätstag nicht blockieren (M3). */
    @Test fun artefaktBlockiertZweitenQualitaetstagNicht() {
        val wl = alternatingWellness().today { it.copy(hrv = hrvAtZ(-3.0)) }
        val cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("artifact")))
        assertVerdict("Artefakt nach hartem Tag", Verdict.GREEN, evaluate(listOf(intervals(1)), cfg, wl))
        assertVerdict("gleicher Tag ohne Markierung", Verdict.AMBER, evaluate(listOf(intervals(1)), wl = wl))
    }

    /* ACWR: EWMA, entkoppelt, ohne den heutigen Tag. Konstante Last → ≈ 1. */
    @Test fun acwrEwmaEntkoppelt() {
        val steady = (1..120).map { ride(it, 60.0) }
        val a = evaluate(steady).metrics.acwr!!
        assertEquals(1.0, a, 0.05)
        assertEquals(a, evaluate(steady + ride(0, 300.0)).metrics.acwr!!, 1e-9)
    }

    @Test fun heuteQualitaetsreizAbsolviert() = assertVerdict("Heute Qualität", Verdict.DONE,
        evaluate(listOf(ride(0, 72.0, 0.84, 3600.0, mapOf(4 to 1200), "VirtualRide", torqueWork = 1200))))
}
