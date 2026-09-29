package com.readiness.app.domain

/**
 * Domänenmodelle als typisierte data classes.
 *
 * Im Prototyp waren dies ungetypte Objektbeutel mit über vierzig Feldern — beim Port
 * die häufigste Fehlerquelle, weil ein Tippfehler im Feldnamen still zu `undefined`
 * wird statt zu einem Fehler. Hier fängt das der Compiler ab.
 *
 * Bewusst OHNE Serialisierungs-Annotationen und ohne Android-Importe: die Domäne bleibt
 * damit reines Kotlin und ist ohne Emulator testbar. Die Übersetzung in ein speicherbares
 * Format übernimmt die Datenschicht.
 */

/** Eine Trainingseinheit, bereits auf das reduziert, was die Auswertung braucht. */
data class Session(
    val id: String,
    val type: String,
    val trainer: Boolean = false,
    val localDate: String,          // ISO yyyy-MM-dd, lokales Datum der Einheit
    val movingTimeSec: Double = 0.0,
    val trainingLoad: Double = 0.0,
    val intensity: Double? = null,  // bereits normalisiert (Bruch, nicht Prozent)
    val eftp: Double? = null,
    val normalizedPower: Double? = null,
    val avgHeartRate: Double? = null,
    val decoupling: Double? = null,
    val zoneSeconds: Map<Int, Int> = emptyMap(),   // echte Zonen Z1..Z7, ohne Bänder
    val hasZones: Boolean = false,
    /** Aus den Roh-Streams gewonnene Kraftkennwerte; null bis ausgewertet. */
    val torque: TorqueMetrics? = null,
    /** Sekunden Drehmomentarbeit heute/gestern (≥85 % FTP bei ≤70 rpm). */
    val torqueWorkSec: Int = 0,
)

/** Ein Tageseintrag aus der Wellness-Reihe. */
data class WellnessDay(
    val date: String,
    val ctl: Double? = null,
    val atl: Double? = null,
    val hrv: Double? = null,
    val restingHr: Double? = null,
    val sleepSeconds: Double? = null,
    val sleepScore: Double? = null,
    val subjective: SubjectiveEntry? = null,
)

/**
 * Subjektive Tagesangaben (intervals.icu-Wellness). Skala 1 = bestens … 4 = schlecht für
 * alle Items — auch Motivation und Stimmung sind so gepolt (1 = sehr hoch bzw. super).
 */
data class SubjectiveEntry(
    val soreness: Double? = null,
    val fatigue: Double? = null,
    val stress: Double? = null,
    val mood: Double? = null,
    val motivation: Double? = null,
    val injury: Double? = null,
) {
    val items: List<Double> get() = listOfNotNull(soreness, fatigue, stress, mood, motivation, injury).filter { it.isFinite() && it >= 1 }
    val hasAny: Boolean get() = items.isNotEmpty()
}

/** Schwellenwerte aus den Sport-Einstellungen. */
data class Thresholds(
    val outdoorFtp: Int? = null,
    val indoorFtp: Int? = null,
    val eftp: Int? = null,
    val lthr: Int? = null,
    val maxHr: Int? = null,
    val staleMessage: String? = null,
)

