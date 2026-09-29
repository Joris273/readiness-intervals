package com.readiness.app.domain

/**
 * Begründungen als STRUKTUR statt als Satz.
 *
 * Die Domäne hat früher fertige deutsche Sätze erzeugt. Das hatte drei Folgen: die Tests
 * prüften Formulierungen statt Entscheidungen, jede Textänderung konnte die
 * wissenschaftliche Prüfung brechen, und die Darstellung war an die Rechnung gekettet.
 * Jetzt liefert die Domäne nur, WAS entschieden wurde und WARUM (Code plus Kennzahlen);
 * die Sätze baut ausschließlich der SnapshotMapper.
 */

enum class ComponentId { FORM, HRV, SLEEP, RHR, SUBJECTIVE }

/** Wie gut ein autonomer Befund durch unabhängige Marker gestützt ist. */
enum class Evidence {
    /** Kein autonomer Marker — Frage stellt sich nicht. */
    NOT_APPLICABLE,
    /** Ursache extern angegeben (Störfaktor). */
    EXTERNAL,
    /** Einzelbefund, von den übrigen Markern nicht bestätigt. */
    UNCONFIRMED,
    /** Durch weitere Marker bestätigt. */
    CONFIRMED,
}

enum class LimitCode {
    /** HRV-Tageswert weit unter der Basis. `value` = Abstand in SD. */
    HRV_STRONG,
    /** HRV-Tageswert deutlich unter der Basis. `value` = Abstand in SD. */
    HRV_BELOW,
    /** Teilscore kritisch niedrig. `value` = Teilscore. */
    COMPONENT_CRITICAL,
    /** Teilscore deutlich reduziert. `value` = Teilscore. */
    COMPONENT_REDUCED,
    /** Schlafdefizit gegenüber Bedarf, bestätigt. `value` = Defizit in h. */
    SLEEP_DEFICIT,
    /** Wochenschnitt unter dem absoluten Boden. `value` = Wochenschnitt in h. */
    SLEEP_FLOOR,
    /** Erschöpfung oder Muskelkater auf der schlechtesten Stufe. */
    SUBJECTIVE_WORST,
}

enum class Corroborator { HRV_BELOW_SWC, RHR_ELEVATED, HRV_ALARM }

data class LimitingFactor(
    val code: LimitCode,
    val severity: Severity,
    val component: ComponentId? = null,
    val value: Double = 0.0,
    val evidence: Evidence = Evidence.NOT_APPLICABLE,
    /** Bei einem Schwellwert-Befund, ob er wegen fehlender Bestätigung gedeckelt wurde. */
    val capped: Boolean = false,
    val corroborators: List<Corroborator> = emptyList(),
)

/** Hinweise zur Tagesempfehlung. Die Kennzahlen dazu liest der Mapper aus Metrics und LoadHistory. */
enum class NoteCode {
    FORM_DEEP_FATIGUE,
    LOAD_FORCE_REST,
    REST_DAY_DUE,
    QUALITY_STREAK_END,
    BIG_DAY_YESTERDAY,
    HARD_YESTERDAY_NOT_RECOVERED,
    SECOND_QUALITY_DAY,
    HRV_BELOW_BAND,
    HRV_SATURATION,
    HRV_WEEK_ALARM,
    LIMITING_FACTOR,
    INSUFFICIENT_PHYSIOLOGY,
    FORM_VERY_HIGH,
    ACWR_HIGH,
    ILLNESS,
    HRV_ARTIFACT,
    EXTERNAL_CAUSE,
}

data class Note(val code: NoteCode, val limit: LimitingFactor? = null)

/** Hinweise der Belastungshistorie. */
enum class LoadNoteCode { STREAK_LONG, STREAK_5, STREAK_4_MONOTONY, HARD_YESTERDAY, HIGH_MONOTONY }

/** Warum ein Tag als harter Reiz gilt. */
enum class HardKind { Z5_PLUS, Z6_PLUS, Z4, TORQUE, IF_NO_ZONES }

data class HardReason(
    val kind: HardKind,
    val seconds: Int = 0,
    /** Anteil an der Fahrzeit in Prozent, wenn das Konzentrationskriterium griff. */
    val sharePct: Int? = null,
    /** Absolutschwelle in Minuten, wenn das Absolutkriterium griff. */
    val absoluteMin: Int? = null,
    /** IF bei Einheiten ohne Zonendaten. */
    val intensity: Double? = null,
)

// ---- Formaufbau ----

/**
 * Antwortmarker des Formaufbaus. Die Familie fasst Marker zusammen, die aus denselben
 * Einheiten und Fenstern stammen und deshalb gemeinsam schwanken: drei Kraftdauern und
 * die Kraft-Effizienz sind EIN Beleg, nicht vier.
 */
enum class Marker(val family: MarkerFamily) {
    EFTP(MarkerFamily.POWER), AEROBIC_EF(MarkerFamily.AEROBIC), DECOUPLING(MarkerFamily.DURABILITY),
    D60(MarkerFamily.STRENGTH), D300(MarkerFamily.STRENGTH), D600(MarkerFamily.STRENGTH),
    LC_EFFICIENCY(MarkerFamily.STRENGTH);
    companion object { fun of(k: DurationKey) = when (k) { DurationKey.D60 -> D60; DurationKey.D300 -> D300; DurationKey.D600 -> D600 } }
}

enum class MarkerFamily { POWER, AEROBIC, DURABILITY, STRENGTH }

enum class ProgressionVerdict {
    INSUFFICIENT, PRODUCTIVE, FOCUSED, STIMULUS_PENDING, LOAD_UP_RESPONSE_DOWN,
    DELOAD_WORKS, DETRAINING, PLATEAU,
}

enum class DurationKey(val seconds: Int) { D60(60), D300(300), D600(600) }
