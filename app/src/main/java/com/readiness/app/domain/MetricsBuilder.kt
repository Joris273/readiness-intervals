package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Baut aus Wellness-Reihe und Einheiten die Tageskennwerte auf.
 *
 * Im Prototyp war das ein Teil der 237-Zeilen-Ladefunktion, die zugleich Netzabruf und
 * Rendering erledigte. Hier ist es eine reine Funktion ohne Seiteneffekte: gleiche
 * Eingaben, gleiches Ergebnis, ohne Emulator testbar.
 */
object MetricsBuilder {

    /**
     * Neuester Eintrag mit gültigem Wert, höchstens `lookback` KALENDERTAGE vor `today`.
     * Früher zählte der Rückblick Zeilen — bei lückenhafter Reihe reichte er damit beliebig
     * weit zurück, und ein Wert von vorgestern ging ohne Kennzeichnung als „heute" durch.
     */
    private fun latestIndex(w: List<WellnessDay>, today: LocalDate, lookback: Int, sel: (WellnessDay) -> Double?): Int {
        for (i in w.indices.reversed()) {
            val back = Stats.daysBefore(w[i].date, today) ?: continue
            if (back < 0) continue
            if (back > lookback) return -1
            if (Stats.valid(sel(w[i])) != null) return i
        }
        return -1
    }

    /** Gültige Werte der `days` Kalendertage VOR `date`, gestörte Tage ausgenommen. */
    private fun history(w: List<WellnessDay>, date: String, days: Int, exclude: Set<String>,
                        sel: (WellnessDay) -> Double?): List<Double> {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return emptyList()
        return w.mapNotNull { row ->
            if (row.date in exclude) return@mapNotNull null
            val back = Stats.daysBefore(row.date, d) ?: return@mapNotNull null
            if (back in 1..days) Stats.valid(sel(row)) else null
        }
    }

    /** Standardwert-Abweichung gegen die eigene Historie (Ausreißer entfernt, SD mit Boden). */
    private data class Norm(val mean: Double, val sd: Double?, val n: Int)

    private fun norm(values: List<Double>, sdFloor: Double, minForSd: Int): Norm? {
        val v = Stats.withoutOutliers(values, 3.0, sdFloor)
        if (v.size < 3) return null
        val sd = if (v.size >= minForSd) max(Stats.sampleSd(v) ?: 0.0, sdFloor) else null
        return Norm(v.average(), sd, v.size)
    }