/** Aufbereitete Tageskennwerte, die in den Score fließen. */
data class Metrics(
    val dataDate: String? = null,
    /** Aktuelle CTL/ATL (neueste Zeile, ggf. inkl. heutiger Einheit) — für Anzeige und Lastbezug. */
    val ctl: Double? = null,
    val atl: Double? = null,
    /**
     * Form, mit der man in den Tag GEHT: CTL − ATL des Vortags. Die heutige Zeile enthält
     * nach dem Hochladen die heutige Einheit — sonst änderte sich der Morgen-Score durch
     * das Training, das er bewerten soll.
     */
    val tsb: Double? = null,
    /** TSB relativ zur CTL des Vortags, in Prozent. */
    val tsbPct: Double? = null,
    val formCtl: Double? = null,
    val formAtl: Double? = null,
    /** Kein Vortag verfügbar — Form aus der heutigen Zeile. */
    val formFromToday: Boolean = false,
    val hrv: Double? = null,
    val hrvDate: String? = null,
    val hrvDeviationPct: Double? = null,
    val hrvLn: Double? = null,
    /** Basis: Mittel der Ln-rMSSD über das Referenzfenster (winsorisiert). */
    val hrvLnBase: Double? = null,
    /** Stichproben-SD der Tageswerte im Referenzfenster (mit Rausch-Floor). */
    val hrvLnSd: Double? = null,
    val hrvBandLo: Double? = null,
    val hrvBandHi: Double? = null,
    /** Band aus weniger als [ScoringParams.HrvParams.bandFullValues] Werten — nur Anzeige, keine Entscheidung. */
    val hrvBandProvisional: Boolean = false,
    val hrvBandValues: Int = 0,
    /** Tageswert in SD-Einheiten gegenüber der Basis. */
    val hrvZ: Double? = null,
    /** 7-Tage-Mittel der Ln-rMSSD (inkl. Messtag). */
    val hrvRoll7: Double? = null,
    /** TREND: 7-Tage-Mittel unter dem Normalband. Das ist das Entscheidungssignal. */
    val hrvSuppressed: Boolean = false,
    /** Tageswert deutlich unter der Basis — nur als Bestätigung anderer Befunde. */
    val hrvAcuteDrop: Boolean = false,
    /** Akuter Einbruch, bestätigt durch Trend oder Ruhepuls. */
    val hrvRecoveryAlarm: Boolean = false,
    val hrvAbove: Boolean = false,
    val hrvUnusual: Boolean = false,
    /** Variationskoeffizient der Ln-rMSSD über 7 Tage und sein üblicher Wert (Plews 2012). */
    val hrvCv7: Double? = null,
    val hrvCvRef: Double? = null,
    /** 7-Tage-Mittel deutlich ÜBER dem Band bei Belastungs- oder Instabilitätszeichen. */
    val hrvSaturation: Boolean = false,
    // Wochentrend nach Plews: 7-Tage-Mittel gegen vier unabhängige Wochenblöcke
    val hrvWeek: Double? = null,
    val hrvWeekRef: Double? = null,
    val hrvWeekDevPct: Double? = null,
    val hrvWeekDown: Boolean = false,     // Anzeige ab 0,5 SD
    val hrvWeekAlarm: Boolean = false,    // Handlung erst ab 1,5 SD
    val hrvWeekUp: Boolean = false,
    /** Alter der jeweils verwendeten Messung in Tagen (0 = heute). */
    val hrvAgeDays: Int? = null,
    val restingHr: Double? = null,
    /** Basis und Streuung des Ruhepulses über das Referenzfenster. */
    val restingHrBase: Double? = null,
    val restingHrDiff: Double? = null,
    val restingHrSd: Double? = null,
    val rhrZ: Double? = null,
    val rhrAgeDays: Int? = null,
    /** Ruhepuls erhöht (z ≥ Schwelle, ohne Historie absolut) — bestätigt andere Befunde. */
    val rhrElevated: Boolean = false,
    val sleepScore: Double? = null,
    val sleepScoreBase: Double? = null,
    val sleepScoreZ: Double? = null,
    val sleepScoreAgeDays: Int? = null,
    /** Letzte Nacht einschließlich eines Naps desselben Datums. */
    val sleepHours: Double? = null,
    val sleepNapHours: Double = 0.0,
    val sleepAgeDays: Int? = null,
    val sleepAvgHours: Double? = null,
    val sleep7Effective: Double? = null,  // inkl. Powernaps
    val sleep30: Double? = null,
    val sleepNeed: Double? = null,
    val sleepNeedManual: Boolean = false,
    /** Eigener Median lag unter dem normativen Boden — Bedarf wurde angehoben. */
    val sleepNeedFloored: Boolean = false,
    /** Subjektive Angaben des Tages, Belastungsindex 0 (bestens) … 1 (schlecht) nach Hooper. */
    val subjective: SubjectiveEntry? = null,
    val subjectiveIndex: Double? = null,
    val subjectiveBase: Double? = null,
    val subjectiveZ: Double? = null,
    val subjectiveAgeDays: Int? = null,
    val subjectiveScaleMax: Double = 4.0,
    /** Deutlich schlechter als üblich (oder ohne Historie absolut schlecht). */
    val subjectiveElevated: Boolean = false,
    /** Erschöpfung oder Muskelkater auf der schlechtesten Stufe. */
    val subjectiveWorst: Boolean = false,
    val napMinutes: Int = 0,
    val sleepDeficit: Double? = null,
    val acwr: Double? = null,
    // Störfaktor-Status des HRV-Messtags
    val confounded: Boolean = false,
    /** Schlüssel der eingetragenen Störfaktoren (siehe [Confounders]). */
    val confounderKeys: List<String> = emptyList(),
    val confIllness: Boolean = false,
    val confInvalid: Boolean = false,
    val confExternal: Boolean = false,
)

