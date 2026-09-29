package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Formaufbau: Verdikt, Versuchserkennung („Maximum braucht Maximalversuch“), EF-Bereinigung. */
class ProgressionTest {

    private fun progScen(c0: Double, c1: Double, ef0: Double, ef1: Double,
                         dec0: Double, dec1: Double, lc0: Int? = null, lc1: Int? = null): Progression {
        val wl = (119 downTo 0).map { WellnessDay(d(it), c0 + (c1 - c0) * (119 - it) / 119.0, 50.0, 44.0, 42.0, 23400.0, 71.0) }
        val ss = (83 downTo 0 step 3).map { i ->
            val rec = i < 42
            val tq = if (lc0 != null) TorqueMetrics(
                d60 = if (rec) lc1 else lc0, n60 = 3,
                d300 = if (rec) (lc1!! * 0.8).toInt() else (lc0 * 0.8).toInt(), n300 = 2, p300 = 300,
                efficiency = if (rec) 1.80 * (lc1!!.toDouble() / lc0) else 1.80, peakTorque30s = 44.0) else null
            ride(i, 80.0, 0.72, 5400.0, mapOf(1 to 1800, 2 to 2600, 4 to 400),
                np = (if (rec) ef1 else ef0) * 140, dec = if (rec) dec1 else dec0, tq = tq)
        }
        return ProgressionAnalyzer.analyze(wl, ss, AnalysisConfig(cycles = 1), TODAY)
    }

    private fun verdict(expect: ProgressionVerdict, p: Progression) =
        assertEquals("drivers=${p.drivers} decliners=${p.decliners}", expect, p.verdict)

    @Test fun aufbau() = verdict(ProgressionVerdict.PRODUCTIVE, progScen(45.0, 62.0, 1.60, 1.70, 5.5, 1.8, 300, 330))
    @Test fun nurKraftSteigt() = verdict(ProgressionVerdict.FOCUSED, progScen(57.0, 57.0, 1.65, 1.65, 4.0, 4.0, 300, 330))
    @Test fun plateau() = verdict(ProgressionVerdict.PLATEAU, progScen(57.0, 57.0, 1.65, 1.65, 4.0, 4.0, 320, 320))
    @Test fun lastHochAntwortFaellt() = verdict(ProgressionVerdict.LOAD_UP_RESPONSE_DOWN, progScen(45.0, 62.0, 1.70, 1.58, 3.2, 5.4, 340, 305))
    @Test fun taper() = verdict(ProgressionVerdict.DELOAD_WORKS, progScen(65.0, 52.0, 1.62, 1.68, 4.2, 3.6, 310, 325))
    @Test fun detraining() = verdict(ProgressionVerdict.DETRAINING, progScen(65.0, 50.0, 1.70, 1.58, 3.4, 4.6, 340, 305))

    // ---- Versuchserkennung: ein Maximum belegt eine untere Schranke ----

    private fun tq(d60: Int, ratio: Double) = TorqueMetrics(
        d60 = d60, n60 = 20,                       // viel Exposition, das war der alte Trugschluss
        d300 = (d60 / ratio).toInt(), n300 = 8,
        p300 = 300, efficiency = 1.80, efficiencyHr = 150, peakTorque30s = 44.0)

    private fun kraftScen(d60Old: Int, ratioOld: Double, d60New: Int, ratioNew: Double): Progression {
        val ss = (83 downTo 0 step 3).map { i ->
            val rec = i < 42
            ride(i, 80.0, 0.72, 5400.0, mapOf(1 to 1800, 2 to 2600, 4 to 400),
                np = 231.0, dec = 4.0, tq = if (rec) tq(d60New, ratioNew) else tq(d60Old, ratioOld))
        }
        return ProgressionAnalyzer.analyze(wellnessSeries(), ss, AnalysisConfig(cycles = 1), TODAY)
    }
    private fun d60Of(p: Progression) = p.durations.first { it.key == DurationKey.D60 }

