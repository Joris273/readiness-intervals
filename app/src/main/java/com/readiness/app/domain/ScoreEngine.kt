package com.readiness.app.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Teilscores und Gesamtbewertung.
 *
 * Gewichtung (siehe [ScoringParams.Weights]): HRV 33 %, Form 28 %, Schlaf 28 %,
 * Ruhepuls 11 %. Fehlende Komponenten fallen heraus und die Gewichte werden neu normiert.
 * Die Ruhepuls-Gewichtung ist bewusst niedrig: HRV und Ruhepuls sind beide autonome
 * Marker und teilredundant, der Ruhepuls ist dabei der langsamere und verrauschtere.
 *
 * Liefert ausschließlich Entscheidungen und Kennzahlen; alle Texte baut der SnapshotMapper.
 */
object ScoreEngine {

    /** Ungültige Einzelwerte dürfen nicht in den Score wandern — sie gelten als „nicht gemessen". */
    private fun num(v: Double?): Double? =
        if (v == null || v.isNaN() || v.isInfinite()) null else v

    /**
     * Form relativ zur Fitness (TSB % der CTL), stetig und stückweise linear:
     * 100 im frischen Bereich (+5 … +25 %), 85 bei −10 %, 50 bei −30 %, Boden 15 ab
     * etwa −53 %. Oberhalb von +25 % fällt der Wert bis 65 — dort sinkt die Fitness.
     */
    fun scoreForm(pct: Double?): Int? {
        val v = num(pct) ?: return null
        val s = when {
            v > 25 -> max(65.0, 100 - (v - 25) * 35.0 / 23.0)
            v >= 5 -> 100.0
            v >= -10 -> 85 + (v + 10)
            v >= -30 -> 50 + (v + 30) * 1.75
            else -> max(15.0, 50 + (v + 30) * 1.5)
        }
        return s.roundToInt()
    }

    /**
     * HRV-Tageswert in SD-Einheiten gegenüber der langen Basis (Ln-rMSSD).
     *
     * 100 bis zur unteren Bandkante (−0,5 SD), dann linear bis 15 bei −3 SD. Werte
     * OBERHALB des Bandes werden hier nicht abgewertet — ob ein hoher Wert verdächtig ist,
     * entscheidet der Kontext (Sättigung, Ausreißer), nicht die Zahl allein.
     */
    fun scoreHrv(z: Double?): Int? = num(z)?.let { zScore(it) }

    /** Abweichung in SD-Einheiten auf 100…15: 100 bis −0,5 SD, dann linear bis 15 bei −3 SD. */
    private fun zScore(z: Double): Int = if (z >= -0.5) 100 else max(15.0, 100 - (-0.5 - z) * 34).roundToInt()

    /**
     * Schlaf aus zwei Quellen, beide INDIVIDUELL bewertet:
     *  – Dauer (Nacht + Nap) gegen den Bedarf,
     *  – Garmin-Schlafscore gegen die eigene Verteilung (z), ohne Historie roh.
     * Liegen beide vor, zählen sie je zur Hälfte. Ein absolut sehr schlechter Schlafscore
     * deckelt trotzdem — Gewöhnung an schlechten Schlaf macht ihn nicht erholsam.
     */
    fun scoreSleep(sleepScore: Double?, scoreZ: Double?, durationH: Double?, avgH: Double?, need: Double?,
                   p: ScoringParams = ScoringParams.DEFAULT): Int? {
        val sc = num(sleepScore)
        val fromScore: Int? = sc?.let { raw -> num(scoreZ)?.let { zScore(it) } ?: raw.roundToInt().coerceIn(0, 100) }
        val dur = num(durationH)
        val fromDuration: Int? = dur?.let { d ->
            val target = num(need) ?: num(avgH) ?: p.sleep.defaultTargetH
            var s = min(100.0, d / target * 100).roundToInt()
            val avg = num(avgH)
            if (avg != null && d < avg - 1) s -= p.sleep.shortNightPenalty
            max(0, s)
        }
        var s = when {
            fromScore != null && fromDuration != null -> ((fromScore + fromDuration) / 2.0).roundToInt()
            else -> fromScore ?: fromDuration ?: return null
        }
        if (sc != null && sc < p.sleep.scoreAbsoluteFloor) s = min(s, p.sleep.scoreAbsoluteFloor)
        return s
    }