/** Tagesaggregat für die Belastungshistorie. */
data class DayLoad(
    var load: Double = 0.0,
    var maxIf: Double = 0.0,
    var z5plus: Int = 0,
    var z6plus: Int = 0,
    var z4: Int = 0,
    var torque: Int = 0,
    var durationSec: Double = 0.0,
    var zonedSec: Double = 0.0,     // nur Einheiten, die Zonen beisteuern
    var hasZones: Boolean = false,
)

data class DayStat(
    val z5: Int, val z6: Int, val z4: Int, val torque: Int,
    val zonedSec: Double, val load: Int, val maxIf: Double, val hasZones: Boolean,
)

/** Welches System der letzte Qualitätsreiz vorwiegend belastet hat. */
enum class StimulusType { NONE, VO2MAX, THRESHOLD, VOLUME, MIXED }

data class LoadHistory(
    val deduction: Int,
    val notes: List<LoadNoteCode>,
    val consecutiveDays: Int,
    val hardYesterday: Boolean,
    val bigYesterday: Boolean,
    val monotony: Double,
    val weekLoad: Double,
    val capIntensity: Boolean,
    val forceRest: Boolean,
    /** Lange Serie ohne weiteres Warnsignal: Ruhetag einplanen, Intensität deckeln — aber nicht erzwingen. */
    val restDayDue: Boolean = false,
    /** Qualitätstage innerhalb der aktuellen Trainingsserie. */
    val qualityDaysInStreak: Int = 0,
    val hardReasons: List<HardReason>,
    val yesterday: DayStat?,
    val trainedToday: Boolean,
    val hardToday: Boolean,
    val todayReasons: List<HardReason>,
    val today: DayStat?,
    /** Aufeinanderfolgende Tage mit Qualitätsreiz, heute rückwärts gezählt. */
    val qualityStreak: Int = 0,
    val yesterdayType: StimulusType = StimulusType.NONE,
    val todayType: StimulusType = StimulusType.NONE,
    /** Relative Größe des gestrigen Reizes, 0..1 — Grundlage der abgestuften Bewertung. */
    val yesterdaySeverity: Double = 0.0,
)

data class ScoreComponent(
    val id: ComponentId,
    val weight: Double,
    val sub: Int?,
    val effectiveWeight: Double = 0.0,
    /** Alter der zugrunde liegenden Messung; gestern zählt halb, älter gar nicht. */
    val ageDays: Int? = null,
    /** Messung zu alt — angezeigt, aber nicht bewertet. */
    val stale: Boolean = false,
)

data class BaseScore(val total: Int?, val components: List<ScoreComponent>, val renormalized: Boolean)

