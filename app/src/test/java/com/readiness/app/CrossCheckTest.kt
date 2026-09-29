package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-End-Szenarien der Empfehlung: Erholungsmarker und Störfaktoren.
 *
 * Ursprünglich der Cross-Check gegen den JS-Prototyp. Geprüft wird das VERDIKT, nicht der
 * deutsche Titel — die Texte sind Darstellung und dürfen sich ändern, ohne dass die
 * wissenschaftliche Prüfung bricht.
 */
class CrossCheckTest {

    @Test fun normalTagIstGruen() =
        assertVerdict("Normaltag", Verdict.GREEN, evaluate())

    // ---- HRV bei realistischer Streuung (SD 0,08 in Ln-Einheiten) ----

    /* Der Kern der Korrektur: ein einzelner, mäßig niedriger Tag ist Rauschen. Früher
       deckelte schon −0,5 SWC — das traf rund jeden dritten normalen Tag. */
    @Test fun einzelnerMaessigerEinbruchBleibtGruen() {
        val wl = alternatingWellness().today { it.copy(hrv = hrvAtZ(-1.2)) }
        assertVerdict("HRV −1,2 SD an einem Tag", Verdict.GREEN, evaluate(wl = wl))
    }

    @Test fun deutlicherEinzeleinbruchDeckeltAufGrundlage() {
        val wl = alternatingWellness().today { it.copy(hrv = hrvAtZ(-3.0)) }
        assertVerdict("HRV −3 SD allein", Verdict.AMBER, evaluate(wl = wl))
    }

    @Test fun deutlicherEinbruchMitRuhepulsErzwingtRuhetag() {
        val wl = alternatingWellness().today { it.copy(hrv = hrvAtZ(-3.0), restingHr = 52.0) }
        assertVerdict("HRV −3 SD + Ruhepuls +4", Verdict.RED, evaluate(wl = wl))
    }

    /* Plews: das fallende 7-Tage-Mittel ist das Signal, auch wenn kein Einzeltag auffällt. */
    @Test fun abfallenderTrendDeckelt() {
        val wl = alternatingWellness().lastDays(5) { it.copy(hrv = hrvAtZ(-1.4)) }
        val r = evaluate(wl = wl)
        assertVerdict("5 Tage −1,4 SD", Verdict.AMBER, r)
        assertTrue(r.metrics.hrvSuppressed)
    }

    /* Le Meur 2013: hohe HRV unter hoher Last kann funktionelle Überlastung sein. */
    @Test fun hoheHrvUnterHoherLastIstVerdaechtig() {
        val wl = alternatingWellness(atl = 85.0).lastDays(7) { it.copy(hrv = hrvAtZ(1.6)) }
        val r = evaluate(wl = wl)
        assertTrue(r.metrics.hrvSaturation)
        assertTrue((r.components.first { it.id == ComponentId.HRV }.sub ?: 100) <= 80)
        assertFalse(r.recommendation.verdict == Verdict.GREEN)
    }

    @Test fun hoheHrvOhneKontextBleibtGut() {
        val wl = alternatingWellness().lastDays(7) { it.copy(hrv = hrvAtZ(1.6)) }
        val r = evaluate(wl = wl)
        assertFalse(r.metrics.hrvSaturation)
        assertVerdict("HRV hoch, Last normal", Verdict.GREEN, r)
    }

    @Test fun kurzeHistorieLoestNichtsAus() {
        val wl = alternatingWellness(days = 12).today { it.copy(hrv = hrvAtZ(-3.0)) }
        val r = evaluate(wl = wl)
        assertTrue(r.metrics.hrvBandProvisional)
        assertTrue(r.limitingFactors.none { it.component == ComponentId.HRV })
    }

    /* Ein einzelner Artefaktwert im Referenzfenster darf das Band nicht aufblähen. */
    @Test fun ausreisserImReferenzfensterWirdGekappt() {
        val clean = evaluate(wl = alternatingWellness())
        val spiked = evaluate(wl = alternatingWellness().mapIndexed { i, w -> if (i == 100) w.copy(hrv = 150.0) else w })
        val sdClean = clean.metrics.hrvLnSd!!; val sdSpiked = spiked.metrics.hrvLnSd!!
        assertTrue("SD $sdClean → $sdSpiked", sdSpiked < sdClean * 1.1)
    }

    // ---- Konstante Reihen (SD-Floor) ----

    @Test fun hrvEinbruchAlleinDeckeltAufGrundlage() {
        val supp = wellnessSeries().today { it.copy(hrv = 38.0) }
        assertVerdict("HRV −14 % allein", Verdict.AMBER, evaluate(wl = supp))
    }

    @Test fun tiefeErmuedungErzwingtRuhetag() =
        assertVerdict("TSB −25 bei CTL 60 (−42 %)", Verdict.RED, evaluate(wl = wellnessSeries(ctl = 60.0, atl = 85.0)))

    // ---- Form relativ zur Fitness ----

    /* TSB −20 ist bei CTL 100 normaler Aufbaualltag (−20 %) — früher pauschal ROT. */
    @Test fun gleicherTsbBeiHoherFitnessKeinRuhetag() {
        val r = evaluate(wl = wellnessSeries(ctl = 100.0, atl = 120.0))
        assertEquals(-20.0, r.metrics.tsbPct!!, 0.01)
        assertFalse(r.recommendation.verdict == Verdict.RED)
    }

    /* Derselbe absolute TSB bei niedriger Fitness ist die Hälfte der CTL: tiefe Ermüdung. */
    @Test fun gleicherTsbBeiNiedrigerFitnessRuhetag() =
        assertVerdict("CTL 40 / ATL 60 (−50 %)", Verdict.RED, evaluate(wl = wellnessSeries(ctl = 40.0, atl = 60.0)))

    /* Die heutige Zeile enthält nach dem Hochladen die heutige Einheit. Der Morgen-Score
       darf sich dadurch nicht ändern — er beschreibt die Bereitschaft VOR dem Training. */
    @Test fun heutigesTrainingAendertDenMorgenscoreNicht() {
        val morning = evaluate(wl = wellnessSeries())
        val afterRide = evaluate(listOf(intervals(0)), wl = wellnessSeries().today { it.copy(ctl = 58.5, atl = 66.0) })
        assertEquals(morning.baseScore, afterRide.baseScore)
        assertEquals(morning.metrics.tsb, afterRide.metrics.tsb)
    }

    private val low = wellnessSeries().today { it.copy(hrv = 32.0, restingHr = 47.0, sleepScore = 58.0) }

    @Test fun alkoholMildertAufGrundlage() = assertVerdict("Alkohol (extern)", Verdict.AMBER,
        evaluate(cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("alcohol"))), wl = low))

    @Test fun krankheitErzwingtRuhetag() = assertVerdict("Krankheit", Verdict.RED,
        evaluate(cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("illness"))), wl = low))

    @Test fun krankheitAuchBeiSonstGruen() = assertVerdict("Krankheit bei sonst grün", Verdict.RED,
        evaluate(cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("illness")))))

    private val hrvOnly = wellnessSeries().today { it.copy(hrv = 32.0) }

    @Test fun artefaktFaelltAusDerGewichtung() = assertVerdict("Messartefakt", Verdict.GREEN,
        evaluate(cfg = AnalysisConfig(confounders = mapOf(d(0) to listOf("artifact"))), wl = hrvOnly))

    @Test fun gleicherTagOhneMarkierung() =
        assertVerdict("Gleicher Tag ohne Markierung", Verdict.AMBER, evaluate(wl = hrvOnly))
}