    /**
     * Ruhepuls: mit ausreichender Historie gegen die eigene Streuung (100 bis +0,5 SD,
     * linear bis 20 bei +3 SD), sonst als absolute Differenz zur Basis.
     */
    fun scoreRestingHr(z: Double?, diffValue: Double? = null): Int? {
        num(z)?.let { return if (it <= 0.5) 100 else max(20.0, 100 - (it - 0.5) * 32).roundToInt() }
        val diff = num(diffValue) ?: return null
        return when {
            diff <= 0 -> 100
            diff <= 3 -> (100 - diff * 10).roundToInt()
            diff <= 5 -> (70 - (diff - 3) * 10).roundToInt()
            // stetig an den Wert 50 bei +5 bpm anschließen
            else -> max(20.0, 50 - (diff - 5) * 5).roundToInt()
        }
    }

    /**
     * Subjektives Befinden: mit Historie gegen die eigene Streuung (100 bis +0,5 SD,
     * linear bis 15 bei +3 SD), ohne Historie absolut (100 bis Index ⅓, also bis „2 von 4"
     * im Mittel, linear bis 15 bei „alles schlecht").
     */
    fun scoreSubjective(z: Double?, index: Double?): Int? {
        num(z)?.let { return if (it <= 0.5) 100 else max(15.0, 100 - (it - 0.5) * 34).roundToInt() }
        val i = num(index) ?: return null
        return if (i <= 1.0 / 3) 100 else max(15.0, 100 - (i - 1.0 / 3) / (2.0 / 3) * 85).roundToInt()
    }

    fun buildScore(m: Metrics, p: ScoringParams = ScoringParams.DEFAULT): BaseScore {
        val w = p.weights
        val vp = p.validity
        /* Aktualität: eine Messung von gestern zählt mit halbem Gewicht, ältere werden
           angezeigt, aber nicht bewertet. Ein Tagesscore aus Werten von vorgestern wäre
           eine Aussage über vorgestern. */
        fun aged(c: ScoreComponent, age: Int?): ScoreComponent {
            val a = age ?: return c
            return when {
                a > vp.staleAfterDays -> c.copy(sub = null, ageDays = a, stale = true)
                a >= 1 -> c.copy(weight = c.weight * vp.yesterdayWeight, ageDays = a)
                else -> c.copy(ageDays = a)
            }
        }
        val sleepAge = listOfNotNull(m.sleepAgeDays, m.sleepScoreAgeDays).minOrNull()
        val comps = listOf(
            ScoreComponent(ComponentId.FORM, w.form, scoreForm(m.tsbPct)),
            aged(ScoreComponent(ComponentId.HRV, w.hrv, if (m.confInvalid) null else scoreHrv(m.hrvZ)?.let { s ->
                var c = s
                if (m.hrvUnusual) c = min(c, p.hrv.unusualCap)          // Einzelwert zu hoch: möglicher Artefakt
                if (m.hrvSaturation) c = min(c, p.hrv.saturationCap)    // hoch UND verdächtiger Kontext
                c
            }), m.hrvAgeDays),
            aged(ScoreComponent(ComponentId.SLEEP, w.sleep,
                scoreSleep(m.sleepScore, m.sleepScoreZ, m.sleepHours, m.sleepAvgHours, m.sleepNeed, p)), sleepAge),
            aged(ScoreComponent(ComponentId.RHR, w.rhr, scoreRestingHr(m.rhrZ, m.restingHrDiff)), m.rhrAgeDays),
            aged(ScoreComponent(ComponentId.SUBJECTIVE, w.subjective, scoreSubjective(m.subjectiveZ, m.subjectiveIndex)),
                m.subjectiveAgeDays),
        )
        val available = comps.filter { it.sub != null }
        /* Ohne jeden physiologischen Messwert gibt es keinen Readiness-Score: die Form allein
           ist ein Modellwert aus dem eigenen Training, keine Messung des Zustands. */
        if (available.none { it.id != ComponentId.FORM }) return BaseScore(null, comps, true)
        val wSum = available.sumOf { it.weight }
        val withWeights = comps.map { c ->
            if (c.sub != null) c.copy(effectiveWeight = c.weight / wSum) else c
        }
        val total = withWeights.filter { it.sub != null }
            .sumOf { it.sub!! * it.effectiveWeight }.roundToInt()
        return BaseScore(total, withWeights, available.size < comps.size || comps.any { it.ageDays?.let { a -> a >= 1 } == true })
    }

