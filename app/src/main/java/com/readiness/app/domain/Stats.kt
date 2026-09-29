package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Statistische Hilfsfunktionen an EINER Stelle.
 *
 * Vorher hatte jede Datei ihre eigene Kopie von Mittelwert, Median und Standardabweichung —
 * mit unterschiedlichen Konventionen. Die HRV-Bandbreite etwa teilte durch n statt n−1
 * und unterschätzte die Streuung bei sieben Werten um rund 7 %, was das Band enger und
 * jeden Alarm wahrscheinlicher machte.
 */
object Stats {

    /**
     * Gültiger physiologischer Messwert: endlich und größer null.
     *
     * Null ist bei HRV, Ruhepuls und Schlafdauer kein Messwert, sondern die Art, wie manche
     * Quellen „nicht gemessen" kodieren. NaN darf nie in eine Rechnung gelangen — spätestens
     * beim Runden wirft Kotlin sonst eine Ausnahme.
     */
    fun valid(v: Double?): Double? = if (v != null && v.isFinite() && v > 0) v else null

    /** Endlicher Wert (auch null und negativ erlaubt, etwa für CTL/ATL). */
    fun finite(v: Double?): Boolean = v != null && v.isFinite()

    fun mean(v: List<Double>): Double? = if (v.isEmpty()) null else v.average()

    /** Stichproben-Standardabweichung (n−1); null unter zwei Werten. */
    fun sampleSd(v: List<Double>): Double? {
        if (v.size < 2) return null
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
    }

    /** Populations-Standardabweichung (n) — nur, wo die Formel sie ausdrücklich verlangt (Foster). */
    fun populationSd(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }

    fun median(v: List<Double>): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted(); val i = s.size / 2
        return if (s.size % 2 == 1) s[i] else (s[i - 1] + s[i]) / 2
    }

    /** Median der absoluten Abweichungen vom Median. */
    fun mad(v: List<Double>): Double? {
        val m = median(v) ?: return null
        return median(v.map { abs(it - m) })
    }

    /**
     * Werte außerhalb Median ± k·s verwerfen, mit s = IQR / 1,349 (bei Normalverteilung eine
     * Schätzung der Standardabweichung) und einer Untergrenze `minScale`. Bei k = 3 fallen
     * von echten, normalverteilten Werten nur rund 0,3 % heraus.
     *
     * Verwerfen statt Kappen (Winsorisieren): ein auf die Grenze gezogener Artefaktwert
     * bläht die Streuung weiterhin um rund 20 % auf — genug, um einen echten Einbruch eine
     * Woche lang im verbreiterten Band verschwinden zu lassen.
     *
     * IQR statt MAD: die MAD bricht bei gehäuften oder gerundeten Werten auf nahe null
     * zusammen, und die Bereinigung verwirft dann die Hälfte der echten Messungen.
     */
    fun withoutOutliers(v: List<Double>, k: Double = 3.0, minScale: Double = 0.0): List<Double> {
        if (v.size < 4) return v
        val m = median(v)!!
        val iqr = percentile(v, 0.75)!! - percentile(v, 0.25)!!
        val s = maxOf(iqr / 1.349, minScale)
        if (s <= 0) return v
        return v.filter { it >= m - k * s && it <= m + k * s }
    }

    /** Perzentil mit linearer Interpolation, p in 0..1. */
    fun percentile(v: List<Double>, p: Double): Double? {
        if (v.isEmpty()) return null
        val s = v.sorted()
        val pos = p.coerceIn(0.0, 1.0) * (s.size - 1)
        val lo = pos.toInt(); val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** Tage von einem ISO-Datum bis `today` (positiv = in der Vergangenheit); null bei ungültigem Datum. */
    fun daysBefore(isoDate: String, today: LocalDate): Int? =
        runCatching { LocalDate.parse(isoDate) }.getOrNull()?.let { (today.toEpochDay() - it.toEpochDay()).toInt() }
}