    fun build(wellness: List<WellnessDay>, sessions: List<Session>, cfg: AnalysisConfig, today: LocalDate): Metrics {
        if (wellness.isEmpty()) return Metrics()
        val p = cfg.params
        val w = wellness.sortedBy { it.date }
        val confounded = cfg.confoundedDays
        val last = w.last()

        /* FORM VOR DEM TAG. intervals.icu schreibt CTL/ATL eines Tages einschließlich seiner
           Einheiten. Die heutige Zeile verändert sich also, sobald trainiert wurde — ein
           Morgen-Score daraus sänke durch genau das Training, dessen Bereitschaft er
           beschreiben soll. Maßgeblich ist deshalb der Stand am Ende des Vortags. */
        val todayIso = today.toString()
        val formRow = w.lastOrNull { it.date < todayIso && Stats.finite(it.ctl) && Stats.finite(it.atl) }
        val formSrc = formRow ?: last.takeIf { Stats.finite(it.ctl) && Stats.finite(it.atl) }
        val formCtl = formSrc?.ctl; val formAtl = formSrc?.atl
        val tsb = if (formCtl != null && formAtl != null) formCtl - formAtl else null
        var m = Metrics(
            dataDate = last.date, ctl = last.ctl, atl = last.atl,
            tsb = tsb, formCtl = formCtl, formAtl = formAtl,
            tsbPct = if (tsb != null && formCtl != null) tsb / max(formCtl, p.form.minCtl) * 100 else null,
            formFromToday = formRow == null && formSrc != null,
            napMinutes = cfg.napMinutesByDay[last.date] ?: 0,
        )

        // ---- HRV: Trend des 7-Tage-Mittels gegen ein langes, robustes Normalband ----
        val vp = p.validity
        val hrvIdx = latestIndex(w, today, vp.lookbackDays) { it.hrv }
        val hrvDate = if (hrvIdx >= 0) runCatching { LocalDate.parse(w[hrvIdx].date) }.getOrNull() else null
        if (hrvIdx >= 0 && hrvDate != null) {
            val hp = p.hrv
            val v = w[hrvIdx].hrv!!
            val lnToday = ln(v)
            m = m.copy(hrvAgeDays = Stats.daysBefore(w[hrvIdx].date, today))
            /* Ln-Werte nach Abstand zum Messtag (0 = Messtag). Störfaktor-Tage fallen aus
               Basis, Streuung und Trend heraus — sie beschreiben nicht den Trainingszustand. */
            val lnByBack = HashMap<Int, Double>()
            for (k in 0..hrvIdx) {
                if (w[k].date in confounded) continue
                val back = Stats.daysBefore(w[k].date, hrvDate) ?: continue
                if (back in 0..(hp.refDays + hp.rollDays)) Stats.valid(w[k].hrv)?.let { lnByBack[back] = ln(it) }
            }
            fun window(endBack: Int, days: Int) = (endBack until endBack + days).mapNotNull { lnByBack[it] }

            /* Vergleichswert für die Prozentanzeige: geometrisches Mittel der sieben Vortage —
               dieselbe Skala wie das Band, sonst widersprechen sich Anzeige und Einstufung. */
            val prev7 = window(1, hp.rollDays)
            m = m.copy(hrv = v, hrvDate = w[hrvIdx].date,
                hrvDeviationPct = if (prev7.size >= 3) (exp(lnToday - prev7.average()) - 1) * 100 else null)

            /* Referenz: die Vortage im Referenzfenster, ohne Ausreißer. Die Streuung aus nur
               sieben Werten (bisher) hat selbst rund 30 % Unsicherheit — das Band sprang
               täglich, und mit ihm jede Schwelle, die in SWC-Einheiten gemessen wurde. */
            val ref = Stats.withoutOutliers(window(1, hp.refDays), hp.outlierK, hp.sdFloor)
            if (ref.size >= hp.bandMinValues) {
                val base = ref.average()
                val sd = max(Stats.sampleSd(ref) ?: 0.0, hp.sdFloor)
                val swc = hp.swcFactor * sd
                val provisional = ref.size < hp.bandFullValues
                val z = (lnToday - base) / sd
                val roll = window(0, hp.rollDays).takeIf { it.size >= hp.rollMin }
                val rollMean = roll?.average()

                /* Variabilität der Variabilität (Plews 2012): CV der letzten 7 Tage gegen den
                   üblichen CV, ermittelt als Median der rollierenden CVs im Referenzfenster. */
                fun cv(vals: List<Double>): Double? =
                    if (vals.size >= hp.rollMin) Stats.sampleSd(vals)?.let { it / vals.average() * 100 } else null
                val cv7 = roll?.let { cv(it) }
                val cvRef = Stats.median((1..hp.refDays).mapNotNull { cv(window(it, hp.rollDays)) })

                m = m.copy(
                    hrvLn = lnToday, hrvLnBase = base, hrvLnSd = sd,
                    hrvBandLo = exp(base - swc), hrvBandHi = exp(base + swc),
                    hrvBandProvisional = provisional, hrvBandValues = ref.size,
                    hrvZ = z, hrvRoll7 = rollMean,
                    hrvSuppressed = !provisional && rollMean != null && rollMean < base - hp.trendSd * sd,
                    hrvAcuteDrop = !provisional && z <= -hp.acuteSd,
                    hrvAbove = lnToday > base + swc,
                    hrvUnusual = z > hp.unusualSd,
                    hrvCv7 = cv7, hrvCvRef = cvRef,
                )

                /* WOCHENTREND gegen die vier Wochen davor — Planungssignal über Wochen.
                   Die Wochenmittel werden NICHT-ÜBERLAPPEND gebildet: benachbarte
                   rollierende Fenster teilen sechs von sieben Tagen und sind hoch
                   korreliert, ihre Streuung unterschätzt die Unsicherheit erheblich. */
                fun weekMean(from: Int): Pair<Double, Int>? =
                    window(from, 7).let { if (it.size >= 3) it.average() to it.size else null }
                val cur = weekMean(0)
                val refs = listOf(weekMean(7), weekMean(14), weekMean(21), weekMean(28)).filterNotNull()
                if (!provisional && cur != null && refs.size >= hp.weekMinRefWeeks) {
                    val refM = refs.map { it.first }.average()
                    var wSd = Stats.sampleSd(refs.map { it.first }) ?: 0.0
                    /* Untergrenze: Standardfehler der DIFFERENZ aus aktueller Woche und
                       Referenzmittel — nicht nur der aktuellen Woche. Die Streuung aus drei
                       bis vier Wochenmitteln ist selbst zu unsicher, um allein zu tragen. */
                    val nRef = refs.sumOf { it.second }
                    wSd = max(wSd, sd * sqrt(1.0 / cur.second + 1.0 / nRef))
                    /* Zwei Schwellen: die Literatur-SWC von 0,5 SD ist als „kleinste
                       bedeutsame Änderung" definiert, nicht als Signifikanztest — bei
                       stabilem Verlauf fällt ein Wochenmittel rein zufällig in rund 32 %
                       der Fälle darunter. Als Anzeige richtig, als Auslöser einer
                       Intensitätsbegrenzung viel zu locker. Gehandelt wird ab 1,5 SD. */
                    val swcWeek = p.hrv.swcFactor * wSd
                    val actWeek = p.hrv.weekActSd * wSd
                    m = m.copy(
                        hrvWeek = exp(cur.first), hrvWeekRef = exp(refM),
                        hrvWeekDevPct = (exp(cur.first) / exp(refM) - 1) * 100,
                        hrvWeekDown = (cur.first - refM) < -swcWeek,
                        hrvWeekAlarm = (cur.first - refM) < -actWeek,
                        hrvWeekUp = (cur.first - refM) > swcWeek,
                    )
                }
            }

            val causes = cfg.confounders[w[hrvIdx].date].orEmpty()
            if (causes.isNotEmpty()) {
                val kinds = causes.mapNotNull { Confounders.byKey(it)?.kind }
                val illness = ConfounderKind.MEDICAL in kinds
                m = m.copy(
                    confounded = true,
                    confounderKeys = causes,
                    confIllness = illness,
                    confInvalid = ConfounderKind.INVALID in kinds,
                    confExternal = ConfounderKind.EXTERNAL in kinds && !illness,
                )
            }
        }

        /* Veraltete HRV (älter als gestern) wird angezeigt, trägt aber keine Entscheidung. */
        if ((m.hrvAgeDays ?: 0) > vp.staleAfterDays) m = m.copy(
            hrvSuppressed = false, hrvAcuteDrop = false, hrvWeekAlarm = false, hrvWeekDown = false)

        /* ---- Ruhepuls: gegen die EIGENE Streuung, nicht in absoluten Schlägen ----
           +3 bpm sind bei einer Tagesstreuung von 1,5 bpm ein klares Signal, bei 4 bpm
           Rauschen. Basis und Streuung über das Referenzfenster, Ausreißer entfernt. */
        val rp = p.rhr
        val rhrIdx = latestIndex(w, today, vp.lookbackDays) { it.restingHr }
        if (rhrIdx >= 0) {
            val v = w[rhrIdx].restingHr!!
            val n = norm(history(w, w[rhrIdx].date, rp.refDays, confounded) { it.restingHr }, rp.sdFloor, rp.minValues)
            m = m.copy(restingHr = v, restingHrBase = n?.mean, restingHrDiff = n?.let { v - it.mean },
                restingHrSd = n?.sd, rhrZ = n?.sd?.let { (v - n.mean) / it },
                rhrAgeDays = Stats.daysBefore(w[rhrIdx].date, today))
        }
        val rhrFresh = (m.rhrAgeDays ?: 0) <= vp.staleAfterDays
        val rhrUp = rhrFresh && (m.rhrZ?.let { it >= rp.corroborateZ }
            ?: (m.restingHrDiff != null && m.restingHrDiff >= rp.corroborateBpm))
        m = m.copy(rhrElevated = rhrUp)

        /* ---- Subjektives Befinden (Hooper & Mackinnon 1995; Saw 2016) ----
           Mittel der angegebenen Items, auf 0 (bestens) … 1 (schlecht) normiert und gegen
           die eigene Historie gelesen: wer immer „mittel" angibt, hat an einem „mittel"-Tag
           keinen Befund. Die Skala (1–4, manche Quellen 1–5) wird aus den Daten abgeleitet. */
        val sjp = p.subjective
        val scaleMax = if (w.any { r -> r.subjective?.items?.any { it > 4.0 } == true }) 5.0 else 4.0
        fun subjIndex(e: SubjectiveEntry?): Double? =
            e?.items?.takeIf { it.isNotEmpty() }?.map { ((it - 1) / (scaleMax - 1)).coerceIn(0.0, 1.0) }?.average()
        val sjIdx = latestIndex(w, today, vp.lookbackDays) { subjIndex(it.subjective)?.plus(1.0) }   // +1: 0 ist gültig
        if (sjIdx >= 0) {
            val e = w[sjIdx].subjective!!
            val idx = subjIndex(e)!!
            val n = norm(history(w, w[sjIdx].date, sjp.refDays, confounded) { subjIndex(it.subjective)?.plus(1.0) }
                .map { it - 1.0 }, sjp.sdFloor, sjp.minValues)
            val z = n?.sd?.let { (idx - n.mean) / it }
            val fresh = (Stats.daysBefore(w[sjIdx].date, today) ?: 99) <= vp.staleAfterDays
            m = m.copy(subjective = e, subjectiveIndex = idx, subjectiveBase = n?.mean, subjectiveZ = z,
                subjectiveAgeDays = Stats.daysBefore(w[sjIdx].date, today), subjectiveScaleMax = scaleMax,
                subjectiveElevated = fresh && (z?.let { it >= sjp.elevatedZ } ?: (idx >= sjp.elevatedAbsolute)),
                subjectiveWorst = fresh && listOfNotNull(e.fatigue, e.soreness).any { it >= scaleMax })
        }

        // ---- Verknüpfte autonome Signale (brauchen HRV und Ruhepuls) ----
        /* Ein akuter HRV-Einbruch allein ist tagesvariabel. Zum Alarm wird er erst, wenn der
           Trend oder der Ruhepuls mitzieht — konvergente, unabhängige Evidenz. */
        m = m.copy(hrvRecoveryAlarm = m.hrvAcuteDrop && (m.hrvSuppressed || rhrUp))
        /* PARASYMPATHISCHE SÄTTIGUNG. Ein hohes 7-Tage-Mittel ist zunächst gute vagale
           Erholung. Bei funktionell überlasteten Athleten steigt die HRV aber ebenfalls
           (Le Meur 2013), typischerweise mit instabilen Tageswerten (Plews 2012) und unter
           hoher Last. Erst diese Kombination macht den hohen Wert verdächtig — allein
           bleibt er eine gute Nachricht. */
        val lnBase = m.hrvLnBase; val lnSd = m.hrvLnSd; val roll = m.hrvRoll7
        if (lnBase != null && lnSd != null && roll != null && !m.hrvBandProvisional &&
            (m.hrvAgeDays ?: 0) <= vp.staleAfterDays && roll > lnBase + p.hrv.saturationSd * lnSd) {
            val cvUp = m.hrvCv7 != null && m.hrvCvRef != null && m.hrvCv7 > p.hrv.cvRatio * m.hrvCvRef
            val loadHigh = m.tsbPct != null && m.tsbPct < p.form.corroborateBelowPct
            m = m.copy(hrvSaturation = cvUp || loadHigh || rhrUp || m.subjectiveElevated)
        }

        // ---- Schlaf ----
        val sp = p.sleep
        /* Garmin-Schlafscore gegen die eigene Verteilung: ein Wert von 71 ist für jemanden,
           der sonst bei 85 liegt, eine schlechte Nacht — für jemanden bei 68 eine gute. */
        val scIdx = latestIndex(w, today, vp.lookbackDays) { it.sleepScore }
        if (scIdx >= 0) {
            val v = w[scIdx].sleepScore!!
            val n = norm(history(w, w[scIdx].date, sp.needHistoryDays, emptySet()) { it.sleepScore }, sp.scoreSdFloor, sp.scoreMinValues)
            m = m.copy(sleepScore = v, sleepScoreBase = n?.mean, sleepScoreZ = n?.sd?.let { (v - n.mean) / it },
                sleepScoreAgeDays = Stats.daysBefore(w[scIdx].date, today))
        }
        /* Schlaf je Tag = Nacht + Nap DESSELBEN Datums, überall gleich gerechnet — Bedarf,
           Wochenschnitt und letzte Nacht. Nächte mit 0 Sekunden sind „nicht gemessen" (Uhr
           nicht getragen), keine durchwachte Nacht; sie zählten früher als 0 h mit und
           erzeugten Scheindefizite. */
        val nightByDate = LinkedHashMap<String, Double>()
        w.forEach { d -> Stats.valid(d.sleepSeconds)?.let { nightByDate[d.date] = it / 3600 + cfg.napHoursOn(d.date) } }
        val sIdx = latestIndex(w, today, vp.lookbackDays) { it.sleepSeconds }
        if (sIdx >= 0) {
            val date = w[sIdx].date
            val d0 = LocalDate.parse(date)   // gültig, sonst hätte latestIndex die Zeile übersprungen
            val prev = nightByDate.filterKeys { (Stats.daysBefore(it, d0) ?: -1) in 1..7 }.values.toList()
            m = m.copy(sleepHours = nightByDate[date], sleepNapHours = cfg.napHoursOn(date),
                sleepAvgHours = prev.takeIf { it.size >= 3 }?.average(),
                sleepAgeDays = Stats.daysBefore(date, today))
        }
        val s7 = mutableListOf<Double>(); val s30 = mutableListOf<Double>(); val sAll = mutableListOf<Double>()
        nightByDate.forEach { (date, total) ->
            val back = Stats.daysBefore(date, today) ?: return@forEach
            if (back in 0..6) s7 += total
            if (back in 0..29) s30 += total
            if (back in 0 until sp.needHistoryDays) sAll += total
        }
        /* Bedarf: selbst gesetzt, sonst aus der eigenen Historie — aber nie unter dem
           normativen Boden. Der eigene Median allein wäre zirkulär: chronischer Schlafmangel
           definierte sich selbst als Bedarf und bliebe für immer unsichtbar (Konsens für
           Erwachsene 7–9 h, für Athleten eher darüber; Walsh 2021). */
        val hist = if (sAll.size >= sp.needMinNights) Stats.median(sAll) else null
        val need = cfg.sleepNeedHours ?: hist?.let { max(it, sp.needFloorH) }
        val eff = if (s7.size >= sp.week7MinNights) s7.average() else null
        m = m.copy(
            sleep7Effective = eff,
            sleep30 = if (s30.size >= 14) s30.average() else null,
            sleepNeed = need, sleepNeedManual = cfg.sleepNeedHours != null,
            sleepNeedFloored = cfg.sleepNeedHours == null && hist != null && hist < sp.needFloorH,
            sleepDeficit = if (need != null && eff != null) need - eff else null,
        )

        /* ---- ACWR (nur Orientierung, kein Risiko-Gate) ----
           Exponentiell gewichtet (Williams 2017) statt rollierender Summen und ENTKOPPELT:
           die chronische Größe endet vor der akuten Woche. Beim klassischen Verhältnis
           steckt die akute Woche im Nenner selbst — die Kopplung erzeugt Scheinkorrelationen
           (Lolli 2019), und das Konzept als Verletzungsprädiktor gilt ohnehin als nicht
           belastbar (Impellizzeri 2020). Der heutige, meist unvollständige Tag zählt nicht. */
        val byDate = HashMap<String, Double>()
        sessions.forEach { byDate[it.localDate] = (byDate[it.localDate] ?: 0.0) + it.trainingLoad }
        val la = 2.0 / (p.load.acwrAcuteDays + 1); val lc = 2.0 / (p.load.acwrChronicDays + 1)
        var acute = 0.0; var chronic = 0.0; var seen = false
        for (back in 120 downTo 1) {
            val l = byDate[today.minusDays(back.toLong()).toString()] ?: 0.0
            if (l > 0) seen = true
            acute = la * l + (1 - la) * acute
            if (back > p.load.acwrAcuteDays) chronic = lc * l + (1 - lc) * chronic
        }
        m = m.copy(acwr = when {
            seen && chronic > 0 -> acute / chronic
            (m.ctl ?: 0.0) > 0 && m.atl != null -> m.atl!! / m.ctl!!
            else -> null
        })

        return m
    }
}