    /**
     * Nicht-kompensatorische Sicherung („limitierender Faktor").
     *
     * Ein gewichteter Mittelwert erlaubt, dass eine starke Domäne eine kritisch schwache
     * überdeckt — physiologisch ist Erholung aber kein Mittelwert: der schwächste
     * Teilbereich begrenzt die Belastbarkeit.
     *
     * Autonome Marker dürfen einen Ruhetag allerdings nicht im Alleingang erzwingen. Ein
     * einzelner HRV-Einbruch ist ein Signal, aber tagesvariabel; erst wenn ein zweiter,
     * unabhängiger Marker mitzieht, ist die Evidenz konvergent.
     */
    fun limitingFactors(comps: List<ScoreComponent>, m: Metrics, p: ScoringParams = ScoringParams.DEFAULT): List<LimitingFactor> {
        val out = mutableListOf<LimitingFactor>()
        val lp = p.limits
        val sleepC = comps.firstOrNull { it.id == ComponentId.SLEEP }
        val rhrUp = m.rhrElevated
        val hrvLow = (m.hrvSuppressed || m.hrvAcuteDrop) && !m.confounded

        comps.forEach { c ->
            val sub = c.sub ?: return@forEach
            /* Autonome Marker und subjektive Angaben sind tagesvariabel: allein erzwingen sie
               keinen Ruhetag, erst mit einem unabhängigen zweiten Marker. */
            val autonomic = c.id == ComponentId.HRV || c.id == ComponentId.RHR || c.id == ComponentId.SUBJECTIVE
            val corroborated =
                (c.id == ComponentId.HRV && (rhrUp || m.subjectiveElevated)) ||
                (c.id == ComponentId.RHR && (hrvLow || m.subjectiveElevated)) ||
                (c.id == ComponentId.SUBJECTIVE && (hrvLow || rhrUp)) ||
                (sleepC?.sub != null && sleepC.sub <= p.sleep.corroborateSub) ||
                (m.tsbPct != null && m.tsbPct < p.form.corroborateBelowPct)
            val cap = autonomic && (m.confounded || !corroborated)
            val evidence = when {
                !autonomic -> Evidence.NOT_APPLICABLE
                m.confounded -> Evidence.EXTERNAL
                cap -> Evidence.UNCONFIRMED
                else -> Evidence.CONFIRMED
            }

            if (c.id == ComponentId.HRV) {
                // keine gültige Messung oder nur vorläufiges Band → kein Befund
                if (m.confInvalid || m.hrvBandProvisional) return@forEach
                /* HRV nach der Abweichung in SD-Einheiten einstufen, nicht nach der Punktzahl:
                   die Punkteskala ist eine Darstellungsentscheidung, die Abweichung von der
                   eigenen Basis dagegen das physiologische Maß. */
                val drop = -(m.hrvZ ?: return@forEach)
                if (drop >= lp.hrvStrongSd) out += LimitingFactor(LimitCode.HRV_STRONG,
                    if (cap) Severity.AMBER else Severity.RED, c.id, drop, evidence, cap)
                else if (drop >= lp.hrvBelowSd) out += LimitingFactor(LimitCode.HRV_BELOW,
                    Severity.AMBER, c.id, drop, evidence)
                return@forEach
            }

            if (sub <= lp.critical) out += LimitingFactor(LimitCode.COMPONENT_CRITICAL,
                if (cap) Severity.AMBER else Severity.RED, c.id, sub.toDouble(), evidence, cap)
            else if (sub <= lp.reduced) out += LimitingFactor(LimitCode.COMPONENT_REDUCED,
                Severity.AMBER, c.id, sub.toDouble(), evidence)
        }

        /* Schlaf: individuell statt normativ. Ein Defizit deckelt nur bei konvergenter
           Evidenz. */
        val deficit = m.sleepDeficit
        if (deficit != null && deficit >= p.sleep.deficitH) {
            val corr = mutableListOf<Corroborator>()
            if (hrvLow) corr += Corroborator.HRV_BELOW_SWC
            if (rhrUp) corr += Corroborator.RHR_ELEVATED
            if (corr.isNotEmpty()) out += LimitingFactor(LimitCode.SLEEP_DEFICIT,
                if (deficit >= p.sleep.deficitRedH && corr.size >= 2) Severity.RED else Severity.AMBER,
                ComponentId.SLEEP, deficit, corroborators = corr)
        }
        /* Subjektiv: Erschöpfung oder Muskelkater auf der schlechtesten Stufe deckelt die
           Intensität. Zum Ruhetag wird es erst, wenn ein objektiver Marker mitzieht —
           dieselbe Konvergenz-Regel wie bei den autonomen Markern. */
        if (m.subjectiveWorst) {
            val corr = mutableListOf<Corroborator>()
            if (m.hrvRecoveryAlarm && !m.confounded) corr += Corroborator.HRV_ALARM
            if (rhrUp) corr += Corroborator.RHR_ELEVATED
            out += LimitingFactor(LimitCode.SUBJECTIVE_WORST, if (corr.isEmpty()) Severity.AMBER else Severity.RED,
                ComponentId.SUBJECTIVE, corroborators = corr)
        }

        /* Absoluter Sicherheitsboden: unter sechs Stunden im Wochenschnitt sind Leistungs-
           und Immuneinbußen so konsistent belegt, dass Gewöhnung sie nicht aufhebt; unter
           fünf Stunden ist das ein Grund für einen Ruhetag. */
        val s7 = m.sleep7Effective
        if (s7 != null && s7 < p.sleep.floorH)
            out += LimitingFactor(LimitCode.SLEEP_FLOOR,
                if (s7 < p.sleep.floorRedH) Severity.RED else Severity.AMBER, ComponentId.SLEEP, s7)

        return out
    }

