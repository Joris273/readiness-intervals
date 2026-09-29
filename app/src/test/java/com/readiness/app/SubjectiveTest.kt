package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Subjektive Angaben (Saw 2016, Hooper 1995): eigene Komponente, Limit nur mit Bestätigung. */
class SubjectiveTest {

    private val usual = SubjectiveEntry(soreness = 2.0, fatigue = 2.0, stress = 2.0, mood = 2.0, motivation = 2.0)

    private fun withSubjective(base: List<WellnessDay> = alternatingWellness(), today: SubjectiveEntry = usual) =
        base.mapIndexed { i, w ->
            // Historie mit leichter Streuung um „2", heute gezielt gesetzt
            val hist = usual.copy(fatigue = if (i % 3 == 0) 3.0 else 2.0)
            w.copy(subjective = if (i == base.size - 1) today else hist)
        }

    /* Ohne Angaben: dieselbe Rechnung wie vorher, nur umnormiert. */
    @Test fun fehlendeAngabenWerdenUmnormiert() {
        val r = evaluate(wl = alternatingWellness())
        val c = r.components.first { it.id == ComponentId.SUBJECTIVE }
        assertNull(c.sub)
        assertEquals(1.0, r.components.filter { it.sub != null }.sumOf { it.effectiveWeight }, 1e-9)
    }

    @Test fun ueblichesBefindenIstUnauffaellig() {
        val r = evaluate(wl = withSubjective())
        assertEquals(100, r.components.first { it.id == ComponentId.SUBJECTIVE }.sub)
        assertVerdict("übliches Befinden", Verdict.GREEN, r)
    }

    /* Schlechteste Stufe allein: Intensität deckeln, aber kein Ruhetag. */
    @Test fun schlechtesteStufeAlleinDeckelt() {
        val r = evaluate(wl = withSubjective(today = usual.copy(fatigue = 4.0, soreness = 4.0)))
        assertTrue(r.metrics.subjectiveWorst)
        assertVerdict("Erschöpfung 4/4 allein", Verdict.AMBER, r)
    }

    /* Mit objektiver Bestätigung (HRV-Einbruch plus Ruhepuls) wird daraus ein Ruhetag. */
    @Test fun mitObjektiverBestaetigungRuhetag() {
        val base = alternatingWellness().today { it.copy(hrv = hrvAtZ(-1.5), restingHr = 52.0) }
        val r = evaluate(wl = withSubjective(base, usual.copy(fatigue = 4.0)))
        assertVerdict("Erschöpfung 4/4 + HRV/Ruhepuls", Verdict.RED, r)
    }

    @Test fun skala1bis5WirdErkannt() {
        val r = evaluate(wl = withSubjective(today = usual.copy(fatigue = 5.0)))
        assertEquals(5.0, r.metrics.subjectiveScaleMax, 0.0)
        assertTrue(r.metrics.subjectiveWorst)
    }

    @Test fun scoreFunktion() {
        assertEquals(100, ScoreEngine.scoreSubjective(0.0, null))
        assertEquals(15, ScoreEngine.scoreSubjective(3.0, null))
        assertEquals(100, ScoreEngine.scoreSubjective(null, 1.0 / 3))
        assertEquals(15, ScoreEngine.scoreSubjective(null, 1.0))
        assertNull(ScoreEngine.scoreSubjective(null, null))
    }
}
