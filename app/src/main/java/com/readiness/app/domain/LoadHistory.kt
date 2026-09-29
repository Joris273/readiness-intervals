package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Belastungshistorie: Trainingstage in Folge, Foster-Monotonie, harte Reize.
 */
object LoadHistoryAnalyzer {

    /**
     * Harter Intensitätsreiz — KONZENTRATION statt reiner Summe.
     *
     * Über eine lange Ausfahrt summieren sich Zonenzeiten nebenbei: 20 Minuten Z4, verteilt
     * als 30-Sekunden-Antritte über drei Stunden, sind physiologisch etwas völlig anderes
     * als 4×5 min an der Schwelle. Deshalb zählt zusätzlich der ANTEIL an der Fahrzeit.
     * Sehr große Absolutmengen gelten unabhängig davon als Reiz, weil sie auch eingebettet
     * in eine lange Ausfahrt einen echten Trainingsreiz darstellen.
     */
    private data class Rule(val kind: HardKind, val secs: Int, val share: Double, val abs: Int)
    private val Z5 = Rule(HardKind.Z5_PLUS, 360, 0.05, 900)   // ≥6 min UND ≥5 % — oder ≥15 min absolut
    private val Z6 = Rule(HardKind.Z6_PLUS, 180, 0.025, 480)  // ≥3 min UND ≥2,5 % — oder ≥8 min absolut
    private val Z4 = Rule(HardKind.Z4, 1200, 0.15, 2700)      // ≥20 min UND ≥15 % — oder ≥45 min absolut