    /** Der gemeldete Fall: vorher 1-min-Profil, jetzt nur 5×5 min — Bestwert 20 % tiefer. */
    @Test fun scheinrueckgangWirdVerworfen() {
        val p = kraftScen(312, 1.25, 248, 1.05)
        assertNull(d60Of(p).deltaPct)
        assertTrue(d60Of(p).suppressedNoAttempt)
        assertEquals(0, d60Of(p).attemptsNow)
        assertEquals(14, d60Of(p).attemptsPrev)
        assertFalse(p.markersUsed.contains(Marker.D60))
        assertTrue(p.noAttemptMarkers.contains(Marker.D60))
    }

    @Test fun echterRueckgangZaehlt() {
        val p = kraftScen(312, 1.25, 280, 1.25)
        assertTrue((d60Of(p).deltaPct ?: 0.0) < -5)
        assertTrue(p.markersUsed.contains(Marker.D60))
    }

    @Test fun anstiegOhneVersuchZaehlt() {
        val p = kraftScen(280, 1.25, 312, 1.05)
        assertNotNull(d60Of(p).deltaPct)
        assertFalse(d60Of(p).suppressedNoAttempt)
        assertTrue(p.markersUsed.contains(Marker.D60))
    }

    @Test fun profilUndHfTest() {
        assertTrue(EffortQuality.isAttempt(TorqueMetrics(d60 = 300, d300 = 240), DurationKey.D60, null))
        assertFalse(EffortQuality.isAttempt(TorqueMetrics(d60 = 252, d300 = 240), DurationKey.D60, null))
        assertTrue(EffortQuality.isAttempt(TorqueMetrics(d300 = 250, efficiencyHr = 172), DurationKey.D300, 175.0))
        assertFalse(EffortQuality.isAttempt(TorqueMetrics(d300 = 250, efficiencyHr = 148), DurationKey.D300, 175.0))
    }

    // ---- HF-Decke, NP, Rolle ----

    /* Ein einzelner Artefaktwert darf die HF-Decke nicht dauerhaft anheben (M7). */
    @Test fun hfDeckeRobustGegenArtefakt() {
        val normal = (1..20).map { ride(it * 3, tq = TorqueMetrics(d300 = 250, efficiencyHr = 165 + it % 6)) }
        val clean = EffortQuality.hrCeiling(normal)!!
        val withSpike = EffortQuality.hrCeiling(normal + ride(1, tq = TorqueMetrics(d300 = 250, efficiencyHr = 205)))!!
        assertTrue("Decke $clean → $withSpike", withSpike - clean < 2.0)
        assertEquals(clean, EffortQuality.hrCeiling(normal + ride(2, tq = TorqueMetrics(efficiencyHr = 235)))!!, 1e-9)
    }

    /* Ohne echte NP keine aerobe Effizienz — Durchschnittsleistung ist kein Ersatz (M6). */
    @Test fun keinNpFallbackAufDurchschnittsleistung() {
        val dto = com.readiness.app.data.ActivityDto(id = "x", averageWatts = 200.0)
        assertNull(dto.normalizedPower)
        assertEquals(200.0, dto.avgPower!!, 0.0)
    }

    /* Rolle bei gleicher Form mit höherer HF: gemischt in beiden Fenstern → Versatz heraus. */
    @Test fun rollenVersatzWirdHerausgerechnet() {
        val ss = (83 downTo 0 step 3).mapIndexed { k, i ->
            val indoor = k % 2 == 0
            // Rolle: NP/HF 1,55 statt 1,65; im aktuellen Fenster mehr Rolle als vorher
            val trainer = if (i < 42) k % 3 != 0 else indoor && k % 4 == 0
            ride(i, 80.0, 0.72, 5400.0, mapOf(1 to 1800, 2 to 2600), np = 140 * (if (trainer) 1.55 else 1.65),
                hr = 140.0, dec = 4.0, trainer = trainer)
        }
        val p = ProgressionAnalyzer.analyze(wellnessSeries(), ss, AnalysisConfig(cycles = 1), TODAY)
        assertEquals(EfEnvironment.MIXED_ADJUSTED, p.efEnvironment)
        assertTrue("EF-Delta ${p.efDeltaPct}", kotlin.math.abs(p.efDeltaPct ?: 99.0) < 1.0)
    }

