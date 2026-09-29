package com.readiness.app

import com.readiness.app.data.ActivityDto
import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fehlende, veraltete, leere und ungültige Eingaben. */
class MissingDataTest {

    @Test fun keineWellnessDaten() {
        val r = evaluate(wl = emptyList())
        assertNull(r.score)
        assertEquals(Verdict.UNKNOWN, r.recommendation.verdict)
    }

    @Test fun nurLeereZeilen() {
        val r = evaluate(wl = (29 downTo 0).map { WellnessDay(d(it)) })
        assertNull(r.score)
        assertEquals(Verdict.UNKNOWN, r.recommendation.verdict)
    }

    @Test fun nanWerteWerdenNichtBewertet() {
        val r = evaluate(wl = wellnessSeries().today { it.copy(hrv = Double.NaN, restingHr = Double.NaN) })
        assertEquals(1, r.metrics.hrvAgeDays)       // NaN heute → gestriger gültiger Wert
    }

    // ---- Mindestdatenlage ----

    /* Die Form ist ein Modellwert aus dem eigenen Training, keine Zustandsmessung. */
    @Test fun nurFormErgibtKeinenScore() {
        val r = evaluate(wl = (119 downTo 0).map { WellnessDay(d(it), ctl = 60.0, atl = 55.0) })
        assertNull(r.score)
        assertEquals(Verdict.UNKNOWN, r.recommendation.verdict)
    }

    @Test fun ohneAutonomenMarkerKeinGruen() {
        val wl = (119 downTo 0).map { WellnessDay(d(it), ctl = 60.0, atl = 55.0, sleepSeconds = 8 * 3600.0, sleepScore = 85.0) }
        val r = evaluate(wl = wl)
        assertEquals(Verdict.AMBER, r.recommendation.verdict)
        assertTrue(r.recommendation.notes.any { it.code == NoteCode.INSUFFICIENT_PHYSIOLOGY })
    }

    // ---- Aktualität ----

    @Test fun hrvVonVorgesternWirdNichtBewertet() {
        val wl = wellnessSeries().lastDays(2) { it.copy(hrv = null) }
        val r = evaluate(wl = wl)
        assertEquals(2, r.metrics.hrvAgeDays)
        val hrv = r.components.first { it.id == ComponentId.HRV }
        assertTrue(hrv.stale)
        assertNull(hrv.sub)
    }

    @Test fun hrvVonGesternZaehltHalb() {
        val fresh = evaluate().components.first { it.id == ComponentId.HRV }
        val old = evaluate(wl = wellnessSeries().today { it.copy(hrv = null) }).components.first { it.id == ComponentId.HRV }
        assertEquals(1, old.ageDays)
        assertEquals(fresh.weight * 0.5, old.weight, 1e-9)
    }

    /* Veraltete HRV darf keine Entscheidung tragen, auch wenn sie damals niedrig war. */
    @Test fun veralteterEinbruchLoestNichtsAus() {
        val wl = alternatingWellness().mapIndexed { i, w ->
            when (i) { 117 -> w.copy(hrv = hrvAtZ(-3.5)); 118, 119 -> w.copy(hrv = null); else -> w }
        }
        val m = evaluate(wl = wl).metrics
        assertFalse(m.hrvAcuteDrop || m.hrvSuppressed || m.hrvRecoveryAlarm)
    }

    // ---- Schlaf ----

    /* 0 Sekunden = Uhr nicht getragen, keine durchwachte Nacht. */
    @Test fun nullNaechteVerfaelschenDenWochenschnittNicht() {
        val base = evaluate(wl = wellnessSeries()).metrics.sleep7Effective!!
        val gaps = evaluate(wl = wellnessSeries().mapIndexed { i, w -> if (i in listOf(114, 116, 118)) w.copy(sleepSeconds = 0.0) else w })
        assertEquals(base, gaps.metrics.sleep7Effective!!, 1e-9)
    }

    /* Gewohnter Schlafmangel ist kein Bedarf: der eigene Median wird auf 7 h angehoben. */
    @Test fun bedarfHatNormativenBoden() {
        val m = evaluate(wl = wellnessSeries(sleepSec = 6.2 * 3600)).metrics
        assertEquals(7.0, m.sleepNeed!!, 1e-9)
        assertTrue(m.sleepNeedFloored)
        assertEquals(0.8, m.sleepDeficit!!, 1e-9)
    }

    @Test fun selbstGesetzterBedarfGilt() {
        val m = evaluate(cfg = AnalysisConfig(sleepNeedHours = 6.5), wl = wellnessSeries(sleepSec = 6.2 * 3600)).metrics
        assertEquals(6.5, m.sleepNeed!!, 1e-9)
        assertFalse(m.sleepNeedFloored)
    }

    @Test fun napZaehltZurNachtDesselbenTages() {
        val m = evaluate(cfg = AnalysisConfig(napMinutesByDay = mapOf(d(0) to 30)), wl = wellnessSeries()).metrics
        assertEquals(23400.0 / 3600 + 0.5, m.sleepHours!!, 1e-9)
    }

    // ---- Ruhepuls relativ ----

    /* +3 bpm: bei enger eigener Streuung ein Signal, bei weiter Streuung Rauschen. */
    @Test fun ruhepulsGegenEigeneStreuung() {
        val tight = alternatingWellness().map { it.copy(restingHr = 48.0 + if (it.date.last().code % 2 == 0) 1.0 else -1.0) }
            .today { it.copy(restingHr = 51.0) }
        val loose = alternatingWellness().map { it.copy(restingHr = 48.0 + if (it.date.last().code % 2 == 0) 4.0 else -4.0) }
            .today { it.copy(restingHr = 51.0) }
        val mt = evaluate(wl = tight).metrics
        val ml = evaluate(wl = loose).metrics
        assertTrue("eng: z=${mt.rhrZ}", mt.rhrElevated)
        assertFalse("weit: z=${ml.rhrZ}", ml.rhrElevated)
        assertNotEquals(mt.rhrZ, ml.rhrZ)
    }

    // ---- Datenaufbereitung ----

    /* IF-Skala je Abruf: eine sehr lockere Einheit mit 2,5 % ist nicht IF 2,5. */
    @Test fun intensitaetsskalaJeAbruf() {
        val acts = listOf(ActivityDto(id = "1", intensity = 72.0), ActivityDto(id = "2", intensity = 2.5))
        assertTrue(ActivityDto.intensityIsPercent(acts))
        assertFalse(ActivityDto.intensityIsPercent(listOf(ActivityDto(id = "3", intensity = 0.72))))
    }
}