    /**
     * @param score angezeigter Tageswert (Basis abzüglich Belastung)
     * @param baseScore reiner Erholungszustand aus den Messwerten, OHNE Belastungsabzug
     *
     * Die Ampel richtet sich nach dem BASISWERT, nicht nach dem angezeigten Score.
     * Andernfalls wirkt die Belastung doppelt: einmal, indem sie den Score senkt, und
     * ein zweites Mal über die Belastungsregeln unten.
     *
     * Die Aufgabenteilung ist deshalb: die Messwerte sagen, wie erholt du BIST; die
     * Belastungsregeln sagen, was angesichts des zuletzt Trainierten SINNVOLL ist.
     */
    fun buildRecommendation(score: Int?, m: Metrics, lh: LoadHistory, limits: List<LimitingFactor>,
                            comps: List<ScoreComponent> = emptyList(), baseScore: Int? = null,
                            p: ScoringParams = ScoringParams.DEFAULT): Recommendation {
        if (score == null) return Recommendation(Verdict.UNKNOWN)
        val vp = p.verdict

        val band = baseScore ?: score
        var r = if (band >= vp.green) Verdict.GREEN else if (band >= vp.amber) Verdict.AMBER else Verdict.RED
        val notes = mutableListOf<Note>()
        fun note(c: NoteCode) { notes += Note(c) }

        val deepFatigue = m.tsbPct != null && m.tsbPct < p.form.redBelowPct
        if (deepFatigue) { r = Verdict.RED; note(NoteCode.FORM_DEEP_FATIGUE) }
        if (lh.forceRest) { r = Verdict.RED; note(NoteCode.LOAD_FORCE_REST) }
        else if (lh.restDayDue && r == Verdict.GREEN) { r = Verdict.AMBER; note(NoteCode.REST_DAY_DUE) }
        else if (lh.capIntensity && r == Verdict.GREEN) {
            /* Die gemessene Erholung entscheidet, nicht der Abstand (Rønnestad: HIT an
               aufeinanderfolgenden Tagen; Vesterinen/Düking: HRV-gesteuerte Steuerung).
               Ein zweiter Qualitätstag ist zulässig, wenn die Marker unauffällig sind. Als
               Sicherung bleibt die Blockgrenze. */
            /* Ein als Störfaktor markierter Messtag darf den zweiten Qualitätstag nicht
               blockieren: bei einem Artefakt gibt es keine gültige Messung, bei externer
               Ursache greift ohnehin die eigene Regel weiter unten. */
            val hrvLow = (m.hrvSuppressed || m.hrvAcuteDrop || m.hrvWeekAlarm) && !m.confounded
            val recoveryClear = !hrvLow && !m.rhrElevated &&
                (comps.firstOrNull { it.id == ComponentId.SLEEP }?.sub ?: 100) >= vp.recoverySleepSub &&
                (m.tsbPct == null || m.tsbPct > p.form.corroborateBelowPct) &&
                limits.none { it.severity == Severity.RED }

            if (lh.qualityStreak >= vp.qualityStreakMax) { r = Verdict.AMBER; note(NoteCode.QUALITY_STREAK_END) }
            else if (!lh.hardYesterday) { r = Verdict.AMBER; note(NoteCode.BIG_DAY_YESTERDAY) }
            else if (!recoveryClear) { r = Verdict.AMBER; note(NoteCode.HARD_YESTERDAY_NOT_RECOVERED) }
            else note(NoteCode.SECOND_QUALITY_DAY)
        }
        if (m.hrvSuppressed && !m.confounded && r == Verdict.GREEN) { r = Verdict.AMBER; note(NoteCode.HRV_BELOW_BAND) }

        /* Hohe HRV bei verdächtigem Kontext: möglicherweise funktionelle Überlastung. */
        if (m.hrvSaturation && !m.confounded) {
            if (r == Verdict.GREEN) r = Verdict.AMBER
            note(NoteCode.HRV_SATURATION)
        }

        /* Abfallender Wochentrend gegen die vier Wochen davor: ein PLANUNGSSIGNAL über Wochen,
           kein Tagesbefund. Die Tagesentscheidung trägt bereits das 7-Tage-Mittel gegen die
           lange Basis (hrvSuppressed) — beide als Deckel zu führen, hätte dieselbe Evidenz
           doppelt gezählt und die Fehlalarme addiert. */
        if (m.hrvWeekAlarm && !m.confounded) note(NoteCode.HRV_WEEK_ALARM)

        limits.forEach { l ->
            if (l.severity == Severity.RED) { r = Verdict.RED; notes += Note(NoteCode.LIMITING_FACTOR, l) }
            else if (r == Verdict.GREEN) { r = Verdict.AMBER; notes += Note(NoteCode.LIMITING_FACTOR, l) }
        }

        /* Mindestdatenlage: GRÜN verlangt einen gültigen, aktuellen AUTONOMEN Messwert.
           Form und Schlaf allein sagen nichts darüber, wie das Nervensystem die Belastung
           verarbeitet hat. */
        val autonomic = comps.any {
            it.sub != null && ((it.id == ComponentId.HRV && !m.hrvBandProvisional) || it.id == ComponentId.RHR)
        }
        if (!autonomic && r == Verdict.GREEN) { r = Verdict.AMBER; note(NoteCode.INSUFFICIENT_PHYSIOLOGY) }

        if (m.tsbPct != null && m.tsbPct > p.form.veryHighAbovePct && r == Verdict.GREEN) note(NoteCode.FORM_VERY_HIGH)
        if (m.acwr != null && m.acwr > vp.acwrNote) note(NoteCode.ACWR_HIGH)

        when {
            m.confIllness -> { r = Verdict.RED; note(NoteCode.ILLNESS) }
            m.confInvalid && !m.confExternal -> note(NoteCode.HRV_ARTIFACT)
            m.confExternal -> {
                val trainingReason = lh.forceRest || deepFatigue ||
                    limits.any { it.severity == Severity.RED }
                if (r == Verdict.RED && !trainingReason) r = Verdict.AMBER
                if (r == Verdict.GREEN) r = Verdict.AMBER
                note(NoteCode.EXTERNAL_CAUSE)
            }
        }

        /* Ist heute bereits trainiert worden, wechselt die Fragestellung: der Score
           beschreibt die Bereitschaft VOR der Einheit. */
        return Recommendation(if (lh.trainedToday) Verdict.DONE else r, r, notes)
    }
}
