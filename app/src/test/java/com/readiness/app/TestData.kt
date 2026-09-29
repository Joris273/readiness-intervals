package com.readiness.app

import com.readiness.app.domain.*
import org.junit.Assert.assertEquals
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Gemeinsame Testdaten.
 *
 * Zwei Arten von Reihen: KONSTANTE (Streuung null) für die Referenzszenarien, deren
 * Ergebnis sich von Hand nachrechnen lässt — und VERRAUSCHTE mit realistischer
 * Tag-zu-Tag-Streuung. Nur die zweite Art zeigt, wie oft das System bei unauffälliger
 * Physiologie fälschlich alarmiert; mit konstanten Reihen greift immer der SD-Floor und
 * jede Schwelle wirkt schärfer, als sie im Alltag ist.
 */
val TODAY: LocalDate = LocalDate.of(2026, 7, 27)

fun d(n: Int): String = TODAY.minusDays(n.toLong()).toString()

fun wellnessSeries(hrv: Double = 44.0, rhr: Double = 42.0, sleepSec: Double = 23400.0,
                   ctl: Double = 57.0, atl: Double = 55.0, days: Int = 120,
                   sleepScore: Double? = 71.0): List<WellnessDay> =
    (days - 1 downTo 0).map { WellnessDay(d(it), ctl, atl, hrv, rhr, sleepSec, sleepScore) }

fun ride(ago: Int, load: Double = 80.0, ifv: Double = 0.72, dur: Double = 5400.0,
         z: Map<Int, Int> = mapOf(1 to 1800, 2 to 2600), type: String = "Ride",
         torqueWork: Int = 0, tq: TorqueMetrics? = null, np: Double? = 230.0,
         hr: Double? = 140.0, dec: Double? = 4.0, eftp: Double? = null,
         trainer: Boolean = false): Session =
    Session("a$ago", type, trainer, d(ago), dur, load, ifv, eftp, np, hr, dec, z, z.isNotEmpty(), tq, torqueWork)

/** Die Standard-Intervalleinheit der Referenzszenarien (25 min Z4, 1 min Z5). */
fun intervals(ago: Int) = ride(ago, 85.0, 0.86, 4500.0, mapOf(4 to 1500, 5 to 60))

val THRESHOLDS = Thresholds(295, 285, 277)

fun evaluate(sessions: List<Session> = emptyList(), cfg: AnalysisConfig = AnalysisConfig(),
             wl: List<WellnessDay> = wellnessSeries(), today: LocalDate = TODAY): ReadinessResult =
    ReadinessEngine.evaluate(wl, sessions, THRESHOLDS, cfg, today)

fun assertVerdict(label: String, expected: Verdict, r: ReadinessResult) {
    val detail = "$label: Score ${r.score} (Basis ${r.baseScore}), ${r.recommendation.verdict} — " +
        r.components.joinToString { "${it.id}=${it.sub}" } +
        " | Limits: ${r.limitingFactors.joinToString { it.severity.name }}"
    assertEquals(detail, expected, r.recommendation.verdict)
}

/** Letzte Wellness-Zeile verändern (der Messtag). */
fun List<WellnessDay>.today(f: (WellnessDay) -> WellnessDay): List<WellnessDay> =
    toMutableList().also { it[it.size - 1] = f(it.last()) }

/** Die letzten `n` Zeilen verändern. */
fun List<WellnessDay>.lastDays(n: Int, f: (WellnessDay) -> WellnessDay): List<WellnessDay> =
    mapIndexed { i, w -> if (i >= size - n) f(w) else w }

val LN60: Double = ln(60.0)

/**
 * Deterministische Reihe mit REALISTISCHER Streuung: Ln-rMSSD abwechselnd ±sd um ln(60).
 * Die Stichproben-SD liegt damit bei ≈ sd, und Szenarien lassen sich in SD-Einheiten
 * formulieren — ohne dass der SD-Floor die Schwellen künstlich verschärft.
 */
fun alternatingWellness(lnSd: Double = 0.08, rhr: Double = 48.0, days: Int = 120,
                        ctl: Double = 60.0, atl: Double = 60.0): List<WellnessDay> =
    (days - 1 downTo 0).map { back ->
        WellnessDay(d(back), ctl, atl, exp(LN60 + if (back % 2 == 0) lnSd else -lnSd), rhr, 7.5 * 3600, 80.0)
    }

/** rMSSD bei `z` SD Abstand zur Basis der alternierenden Reihe. */
fun hrvAtZ(z: Double, lnSd: Double = 0.08): Double = exp(LN60 + z * lnSd)

// ---------------------------------------------------------------- Rauschen

class Noise(seed: Int) {
    private val rnd = Random(seed)
    /** Standardnormalverteilt (Box-Muller). */
    fun gauss(): Double {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-12)
        val u2 = rnd.nextDouble()
        return sqrt(-2 * ln(u1)) * cos(2 * PI * u2)
    }
    fun normal(mean: Double, sd: Double) = mean + sd * gauss()
}

/** Parameter einer realistisch streuenden Wellness-Reihe. */
data class NoiseSpec(
    val lnHrvMean: Double = ln(60.0),
    val lnHrvSd: Double = 0.08,
    val rhrMean: Double = 48.0,
    val rhrSd: Double = 2.5,
    val sleepMeanH: Double = 7.5,
    val sleepSdH: Double = 0.6,
    val sleepScoreMean: Double = 80.0,
    val sleepScoreSd: Double = 7.0,
    val ctl: Double = 60.0,
    val atl: Double = 60.0,
    val days: Int = 120,
)

/**
 * Verrauschte Reihe. `lnHrvShift` verschiebt die letzten `shiftDays` Tage um ein
 * Vielfaches der Tagesstreuung — damit lässt sich eine echte Suppression einspielen.
 */
fun noisyWellness(seed: Int, spec: NoiseSpec = NoiseSpec(), lnHrvShiftSd: Double = 0.0,
                  shiftDays: Int = 0): List<WellnessDay> {
    val n = Noise(seed)
    return (spec.days - 1 downTo 0).map { back ->
        val shift = if (back < shiftDays) lnHrvShiftSd * spec.lnHrvSd else 0.0
        WellnessDay(
            date = d(back), ctl = spec.ctl, atl = spec.atl,
            hrv = exp(n.normal(spec.lnHrvMean, spec.lnHrvSd) + shift),
            restingHr = n.normal(spec.rhrMean, spec.rhrSd),
            sleepSeconds = n.normal(spec.sleepMeanH, spec.sleepSdH).coerceIn(3.0, 11.0) * 3600,
            sleepScore = n.normal(spec.sleepScoreMean, spec.sleepScoreSd).coerceIn(20.0, 100.0),
        )
    }
}

data class Rates(val n: Int, val notGreen: Double, val red: Double) {
    override fun toString() =
        "n=$n  nicht GRÜN ${"%.1f".format(notGreen * 100)} %  ROT ${"%.1f".format(red * 100)} %"
}

/** Bewertet `runs` unabhängige Reihen jeweils am letzten Tag. */
fun rates(runs: Int, build: (seed: Int) -> Pair<List<WellnessDay>, List<Session>>): Rates {
    var notGreen = 0; var red = 0
    for (seed in 1..runs) {
        val (wl, ss) = build(seed)
        val v = evaluate(ss, wl = wl).recommendation.verdict
        if (v != Verdict.GREEN) notGreen++
        if (v == Verdict.RED) red++
    }
    return Rates(runs, notGreen.toDouble() / runs, red.toDouble() / runs)
}
