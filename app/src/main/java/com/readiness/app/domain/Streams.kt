package com.readiness.app.domain

import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Verarbeitung der Roh-Streams (Leistung, Trittfrequenz, Herzfrequenz).
 *
 * Zwei Korrekturen aus dem Audit stecken hier:
 *
 * 1. ABTASTRATE. Alle Fensteralgorithmen rechnen in SEKUNDEN, greifen aber über
 *    Array-Indizes zu. Das ist nur bei sekündlicher Abtastung korrekt. Bei Smart
 *    Recording oder nach Auto-Pause wären 300 Messpunkte real zwanzig Minuten — die
 *    Kennzahl wäre still falsch, ohne dass irgendetwas auffällt. Deshalb wird der
 *    Zeit-Stream mitgeführt, Ungleichmäßigkeit erkannt und auf ein 1-Hz-Raster gelegt.
 *    Lücken über zehn Sekunden bleiben als fehlend markiert statt überbrückt: eine
 *    Pause darf nicht zu Leistung interpoliert werden.
 *
 * 2. PLAUSIBILITÄT. Leistungsspitzen und Trittfrequenzen außerhalb physiologisch
 *    möglicher Bereiche stammen aus Sensoraussetzern (Magnet-Doppelzählung,
 *    Funkstörung) und würden vor allem die Maximum-Statistiken verzerren. Sie gelten
 *    als FEHLEND, nicht als Null — Null wäre eine Aussage („keine Leistung"), die
 *    hier niemand getroffen hat.
 */
object Streams {

    const val LC_RPM = 70          // Obergrenze „niedrige Trittfrequenz"
    const val LC_MIN_CAD = 30      // darunter gilt als Rollen, nicht als Treten
    const val LC_HR_MIN_DUR = 300  // HF-Kopplung erst ab dieser Fensterlänge
    const val MAX_WATTS = 2000.0
    const val MAX_CADENCE = 200.0
    const val MAX_HR = 240.0
    const val MAX_GAP_SEC = 10     // längere Lücken nicht überbrücken

    /** Ausgewertete Kraftdauern (1, 5 und 10 min). */
    val DURATIONS: List<DurationKey> = DurationKey.entries

    /** Auf 1 Hz normalisierte Streams. NaN bedeutet „nicht gemessen". */
    class Normalized(val watts: DoubleArray, val cadence: DoubleArray, val hr: DoubleArray, val resampled: Boolean) {
        val n: Int get() = watts.size
    }

    private fun clean(v: Double?, max: Double): Double =
        if (v != null && v.isFinite() && v >= 0.0 && v <= max) v else Double.NaN

    fun normalize(watts: List<Double?>?, cadence: List<Double?>?, hr: List<Double?>?, time: List<Double?>?): Normalized? {
        if (watts == null || cadence == null || watts.isEmpty() || cadence.isEmpty()) return null

        // Gleichmäßig abgetastet? Fehlt der Zeit-Stream, gilt die 1-Hz-Zusage der API.
        var uniform = true
        if (time != null && time.size == watts.size && time.size > 1) {
            for (i in 1 until time.size) {
                val a = time[i]; val b = time[i - 1]
                if (a == null || b == null || (a - b).roundToInt() != 1) { uniform = false; break }
            }
        }

        if (uniform) {
            val n = minOf(watts.size, cadence.size)
            val w = DoubleArray(n); val c = DoubleArray(n); val h = DoubleArray(n)
            for (i in 0 until n) {
                w[i] = clean(watts[i], MAX_WATTS)
                c[i] = clean(cadence[i], MAX_CADENCE)
                h[i] = clean(hr?.getOrNull(i), MAX_HR)
            }
            return Normalized(w, c, h, false)
        }

        val t = time ?: return null
        val t0 = t.firstOrNull() ?: return null
        val tEnd = t.lastOrNull() ?: return null
        val n = ((tEnd - t0).roundToInt() + 1).coerceIn(1, 86_400)
        val w = DoubleArray(n); val c = DoubleArray(n); val h = DoubleArray(n)
        var src = 0
        for (sec in 0 until n) {
            while (src + 1 < t.size && ((t[src + 1] ?: 0.0) - t0) <= sec) src++
            val gap = sec - ((t[src] ?: 0.0) - t0)
            if (gap > MAX_GAP_SEC) { w[sec] = Double.NaN; c[sec] = Double.NaN; h[sec] = Double.NaN }
            else {
                w[sec] = clean(watts.getOrNull(src), MAX_WATTS)
                c[sec] = clean(cadence.getOrNull(src), MAX_CADENCE)
                h[sec] = clean(hr?.getOrNull(src), MAX_HR)
            }
        }
        return Normalized(w, c, h, true)
    }

    /**
     * Präfixsummen EINMAL je Einheit aufbauen und für alle Fensterlängen wiederverwenden.
     * Im Prototyp baute jede Abfrage ihre eigenen Summen auf — bei drei Dauern plus
     * Referenzfenster viermal dieselbe Arbeit und viermal dieselbe Allokation.
     */
    class Prefix(s: Normalized, maxCad: Double) {
        val n = s.n
        val power = DoubleArray(n + 1)
        val cadence = DoubleArray(n + 1)
        val valid = DoubleArray(n + 1)
        val low = DoubleArray(n + 1)
        /** Sekunden mit GEMESSENER Leistung — Nenner des Mittelwerts, siehe [bestWindow]. */
        val finite = DoubleArray(n + 1)
        init {
            for (i in 0 until n) {
                val w = s.watts[i]; val c = s.cadence[i]
                val ok = w.isFinite() && c.isFinite() && c >= LC_MIN_CAD && w > 0
                power[i + 1] = power[i] + (if (w.isFinite()) w else 0.0)
                cadence[i + 1] = cadence[i] + (if (ok) c else 0.0)
                valid[i + 1] = valid[i] + (if (ok) 1.0 else 0.0)
                low[i + 1] = low[i] + (if (ok && c <= maxCad) 1.0 else 0.0)
                finite[i + 1] = finite[i] + (if (w.isFinite()) 1.0 else 0.0)
            }
        }
    }

    data class Window(val best: Int?, val count: Int, val at: Int)

    /**
     * Bestes Fenster einer Dauer, wahlweise auf niedrige Trittfrequenz konditioniert.
     * Rollphasen zählen nicht als Tretzeit; ein Fenster qualifiziert nur, wenn mindestens
     * 90 % der Zeit tatsächlich getreten wurde — sonst würde eine Abfahrt als „niedrige
     * Frequenz" gelten.
     */
    fun bestWindow(p: Prefix, len: Int, maxCad: Double?): Window {
        if (p.n < len) return Window(null, 0, -1)
        var best: Double? = null; var at = -1; var count = 0; var lastEnd = -1
        for (i in 0..(p.n - len)) {
            val valid = p.valid[i + len] - p.valid[i]
            if (valid < 0.9 * len) continue
            if (maxCad != null) {
                if ((p.cadence[i + len] - p.cadence[i]) / valid > maxCad) continue
                /* Der Mittelwert allein genügt nicht: ein Fenster aus je zur Hälfte 60 und
                   92 rpm hat im Schnitt 76 — knapp darunter liegende Mischungen würden
                   qualifizieren, obwohl ein erheblicher Teil Hochfrequenzarbeit ist, deren
                   höhere Leistung den Kraftwert verfälschen würde. Deshalb müssen mindestens
                   85 % der Tretsekunden EINZELN unter der Schwelle liegen. */
                if ((p.low[i + len] - p.low[i]) < 0.85 * valid) continue
            }
            /* Über die GEMESSENEN Sekunden mitteln, nicht über die Fensterlänge. Bis zu
               10 % der Sekunden dürfen fehlen; sie tragen 0 W zur Summe bei und hätten
               durch `len` geteilt das Fenster systematisch zu niedrig geschätzt — ein
               Fehler, der für sich allein schon einen Scheinrückgang erzeugen kann.
               Nicht durch `valid` teilen: darin fehlen legitime Rollsekunden mit echten
               0 W, die sehr wohl zum Fenstermittel gehören. */
            val fin = p.finite[i + len] - p.finite[i]
            if (fin <= 0) continue
            val mp = (p.power[i + len] - p.power[i]) / fin
            if (best == null || mp > best!!) { best = mp; at = i }
            if (i > lastEnd) { count++; lastEnd = i + len - 1 }
        }
        return Window(best?.roundToInt(), count, at)
    }

    /** Mittlere Herzfrequenz über die ZWEITE HÄLFTE eines Fensters (Anlauf ausgeklammert). */
    fun meanHrLatter(hr: DoubleArray, at: Int, len: Int): Double? {
        if (at < 0) return null
        val from = at + floor(len / 2.0).toInt()
        val to = minOf(hr.size, at + len)
        var sum = 0.0; var n = 0
        for (i in from until to) { val v = hr[i]; if (v.isFinite() && v > 40) { sum += v; n++ } }
        return if (n >= (to - from) * 0.7) sum / n else null
    }

    /**
     * Kennwerte einer Einheit. EIN Präfixsatz genügt: Leistungs-, Trittfrequenz- und
     * Gültigkeitssummen sind von der Trittfrequenz-Schranke unabhängig, nur die Zählung
     * „Sekunden unter Schwelle" hängt daran. Die unkonditionierte Abfrage überspringt
     * diese Prüfung ohnehin.
     */
    fun metrics(s: Normalized): TorqueMetrics {
        val p = Prefix(s, LC_RPM.toDouble())
        val res = DURATIONS.associateWith { d -> bestWindow(p, d.seconds, LC_RPM.toDouble()) }
        val w300 = res.getValue(DurationKey.D300)
        val p300 = bestWindow(p, 300, null).best

        var ef: Double? = null; var efW: Int? = null; var efHr: Int? = null
        // HF-Kopplung über das 5-min-Fenster — das ist bereits die Mindestdauer LC_HR_MIN_DUR
        if (w300.best != null) {
            val h = meanHrLatter(s.hr, w300.at, 300)
            if (h != null && h > 0) {
                ef = ((w300.best!! / h) * 1000).roundToInt() / 1000.0
                efW = w300.best; efHr = h.roundToInt()
            }
        }

        // höchstes 30-Sekunden-Mittel des Drehmoments (Nm = 9,549 × W / rpm)
        var peak: Double? = null
        if (s.n >= 30) {
            val tS = DoubleArray(s.n + 1); val vS = DoubleArray(s.n + 1)
            for (i in 0 until s.n) {
                val w = s.watts[i]; val c = s.cadence[i]
                val ok = w.isFinite() && c.isFinite() && c >= LC_MIN_CAD && w > 0
                tS[i + 1] = tS[i] + (if (ok) 9.549 * w / c else 0.0)
                vS[i + 1] = vS[i] + (if (ok) 1.0 else 0.0)
            }
            for (i in 0..(s.n - 30)) {
                if (vS[i + 30] - vS[i] < 27) continue
                val mt = (tS[i + 30] - tS[i]) / 30.0
                if (peak == null || mt > peak!!) peak = mt
            }
            if (peak != null) peak = (peak!! * 10).roundToInt() / 10.0
        }

        val w60 = res.getValue(DurationKey.D60); val w600 = res.getValue(DurationKey.D600)
        return TorqueMetrics(
            d60 = w60.best, n60 = w60.count,
            d300 = w300.best, n300 = w300.count,
            d600 = w600.best, n600 = w600.count,
            p300 = p300, efficiency = ef, efficiencyW = efW, efficiencyHr = efHr,
            peakTorque30s = peak, resampled = s.resampled,
        )
    }

    /** Sekunden Drehmomentarbeit: ≥85 % FTP bei ≤70 rpm. */
    fun torqueWorkSeconds(s: Normalized, ftp: Double): Int {
        var secs = 0
        for (i in 0 until s.n) {
            val w = s.watts[i]; val c = s.cadence[i]
            if (w.isFinite() && c.isFinite() && w >= 0.85 * ftp && c >= LC_MIN_CAD && c <= LC_RPM) secs++
        }
        return secs
    }
}