enum class Severity { AMBER, RED }

enum class Verdict { GREEN, AMBER, RED, DONE, UNKNOWN }

/**
 * @param verdict endgültige Ampel; DONE, wenn heute schon trainiert wurde
 * @param morning Ampel aus der Morgenlage (vor einer heutigen Einheit)
 */
data class Recommendation(
    val verdict: Verdict,
    val morning: Verdict = verdict,
    val notes: List<Note> = emptyList(),
)

/** Kraftkennwerte einer Einheit, aus den Roh-Streams gewonnen. */
data class TorqueMetrics(
    val d60: Int? = null, val n60: Int = 0,
    val d300: Int? = null, val n300: Int = 0,
    val d600: Int? = null, val n600: Int = 0,
    val p300: Int? = null,
    val efficiency: Double? = null,     // W/bpm bei ≤70 rpm, ab 5 min
    val efficiencyW: Int? = null,
    val efficiencyHr: Int? = null,
    val peakTorque30s: Double? = null,  // Nm, nur Orientierung
    val resampled: Boolean = false,
)

/** @param band Rauschband dieses Markers in derselben Einheit wie `deltaPct` */
data class MarkerDelta(val marker: Marker, val deltaPct: Double, val band: Double)

/** Worauf sich die Antwortseite des Verdikts stützt. */
enum class ResponseBasis {
    NONE,
    /** Mehrere Marker gleichgerichtet über ihrem Rauschband. */
    CONSENSUS,
    /** Ein Marker weit über seinem Rauschband, keine Gegenbewegung. */
    STRONG_SINGLE,
    /** Gegenläufige Marker, entschieden über die Bilanz in Rauschband-Einheiten. */
    BALANCE,
    /** Nur ein Marker knapp über dem Rauschband — allein kein Beleg. */
    WEAK_SINGLE,
    /** Nichts bewegt sich über das Rauschband hinaus. */
    FLAT,
}

/** Welche Einheiten in die aerobe Effizienz eingingen. */
enum class EfEnvironment {
    OUTDOOR, INDOOR,
    /** Beide Umgebungen in beiden Fenstern: Rollen-Versatz geschätzt und herausgerechnet. */
    MIXED_ADJUSTED,
    /** Umgebung fällt mit dem Zeitraum zusammen: nicht bereinigbar, nur eingeschränkt vergleichbar. */
    MIXED_RAW,
}

data class DurationProgress(
    val key: DurationKey,
    val now: Int?, val prev: Int?,
    /** Rauschband in Prozent. */
    val band: Double = 0.0,
    /* nNow/nPrev zählen EXPOSITION (überlappungsfreie Fenster je Einheit) und bleiben nur
       noch als Diagnose erhalten. Entschieden wird über attemptsNow/attemptsPrev — die
       Zahl der Einheiten mit einem tatsächlichen Maximalversuch. */
    val nNow: Int, val nPrev: Int,
    val deltaPct: Double?, val thin: Boolean, val separationDays: Int?,
    val attemptsNow: Int = 0, val attemptsPrev: Int = 0,
    /** Rückgang verworfen, weil im aktuellen Zeitraum kein Maximalversuch stattfand. */
    val suppressedNoAttempt: Boolean = false,
)

data class ProgressionDiag(
    val rides: Int = 0, val ridesInWindow: Int = 0, val withPowerHr: Int = 0,
    val longEnough: Int = 0, val aerobic: Int = 0, val eftpValues: Int = 0,
    val npKey: String? = null, val hrKey: String? = null,
)

data class TorqueScan(val total: Int, val missing: Int, val openOlder: Int)

