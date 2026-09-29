package com.readiness.app

import com.readiness.app.domain.ScoreEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teilscore-Funktionen: Referenzwerte, Knickpunkte, ungültige Eingaben. */
class ScoreFunctionsTest {

    /** Form in % der Fitness: Knickpunkte nach intervals.icu/Coggan. */
    @Test fun form() {
        assertEquals(100, ScoreEngine.scoreForm(5.0))
        assertEquals(100, ScoreEngine.scoreForm(15.0))
        assertEquals(100, ScoreEngine.scoreForm(25.0))
        assertEquals(85, ScoreEngine.scoreForm(-10.0))
        assertEquals(68, ScoreEngine.scoreForm(-20.0))
        assertEquals(50, ScoreEngine.scoreForm(-30.0))
        assertEquals(15, ScoreEngine.scoreForm(-60.0))
        assertEquals(65, ScoreEngine.scoreForm(80.0))
        assertNull(ScoreEngine.scoreForm(null))
        assertNull(ScoreEngine.scoreForm(Double.NaN))
    }

    /** Keine Sprünge: benachbarte Werte unterscheiden sich über den ganzen Bereich um höchstens 2 Punkte. */
    @Test fun formStetig() {
        var prev = ScoreEngine.scoreForm(-80.0)!!
        var x = -80.0
        while (x <= 80.0) {
            val s = ScoreEngine.scoreForm(x)!!
            assertTrue("Sprung bei $x: $prev → $s", kotlin.math.abs(s - prev) <= 2)
            prev = s; x += 0.5
        }
    }

    /** HRV in SD-Einheiten: 100 bis −0,5 SD, linear auf 15 bei −3 SD, oberhalb nie abgewertet. */
    @Test fun hrv() {
        assertEquals(100, ScoreEngine.scoreHrv(0.0))
        assertEquals(100, ScoreEngine.scoreHrv(2.5))
        assertEquals(100, ScoreEngine.scoreHrv(-0.5))
        assertEquals(83, ScoreEngine.scoreHrv(-1.0))
        assertEquals(49, ScoreEngine.scoreHrv(-2.0))
        assertEquals(15, ScoreEngine.scoreHrv(-3.0))
        assertEquals(15, ScoreEngine.scoreHrv(-6.0))
        assertNull(ScoreEngine.scoreHrv(null))
        assertNull(ScoreEngine.scoreHrv(Double.NaN))
    }

    /** Stetigkeit an der Bandkante: kein Sprung zwischen −0,5 und knapp darunter. */
    @Test fun hrvStetig() {
        val a = ScoreEngine.scoreHrv(-0.5)!!; val b = ScoreEngine.scoreHrv(-0.51)!!
        assertTrue(a - b <= 1)
    }

    /** Ruhepuls gegen die eigene Streuung: 100 bis +0,5 SD, linear bis 20 bei +3 SD. */
    @Test fun ruhepulsRelativ() {
        assertEquals(100, ScoreEngine.scoreRestingHr(0.0))
        assertEquals(100, ScoreEngine.scoreRestingHr(0.5))
        assertEquals(68, ScoreEngine.scoreRestingHr(1.5))
        assertEquals(20, ScoreEngine.scoreRestingHr(3.0))
        assertEquals(20, ScoreEngine.scoreRestingHr(6.0))
    }

    /** Ohne Streuung (zu wenig Verlauf) weiterhin absolut in bpm. */
    @Test fun ruhepulsAbsolutOhneHistorie() {
        assertEquals(100, ScoreEngine.scoreRestingHr(null, 0.0))
        assertEquals(50, ScoreEngine.scoreRestingHr(null, 5.0))
        assertEquals(45, ScoreEngine.scoreRestingHr(null, 6.0))
        assertNull(ScoreEngine.scoreRestingHr(null, Double.NaN))
        assertNull(ScoreEngine.scoreRestingHr(Double.NaN))
    }

    @Test fun schlaf() {
        // Schlafscore roh (keine Historie) und Dauer gegen Bedarf je zur Hälfte
        assertEquals(84, ScoreEngine.scoreSleep(71.0, null, 5.9, 6.1, 6.1))
        assertEquals(100, ScoreEngine.scoreSleep(null, null, 6.4, 6.1, 6.1))
        assertNull(ScoreEngine.scoreSleep(null, null, null, 6.1, 6.1))
        // 71 ist für diese Person normal (z = 0) — kein Abzug
        assertEquals(100, ScoreEngine.scoreSleep(71.0, 0.0, 7.5, 7.5, 7.5))
        // für diese Person schlecht (z = −2), Dauer unbekannt
        assertEquals(49, ScoreEngine.scoreSleep(80.0, -2.0, null, null, null))
        // absolut sehr schlechter Schlaf deckelt, auch wenn die Dauer stimmt
        assertEquals(40, ScoreEngine.scoreSleep(35.0, null, 8.0, 8.0, 8.0))
    }
}
