package com.readiness.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fehlalarmquote und Trennschärfe bei REALISTISCHEM Rauschen.
 *
 * Jede Reihe ist eine unabhängige, 120 Tage lange Wellness-Historie mit typischer
 * Tag-zu-Tag-Streuung (Ln-rMSSD SD 0,08, Ruhepuls SD 2,5 bpm). Bewertet wird jeweils
 * der letzte Tag. Seeds sind fest, das Ergebnis ist reproduzierbar.
 *
 * Ausgangslage vor der Korrektur (dieselben 500 Reihen): an unauffälligen Tagen 39,4 %
 * nicht GRÜN, nach einem harten Tag 33,8 % ROT — das Tagesflag bei 0,5 SWC hat
 * Rauschen als Erholungsdefizit gelesen.
 */
class MonteCarloTest {

    private val runs = 500

    /** Unauffällige Physiologie, kein Training: fast immer GRÜN, praktisch nie ROT. */
    @Test fun unauffaelligeTage() {
        val r = rates(runs) { seed -> noisyWellness(seed) to emptyList() }
        println("Unauffällig:              $r")
        assertTrue("nicht GRÜN: $r", r.notGreen < 0.10)
        assertTrue("ROT: $r", r.red < 0.02)
    }

    /** Nach einem harten Tag bei unauffälliger Physiologie: kein erzwungener Ruhetag. */
    @Test fun nachHartemTag() {
        val r = rates(runs) { seed -> noisyWellness(seed) to listOf(intervals(1)) }
        println("Nach hartem Tag:          $r")
        assertTrue("ROT: $r", r.red < 0.05)
    }

    /** Echte Absenkung um 2 SD über fünf Tage muss erkannt werden. */
    @Test fun echteSuppressionWirdErkannt() {
        val r = rates(runs) { seed -> noisyWellness(seed, lnHrvShiftSd = -2.0, shiftDays = 5) to emptyList() }
        println("Suppression −2 SD × 5 T.: $r")
        assertTrue("erkannt: $r", r.notGreen >= 0.80)
    }
}