data class Progression(
    val ok: Boolean,
    val windowDays: Int,
    val verdict: ProgressionVerdict,
    /** Dosis: CTL-Trend +1/0/−1, null ohne Vergleichswert. */
    val dose: Int? = null,
    /** Antwort: +1/0/−1, null ohne Marker. */
    val response: Int? = null,
    /** HRV-Chronik fällt bei steigender Last — Zeichen für Maladaptation. */
    val hrvChronWarning: Boolean = false,
    val responseBasis: ResponseBasis = ResponseBasis.NONE,
    /** Bei WEAK_SINGLE: der Marker, der allein nicht reichte. */
    val weakMarker: Marker? = null,
    /** Rauschbänder je Marker (Prozent bzw. Prozentpunkte bei der Entkopplung). */
    val eftpBand: Double = 0.0, val efBand: Double = 0.0, val decBand: Double = 0.0, val lcEfBand: Double = 0.0,
    val efEnvironment: EfEnvironment? = null,
    val eftpNow: Int? = null, val eftpPrev: Int? = null,
    val eftpDeltaPct: Double? = null, val eftpSeparationDays: Int? = null,
    /** eFTP-Rückgang verworfen: kein Antritt im aktuellen Fenster, der eFTP setzen konnte. */
    val eftpSuppressedNoAttempt: Boolean = false,
    val efNow: Double? = null, val efPrev: Double? = null,
    val efDeltaPct: Double? = null, val efN: Int = 0, val efNPrev: Int = 0,
    /** NP/HF wurde um die Intensität bereinigt (sonst: Rohmediane, zu wenige Einheiten). */
    val efIntensityAdjusted: Boolean = false,
    val ctlNow: Double? = null, val ctlPrev: Double? = null,
    val ctlDeltaPct: Double? = null, val rampPerWeek: Double? = null,
    val deloadNow: Boolean = false,
    val share12: Double? = null, val share3: Double? = null, val share4: Double? = null,
    val zoneHours: Double? = null,
    val hrvChronNow: Double? = null, val hrvChronPrev: Double? = null, val hrvChronDeltaPct: Double? = null,
    /* Aerobe Entkopplung (Seiler): wurde berechnet und floss ins Verdikt ein, fehlte aber
       im Ergebnis — und damit in der Anzeige. Der Nutzer sah einen Marker im Urteil, den
       er nirgends nachvollziehen konnte. */
    val decNow: Double? = null, val decPrev: Double? = null,
    val decDeltaPp: Double? = null, val decN: Int = 0, val decNPrev: Int = 0,
    val durations: List<DurationProgress> = emptyList(),
    val lcEfNow: Double? = null, val lcEfPrev: Double? = null,
    val lcEfDeltaPct: Double? = null, val lcEfN: Int = 0, val lcEfNPrev: Int = 0, val lcEfThin: Boolean = true,
    val peakTorqueNow: Double? = null, val peakTorquePrev: Double? = null,
    val markersUsed: List<Marker> = emptyList(),
    val drivers: List<Marker> = emptyList(),
    val decliners: List<Marker> = emptyList(),
    /** Marker, deren Rückgang mangels Maximalversuch nicht bewertet wurde. */
    val noAttemptMarkers: List<Marker> = emptyList(),
    val diag: ProgressionDiag = ProgressionDiag(),
    val torqueScan: TorqueScan? = null,
)

/** Ein Tagespunkt für die Verlaufsdiagramme. */
data class ChartPoint(
    val date: String,
    val ctl: Double? = null,
    val atl: Double? = null,
    val tsb: Double? = null,
    val hrv: Double? = null,
    val load: Double = 0.0,
)

/** Das vollständige Auswertungsergebnis der Domänenschicht. */
data class ReadinessResult(
    val score: Int?,
    val baseScore: Int?,
    val deduction: Int,
    val metrics: Metrics,
    val components: List<ScoreComponent>,
    val limitingFactors: List<LimitingFactor>,
    val loadHistory: LoadHistory,
    val recommendation: Recommendation,
    val thresholds: Thresholds,
    val progression: Progression,
    val chart: List<ChartPoint> = emptyList(),
)