    /**
     * @param hrvRecoveryAlarm akuter HRV-Einbruch, bestätigt durch Trend oder Ruhepuls —
     *   zusammen mit einem harten Vortag der Anlass für einen Ruhetag
     */
    fun analyze(sessions: List<Session>, ctl: Double?, hrvRecoveryAlarm: Boolean, today: LocalDate,
                p: ScoringParams = ScoringParams.DEFAULT, formPct: Double? = null): LoadHistory {
        val lp = p.load
        val byDay = HashMap<Int, DayLoad>()
        sessions.forEach { s ->
            val ago = Stats.daysBefore(s.localDate, today) ?: return@forEach
            if (ago < 0 || ago > 10) return@forEach
            val o = byDay.getOrPut(ago) { DayLoad() }
            o.load += s.trainingLoad
            o.durationSec += s.movingTimeSec
            val isBike = s.type in Zones.CYCLING
            val isRun = s.type in Zones.RUNNING
            /* Zonen und Intensität nur aus Sportarten mit belastbaren Zonen. E-Bike,
               Wandern usw. fließen nur über die Trainingslast ein — die steckt ohnehin
               in CTL/ATL. Sonst würden unkalibrierte Zonen harmlose Pendelfahrten als
               harten Reiz flaggen. */
            if (isBike || isRun) {
                o.maxIf = max(o.maxIf, s.intensity ?: 0.0)
                o.z5plus += Zones.secondsWhere(s.zoneSeconds) { it >= 5 }
                o.z6plus += Zones.secondsWhere(s.zoneSeconds) { it >= 6 }
                o.zonedSec += s.movingTimeSec
                o.hasZones = o.hasZones || s.hasZones
                if (isBike) {
                    o.z4 += Zones.secondsWhere(s.zoneSeconds) { it == 4 }
                    o.torque += s.torqueWorkSec
                }
            }
        }

        val ctlSafe = if (ctl != null && ctl > 0) ctl else lp.ctlFallback
        fun isTrain(o: DayLoad?) = o != null && o.load >= max(lp.trainMinLoad, lp.trainShareCtl * ctlSafe)

        fun reasons(o: DayLoad?): List<HardReason> {
            if (o == null) return emptyList()
            val r = mutableListOf<HardReason>()
            if (!o.hasZones) {
                if (o.maxIf >= 0.85) r += HardReason(HardKind.IF_NO_ZONES, intensity = o.maxIf)
                return r
            }
            fun add(secs: Int, rule: Rule) {
                val share = if (o.zonedSec > 0) secs / o.zonedSec else 0.0
                if (secs >= rule.abs) r += HardReason(rule.kind, secs, absoluteMin = rule.abs / 60)
                else if (secs >= rule.secs && share >= rule.share)
                    r += HardReason(rule.kind, secs, sharePct = (share * 100).roundToInt())
            }
            add(o.z5plus, Z5); add(o.z6plus, Z6); add(o.z4, Z4)
            // Kraftausdauer: von Zonenzeit allein nicht erfasst — gleiche Watt bei 60 rpm
            // sind ein anderer Reiz als bei 90 rpm.
            if (o.torque >= lp.torqueMinSec) r += HardReason(HardKind.TORQUE, o.torque)
            return r
        }

        /* Plausibilitätsregel: ein Tag, der nicht einmal als Trainingstag zählt, kann kein
           harter Trainingstag sein. */
        fun isHard(o: DayLoad?) = isTrain(o) && reasons(o).isNotEmpty()
        fun isBig(o: DayLoad?) = o != null && o.load >= lp.bigShareCtl * ctlSafe

        /* Reiztyp bestimmen. Ein VO2max-Block und eine Schwelleneinheit belasten
           unterschiedliche Systeme — das ist die Grundlage dafür, am Folgetag einen
           ERGÄNZENDEN statt eines gleichartigen Reizes vorzuschlagen. */
        fun typeOf(o: DayLoad?): StimulusType {
            if (o == null || !isHard(o)) return StimulusType.NONE
            /* Verglichen werden NORMIERTE Anteile, nicht rohe Sekunden.
               Sechs Minuten Z5+ und zwanzig Minuten Z4 sind nach unseren eigenen
               Kriterien gleichwertige Reize — ein direkter Sekundenvergleich benachteiligt
               deshalb systematisch die kurzen, harten Intervalle. */
            val vo2 = (o.z5plus / 900.0)
            val thr = ((o.z4 + o.torque) / 2400.0)
            return when {
                vo2 == 0.0 && thr == 0.0 -> if (isBig(o)) StimulusType.VOLUME else StimulusType.MIXED
                vo2 >= 1.5 * thr -> StimulusType.VO2MAX
                thr >= 1.5 * vo2 -> StimulusType.THRESHOLD
                else -> StimulusType.MIXED
            }
        }

        /* Schweregrad des Reizes als relatives Maß statt eines pauschalen Abzugs.
           Die Bezugsgrößen entsprechen dem, was in der Praxis eine volle Qualitätseinheit
           ausmacht. */
        fun severityOf(o: DayLoad?): Double {
            if (o == null || !isHard(o)) return 0.0
            val vo2 = (o.z5plus / 900.0).coerceAtMost(1.0)          // 15 min Z5+ = voll
            val thr = ((o.z4 + o.torque) / 2400.0).coerceAtMost(1.0) // 40 min Schwelle = voll
            val vol = (o.load / (lp.bigShareCtl * ctlSafe)).coerceAtMost(1.0)
            return (maxOf(vo2, thr) * 0.75 + vol * 0.25).coerceIn(0.0, 1.0)
        }
        fun stat(o: DayLoad?) = o?.let {
            DayStat(it.z5plus, it.z6plus, it.z4, it.torque, it.zonedSec, it.load.roundToInt(), it.maxIf, it.hasZones)
        }

        val todayO = byDay[0]
        val yO = byDay[1]
        val hardY = isHard(yO)
        val bigY = isBig(yO)

        var consec = 0
        for (i in 1..10) { if (isTrain(byDay[i])) consec++ else break }

        /* Aufeinanderfolgende Qualitätstage — Grundlage der Blockbegrenzung.
           Gezählt wird ab dem letzten Tag MIT Reiz: solange heute noch nicht trainiert
           wurde, beginnt die Serie bei gestern. */
        var qStreak = 0
        val qStart = if (isHard(byDay[0])) 0 else 1
        for (i in qStart..10) { if (isHard(byDay[i])) qStreak++ else break }

        /* Foster-Monotonie über die sieben ABGESCHLOSSENEN Tage. Der heutige Tag ist morgens
           eine Null und nach dem Training ein Wert — mit ihm schwankte die Monotonie
           innerhalb eines Tages, ohne dass sich an der Woche etwas geändert hätte. */
        val daily = (1..7).map { byDay[it]?.load ?: 0.0 }
        val mean = daily.sum() / 7
        val sd = Stats.populationSd(daily)!!
        val monotony = if (sd > 0) mean / sd else if (mean > 0) 3.0 else 0.0
        val weekLoad = daily.sum()
        val chronic = ctlSafe * 7
        val highMonotony = monotony > lp.monotonyHigh

        /* Abzug nur für das, was CTL/ATL NICHT abbilden. Serientage stecken bereits in ATL
           und damit in der Form-Komponente — sie hier ein zweites Mal abzuziehen, hätte
           dieselbe Belastung doppelt gezählt. Sie bleiben als Hinweis und in den Regeln. */
        var ded = 0
        val notes = mutableListOf<LoadNoteCode>()
        when {
            consec >= lp.streakRestDays -> notes += LoadNoteCode.STREAK_LONG
            consec == 5 -> notes += LoadNoteCode.STREAK_5
            consec == 4 && highMonotony -> notes += LoadNoteCode.STREAK_4_MONOTONY
        }
        val hardWhy = if (hardY) reasons(yO) else emptyList()
        val severity = severityOf(yO)
        if (hardY) {
            /* Abzug proportional zum Reiz statt pauschal: eine kurze VO2max-Serie kostet
               weniger Erholung als ein voller Schwellenblock. */
            ded += (4 + 10 * severity).roundToInt()
            notes += LoadNoteCode.HARD_YESTERDAY
        }
        if (highMonotony && weekLoad > chronic) { ded += 6; notes += LoadNoteCode.HIGH_MONOTONY }
        ded = min(lp.deductionCap, ded)

        /* Lange Serie: ein Ruhetag ist fällig. ERZWUNGEN wird er aber nur mit einem zweiten
           Signal — mehrere Qualitätstage in der Serie, monotone Belastung, ein bestätigter
           HRV-Alarm oder tiefe Ermüdung. Sechs lockere Tage allein sind kein Grund für
           „Ruhetag empfohlen", nur für einen Deckel auf die Intensität. */
        val qualityInStreak = (1..consec).count { isHard(byDay[it]) }
        val longStreak = consec >= lp.streakRestDays
        val streakEvidence = qualityInStreak >= 2 || highMonotony || hrvRecoveryAlarm ||
            (formPct != null && formPct < p.form.corroborateBelowPct)

        return LoadHistory(
            deduction = ded, notes = notes, consecutiveDays = consec,
            hardYesterday = hardY, bigYesterday = bigY, monotony = monotony, weekLoad = weekLoad,
            capIntensity = hardY || bigY,
            // Ruhetag nur bei konvergenter Evidenz, nicht mechanisch
            forceRest = (longStreak && streakEvidence) || (hardY && hrvRecoveryAlarm) ||
                (highMonotony && consec >= 5 && weekLoad > chronic),
            restDayDue = longStreak && !streakEvidence,
            qualityDaysInStreak = qualityInStreak,
            hardReasons = hardWhy, yesterday = stat(yO),
            trainedToday = isTrain(todayO), hardToday = isHard(todayO),
            todayReasons = if (isHard(todayO)) reasons(todayO) else emptyList(), today = stat(todayO),
            qualityStreak = qStreak, yesterdayType = typeOf(yO), todayType = typeOf(todayO),
            yesterdaySeverity = severity,
        )
    }
}
