package com.readiness.app.domain

/**
 * ALLE Schwellen und Gewichte der Auswertung an einer Stelle.
 *
 * Vorher steckten sie als Literale in vier Dateien. Das machte zwei Dinge unmöglich:
 * nachzuvollziehen, welche Zahl welche Entscheidung trägt, und die Schwellen an echten
 * Daten zu kalibrieren, ohne den Code zu verändern. Die Werte sind Heuristiken — für die
 * Gewichtung eines Readiness-Composites gibt es in der Literatur keine validierten
 * Zahlen. Sie werden deshalb hier benannt und begründet, nicht im Rechenweg versteckt.
 *
 * Unveränderlich und durchgereicht wie [AnalysisConfig], damit parallele Läufe sich
 * nicht gegenseitig beeinflussen und die Domäne ohne Android testbar bleibt.
 */
data class ScoringParams(
    val weights: Weights = Weights(),
    val verdict: VerdictParams = VerdictParams(),
    val hrv: HrvParams = HrvParams(),
    val form: FormParams = FormParams(),
    val sleep: SleepParams = SleepParams(),
    val rhr: RhrParams = RhrParams(),
    val subjective: SubjectiveParams = SubjectiveParams(),
    val validity: ValidityParams = ValidityParams(),
    val limits: LimitParams = LimitParams(),
    val load: LoadParams = LoadParams(),
    val progression: ProgressionParams = ProgressionParams(),
    val effort: EffortParams = EffortParams(),
) {
    /**
     * Subjektive Angaben bilden akute und chronische Belastung empfindlicher ab als die
     * üblichen objektiven Marker (Saw 2016). Fehlt eine Komponente, werden die übrigen
     * umnormiert — ohne subjektive Angaben ergibt sich etwa die frühere Gewichtung.
     */
    data class Weights(
        val form: Double = 0.22,
        val hrv: Double = 0.30,
        val sleep: Double = 0.22,
        val rhr: Double = 0.10,
        val subjective: Double = 0.16,
    )

    /**
     * Ampelschwellen. Geprüft in `MonteCarloTest` (500 Reihen, Stand der Umstellung):
     * unauffällige Tage 6 % nicht GRÜN / 0 % ROT, nach hartem Tag 3 % ROT, echte
     * Suppression zu 94 % erkannt. Nachjustieren nur mit `BacktestTest` auf echten Daten.
     */
    data class VerdictParams(
        /** Basiswert ab hier GRÜN. */
        val green: Int = 78,
        /** Basiswert ab hier AMBER, darunter ROT. */
        val amber: Int = 58,
        /** Blockgrenze: so viele Qualitätstage in Folge, dann Entlastung. */
        val qualityStreakMax: Int = 3,
        /** Teilscore Schlaf, ab dem ein zweiter Qualitätstag noch „erholt" heißt. */
        val recoverySleepSub: Int = 60,
        /** ACWR ab hier als Hinweis (kein Gate). */
        val acwrNote: Double = 1.5,
    )

    /**
     * HRV-Steuerung nach Plews (2012/2013) und Vesterinen (2016): ENTSCHIEDEN wird über das
     * 7-Tage-Mittel von Ln-rMSSD gegen ein Normalband aus einer langen Referenz, nicht über
     * den Einzelwert. Ein Einzelwert unterhalb 0,5 SD tritt bei unveränderter Physiologie
     * an rund jedem dritten Tag auf — als Auslöser einer Empfehlung ist das Rauschen.
     */
    data class HrvParams(
        /** SWC als Anteil der Streuung (Plews/Hopkins: 0,5 SD) — definiert das angezeigte Band. */
        val swcFactor: Double = 0.5,
        /** Untergrenze der Ln-SD (≈ 5 % Messrauschen). */
        val sdFloor: Double = 0.0488,
        /** Referenzfenster für Basis und Streuung in Kalendertagen. */
        val refDays: Int = 60,
        /** Ab so vielen Referenzwerten gibt es überhaupt ein Band … */
        val bandMinValues: Int = 7,
        /** … und ab so vielen darf es Entscheidungen tragen (darunter „vorläufig"). */
        val bandFullValues: Int = 21,
        /** Ausreißer der Referenz: außerhalb Median ± k·(IQR/1,349) verworfen. */
        val outlierK: Double = 3.0,
        /** Rollierendes Mittel: Fenster in Tagen und Mindestzahl an Messungen. */
        val rollDays: Int = 7,
        val rollMin: Int = 4,
        /**
         * Trend-Schwelle für das 7-Tage-Mittel in SD der Tageswerte. Die SWC (0,5 SD) plus
         * die Unsicherheit des Mittels selbst (≈ SD/√7 ≈ 0,38 SD, davon rund zwei Drittel):
         * so bleibt die Fehlalarmquote bei stabiler Physiologie im niedrigen einstelligen
         * Prozentbereich, während eine echte Absenkung über mehrere Tage sicher erkannt wird.
         */
        val trendSd: Double = 0.75,
        /** Tageswert gilt als akuter Einbruch ab diesem z-Wert (Kiviniemi 2007: 1 SD) — nur als Bestätigung. */
        val acuteSd: Double = 1.0,
        /** Einzelwert darüber: ungewöhnlich hoch, möglicher Messartefakt. */
        val unusualSd: Double = 3.0,
        /** Teilscore-Deckel für ungewöhnlich hohe Einzelwerte. */
        val unusualCap: Int = 90,
        /** Sättigung: 7-Tage-Mittel über Basis + so viele SD … */
        val saturationSd: Double = 0.75,
        /** … bei CV-Anstieg auf das Vielfache des üblichen CV (Plews 2012) oder anderem Kontextmarker. */
        val cvRatio: Double = 1.5,
        /** Teilscore-Deckel bei Verdacht auf parasympathische Sättigung. */
        val saturationCap: Int = 80,
        /** Wochentrend: Anzeige ab 0,5 SD, Handlung ab diesem Vielfachen. */
        val weekActSd: Double = 1.5,
        val weekMinRefWeeks: Int = 3,
    )

    /**
     * Form RELATIV zur Fitness: TSB% = TSB / CTL (intervals.icu „Form %", Coggan).
     * TSB −20 bedeutet bei CTL 40 halbe Fitness als Ermüdung, bei CTL 100 den normalen
     * Aufbaualltag. Absolute Schwellen haben hoch Trainierte regelmäßig in Ruhetage
     * geschickt und schwach Trainierte zu mild bewertet.
     * Bänder (intervals.icu): > +25 Übergang, +5…+25 frisch, −10…+5 neutral,
     * −30…−10 produktive Ermüdung, < −30 hohes Risiko.
     */
    data class FormParams(
        /** Nenner-Untergrenze, damit TSB% bei sehr kleiner CTL nicht explodiert. */
        val minCtl: Double = 20.0,
        /** Form % darunter: Ruhetag. */
        val redBelowPct: Double = -35.0,
        /** Form % darunter: bestätigt andere Befunde, verhindert einen zweiten Qualitätstag. */
        val corroborateBelowPct: Double = -25.0,
        /** Form % darüber: Hinweis auf sinkende Fitness. */
        val veryHighAbovePct: Double = 25.0,
    )

    data class SleepParams(
        /** Fallback-Ziel, wenn weder Bedarf noch Historie vorliegen. */
        val defaultTargetH: Double = 7.5,
        /** Abzug, wenn die Nacht mehr als 1 h unter dem Wochenschnitt lag. */
        val shortNightPenalty: Int = 15,
        /** Historie für Bedarf und Schlafscore-Norm in Tagen und Mindestzahl an Nächten. */
        val needHistoryDays: Int = 60,
        val needMinNights: Int = 10,
        val week7MinNights: Int = 4,
        /** Normativer Boden des Bedarfs (Walsh 2021: Erwachsene 7–9 h). */
        val needFloorH: Double = 7.0,
        /** Defizit gegenüber Bedarf, ab dem es mit Bestätigung als limitierend gilt. */
        val deficitH: Double = 0.75,
        val deficitRedH: Double = 1.5,
        /** Absoluter Boden für den Wochenschnitt: darunter AMBER, unter `floorRedH` ROT. */
        val floorH: Double = 6.0,
        val floorRedH: Double = 5.0,
        /** Teilscore Schlaf, bis zu dem er einen autonomen Einzelbefund bestätigt. */
        val corroborateSub: Int = 50,
        /** Schlafscore-Norm: SD-Boden (Punkte) und Mindestzahl an Werten. */
        val scoreSdFloor: Double = 3.0,
        val scoreMinValues: Int = 14,
        /** Garmin-Schlafscore darunter deckelt den Teilscore absolut. */
        val scoreAbsoluteFloor: Int = 40,
    )

    data class RhrParams(
        /** Referenzfenster in Tagen, Mindestzahl an Werten für eine Streuung, SD-Boden in bpm. */
        val refDays: Int = 60,
        val minValues: Int = 14,
        val sdFloor: Double = 1.5,
        /** z-Wert, ab dem der Ruhepuls als erhöht gilt und Befunde bestätigt. */
        val corroborateZ: Double = 1.0,
        /** Ohne ausreichende Historie: absoluter Anstieg in bpm. */
        val corroborateBpm: Double = 2.0,
    )

    /** Subjektiver Index (Hooper-artig): Mittel der Items, auf 0 (bestens) … 1 (schlecht) normiert. */
    data class SubjectiveParams(
        val refDays: Int = 60,
        val minValues: Int = 14,
        val sdFloor: Double = 0.08,
        /** z-Wert, ab dem der Tag deutlich schlechter als üblich gilt. */
        val elevatedZ: Double = 1.0,
        /** Ohne Historie: Index ab hier „schlecht" (entspricht Mittel ≥ 3 von 4). */
        val elevatedAbsolute: Double = 2.0 / 3.0,
    )

    /** Aktualität der Messwerte. */
    data class ValidityParams(
        /** Wie weit maximal nach der letzten Messung gesucht wird (Anzeige). */
        val lookbackDays: Int = 7,
        /** Älter als so viele Tage: angezeigt, aber nicht bewertet. */
        val staleAfterDays: Int = 1,
        /** Gewicht einer Messung von gestern. */
        val yesterdayWeight: Double = 0.5,
    )

    data class LimitParams(
        /** Teilscore bis hier: kritisch (ROT, bei unbestätigtem autonomen Einzelbefund AMBER). */
        val critical: Int = 25,
        /** Teilscore bis hier: deutlich reduziert (AMBER). */
        val reduced: Int = 40,
        /** HRV-Tageswert: SD unter der Basis für „unter dem Normalband" (AMBER) bzw. „stark unterdrückt". */
        val hrvBelowSd: Double = 2.0,
        val hrvStrongSd: Double = 2.75,
    )

    data class LoadParams(
        /** Trainingstag ab max(minLoad, trainShareCtl × CTL). */
        val trainMinLoad: Double = 20.0,
        val trainShareCtl: Double = 0.4,
        /** Großer Umfangstag ab bigShareCtl × CTL. */
        val bigShareCtl: Double = 1.5,
        /** Ersatz-CTL, wenn keine vorliegt. */
        val ctlFallback: Double = 40.0,
        /** Drehmomentarbeit ab dieser Dauer gilt als Qualitätsreiz (s). */
        val torqueMinSec: Int = 480,
        val monotonyHigh: Double = 2.0,
        /** Ab so vielen Trainingstagen in Folge ist ein Ruhetag fällig (erzwungen nur mit weiterem Signal). */
        val streakRestDays: Int = 6,
        /**
         * Obergrenze des Belastungsabzugs. Er bildet nur noch ab, was CTL/ATL NICHT
         * enthalten: die Intensitätsstruktur von gestern und die Monotonie. Serientage
         * stecken bereits in ATL und damit in der Form-Komponente.
         */
        val deductionCap: Int = 20,
        /** ACWR (Williams 2017): EWMA-Zeitkonstanten in Tagen. */
        val acwrAcuteDays: Int = 7,
        val acwrChronicDays: Int = 28,
    )

    /**
     * Rauschbänder JE MARKER (Hopkins 2000): max(Mindestband, Typical Error des Vergleichs).
     * Ein gemeinsames Band von 2,5 % lag unter der Streuung der verrauschtesten Marker — mit
     * sieben Markern bewegte sich fast immer einer „signifikant", und ein einziger reichte
     * für ein positives Verdikt. Bei unveränderter Leistung ergab das in rund 30 % der Fälle
     * einen Scheinfortschritt.
     */
    data class ProgressionParams(
        val minBandEftp: Double = 2.0,
        val minBandEf: Double = 2.0,
        /** Entkopplung in Prozentpunkten. */
        val minBandDecPp: Double = 1.5,
        val minBandDuration: Double = 3.0,
        val minBandLcEf: Double = 2.0,
        /** Maxima (eFTP, Kraftdauern): Streuung eines Einzelbestwerts in % — Maximum zweier Fenster ohne Mittelung. */
        val maxStatNoisePct: Double = 2.0,
        /**
         * Ein Marker gilt als bewegt, wenn seine Änderung den Standardfehler der Differenz um
         * dieses Vielfache übersteigt (≈ 93 % einseitig). Bei nur einem Standardfehler läge
         * jeder Marker schon zufällig in jedem sechsten Vergleich „darüber".
         */
        val bandZ: Double = 1.5,
        /** Ein einzelner Marker trägt ein Verdikt nur ab diesem Vielfachen seines Bandes. */
        val strongFactor: Double = 2.0,
        /** Sonst braucht es mindestens so viele gleichgerichtete Marker. */
        val consensusMin: Int = 2,
        val efIntensityMin: Double = 0.60,
        val efIntensityMax: Double = 0.85,
    )

    data class EffortParams(
        /** d60/d300: 1-min-Maximalversuch. */
        val r1Min: Double = 1.12,
        /** d300/d600: 5-min-Maximalversuch. */
        val r2Min: Double = 1.04,
        /** d300/d600 darüber: 10-min-Fenster durch Pausen verwässert. */
        val r2Max: Double = 1.12,
        /** Anteil der eigenen HF-Decke für „ausbelastet". */
        val hrNearMax: Double = 0.96,
        /** HF-Decke: plausibler Bereich und Perzentil statt Maximum. */
        val hrMin: Double = 100.0,
        val hrMax: Double = 220.0,
        val hrCeilingPercentile: Double = 0.95,
    )

    companion object {
        val DEFAULT = ScoringParams()
    }
}