    // ---- Multiplizität: viele verrauschte Marker ohne echten Effekt ----

    /**
     * Zwölf Wochen gleiche Last und gleiche wahre Leistung; jeder Marker streut nur so,
     * wie er es in echten Daten tut (EF ≈ 7,5 %, Entkopplung einige Prozentpunkte, Maxima
     * und Kraft-Effizienz ≈ 5 %). Jede „Verbesserung" ist hier ein Fehlurteil.
     */
    private fun noisyNoEffect(seed: Int, gainNow: Double = 0.0): Progression {
        val n = Noise(seed)
        val ss = (83 downTo 0 step 3).map { i ->
            val g = if (i < 42) 1 + gainNow else 1.0
            val d60 = (330 * g * (1 + 0.05 * n.gauss())).toInt()
            val tq = TorqueMetrics(d60 = d60, n60 = 3, d300 = (d60 / 1.25 * (1 + 0.03 * n.gauss())).toInt(), n300 = 2,
                d600 = (d60 / 1.38 * (1 + 0.03 * n.gauss())).toInt(), n600 = 1, p300 = 300,
                efficiency = 1.80 * g * (1 + 0.05 * n.gauss()), efficiencyHr = 150, peakTorque30s = 44.0)
            ride(i, 80.0, 0.72, 5400.0, mapOf(1 to 1800, 2 to 2600, 4 to 400),
                np = 140 * 1.65 * g * (1 + 0.075 * n.gauss()), dec = 3.0 + 3.0 * n.gauss(), tq = tq)
        }
        return ProgressionAnalyzer.analyze(wellnessSeries(), ss, AnalysisConfig(cycles = 1), TODAY)
    }

    /* Gegenprobe: eine echte Verbesserung von 8 % in Effizienz und Kraft wird trotz
       strengerer Bänder erkannt. */
    @Test fun echterFortschrittWirdErkannt() {
        val runs = 300
        val found = (1..runs).count {
            noisyNoEffect(it, gainNow = 0.08).verdict in setOf(ProgressionVerdict.PRODUCTIVE, ProgressionVerdict.FOCUSED)
        }
        val rate = found.toDouble() / runs
        println("Echter Fortschritt (+8 %) erkannt: ${"%.1f".format(rate * 100)} %")
        assertTrue("erkannt: $rate", rate >= 0.80)
    }

    @Test fun keinScheinfortschrittDurchRauschen() {
        val runs = 300
        val positive = (1..runs).count {
            noisyNoEffect(it).verdict in setOf(ProgressionVerdict.PRODUCTIVE, ProgressionVerdict.FOCUSED)
        }
        val rate = positive.toDouble() / runs
        println("Scheinfortschritt ohne echten Effekt: ${"%.1f".format(rate * 100)} %")
        assertTrue("Fehlurteil „Verbesserung“: $rate", rate < 0.10)
    }

    /* NP/HF wächst mit der Intensität. Beide Fenster haben DIESELBE wahre Effizienz
       (1,50 + Intensität − 0,60), nur bei 0,65 gegen 0,80 gefahren. Roh ergäbe das
       +9,7 %, also einen Fortschritt, den es nicht gibt. */
    @Test fun intensitaetWirdHerausgerechnet() {
        val ss = (83 downTo 0 step 3).mapIndexed { k, i ->
            val rec = i < 42
            val inten = if (rec) (if (k % 2 == 0) 0.77 else 0.83) else (if (k % 2 == 0) 0.62 else 0.68)
            ride(i, 80.0, inten, 5400.0, mapOf(1 to 1800, 2 to 2600),
                np = 140.0 * (1.50 + inten - 0.60), hr = 140.0, dec = 4.0)
        }
        val p = ProgressionAnalyzer.analyze(wellnessSeries(), ss, AnalysisConfig(cycles = 1), TODAY)
        assertTrue(p.efIntensityAdjusted)
        assertTrue("EF-Delta ${p.efDeltaPct}", abs(p.efDeltaPct ?: 99.0) < 1.0)
    }
}
