package com.readiness.app.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * FORMAUFBAU: Dosis gegen Antwort.
 *
 * Wissenschaftlicher Kern: CTL ist die DOSIS (Modell-Output aus dem eigenen
 * Trainings-Input), nicht die WIRKUNG. Steigende CTL heißt nicht „fitter". Um
 * Formaufbau zu belegen, braucht es Output-Marker — deshalb werden Dosis und Antwort
 * getrennt erhoben und über gleich lange Fenster verglichen.
 *
 * Deload-Robustheit ist eingebaut, nicht nachgereicht: lange Fenster, Maximum- bzw.
 * Median-Statistik und explizite Erkennung von Entlastungswochen. Im Taper sinkt die
 * Last, während die Leistung steigt — ein lastbasiertes Fortschrittsmaß wäre dort
 * aktiv irreführend.
 */
object ProgressionAnalyzer {

    private fun pct(now: Double?, prev: Double?): Double? =
        if (now != null && prev != null && prev != 0.0) (now / prev - 1) * 100 else null

    /** Eine aerobe Einheit: Intensität (IF), NP/HF, auf der Rolle? */
    private data class EfPoint(val x: Double, val y: Double, val trainer: Boolean)

    private data class EfResult(
        val now: Double?, val prev: Double?, val deltaPct: Double?, val band: Double,
        val adjusted: Boolean, val environment: EfEnvironment?, val nNow: Int, val nPrev: Int,
    )

    /** Rauschband einer Differenz zweier Fenstermittel/-mediane: z · TE · √(1/n₁ + 1/n₂). */
    private fun diffBand(sd: Double?, n1: Int, n2: Int, minBand: Double, z: Double): Double =
        if (sd == null || n1 < 1 || n2 < 1) minBand else maxOf(minBand, z * sd * kotlin.math.sqrt(1.0 / n1 + 1.0 / n2))

    /**
     * Aerobe Effizienz NP/HF, um Intensität UND Umgebung bereinigt.
     *
     * Beide Fenster gemeinsam auf NP/HF = a + b·Intensität (+ c·Rolle) legen und je Fenster
     * den Median der zurückgerechneten Werte bilden. Gemeinsame Schätzung, weil Steigung
     * und Rollen-Versatz Eigenschaften des Athleten sind, nicht des Zeitraums. Auf der Rolle
     * liegt die HF bei gleicher Leistung meist höher (Wärme, fehlender Fahrtwind) — ohne
     * Bereinigung sähe ein Winter auf der Rolle wie Formverlust aus.
     */
    private fun efficiency(nowP: List<EfPoint>, prevP: List<EfPoint>, pp: ScoringParams.ProgressionParams): EfResult {
        var now = nowP; var prev = prevP
        val all = now + prev
        /* Der Rollen-Versatz lässt sich nur schätzen, wenn BEIDE Umgebungen in BEIDEN
           Fenstern vorkommen. Fällt die Umgebung mit dem Zeitraum zusammen (vorher Straße,
           jetzt Rolle), wären Versatz und echte Veränderung nicht zu trennen — dann wird
           nur die Umgebung verglichen, die in beiden Fenstern vorliegt. */
        fun both(trainer: Boolean) = now.count { it.trainer == trainer } >= 2 && prev.count { it.trainer == trainer } >= 2
        val env = when {
            both(true) && both(false) -> EfEnvironment.MIXED_ADJUSTED
            both(false) -> EfEnvironment.OUTDOOR
            both(true) -> EfEnvironment.INDOOR
            all.all { !it.trainer } -> EfEnvironment.OUTDOOR
            all.all { it.trainer } -> EfEnvironment.INDOOR
            else -> EfEnvironment.MIXED_RAW
        }
        if (env == EfEnvironment.OUTDOOR || env == EfEnvironment.INDOOR) {
            val keepTrainer = env == EfEnvironment.INDOOR
            now = now.filter { it.trainer == keepTrainer }; prev = prev.filter { it.trainer == keepTrainer }
        }
        val pooled = now + prev
        var adj: (EfPoint) -> Double = { it.y }
        var adjusted = false
        if (pooled.size >= 4) {
            val mx = pooled.map { it.x }.average(); val my = pooled.map { it.y }.average()
            val mt = pooled.map { if (it.trainer) 1.0 else 0.0 }.average()
            var sxx = 0.0; var sxy = 0.0; var stt = 0.0; var sxt = 0.0; var sty = 0.0
            pooled.forEach { e ->
                val dx = e.x - mx; val dy = e.y - my; val dt = (if (e.trainer) 1.0 else 0.0) - mt
                sxx += dx * dx; sxy += dx * dy; stt += dt * dt; sxt += dx * dt; sty += dt * dy
            }
            if (env == EfEnvironment.MIXED_ADJUSTED && pooled.size >= 5) {
                val det = sxx * stt - sxt * sxt
                /* Ohne Streuung der Intensität bleibt nur der Rollen-Versatz zu schätzen. */
                val flatX = sxx < 1e-9
                val b = when { flatX -> 0.0; det > 1e-12 -> (sxy * stt - sty * sxt) / det; else -> Double.NaN }
                val c = when { flatX && stt > 1e-12 -> sty / stt; det > 1e-12 -> (sty * sxx - sxy * sxt) / det; else -> Double.NaN }
                // Plausibilität: NP/HF 1,5–2,0, IF 0,6–0,85; Rollen-Versatz höchstens ±1
                if (b.isFinite() && abs(b) < 5.0 && c.isFinite() && abs(c) < 1.0) {
                    adjusted = true
                    adj = { it.y - b * (it.x - mx) - c * (if (it.trainer) 1.0 else 0.0) }
                }
            } else {
                val b = if (sxx > 1e-9) sxy / sxx else Double.NaN
                if (b.isFinite() && abs(b) < 5.0) { adjusted = true; adj = { it.y - b * (it.x - mx) } }
            }
        }
        val nowAdj = now.map(adj); val prevAdj = prev.map(adj)
        val nowVal = Stats.median(nowAdj); val prevVal = Stats.median(prevAdj)
        val delta = if (now.size >= 2 && prev.size >= 2) pct(nowVal, prevVal) else null
        /* Typical Error als relative Streuung der bereinigten Einzelwerte im älteren Fenster;
           der Median ist etwas unschärfer als das Mittel (Faktor ≈ 1,25). */
        val rel = if (prevAdj.size >= 3 && prevVal != null && prevVal > 0) Stats.sampleSd(prevAdj)?.let { it / prevVal * 100 * 1.25 } else null
        return EfResult(nowVal, prevVal, delta, diffBand(rel, now.size, prev.size, pp.minBandEf, pp.bandZ),
            adjusted, if (all.isEmpty()) null else env, now.size, prev.size)
    }

    fun analyze(
        wellness: List<WellnessDay>, sessions: List<Session>, cfg: AnalysisConfig,
        today: LocalDate, scan: TorqueScan? = null,
    ): Progression {
        val p = cfg.params
        val pp = p.progression
        val win = cfg.windowDays
        val confounded = cfg.confoundedDays
        val dayIdx = HashMap<String, Int>()
        fun idxOf(date: String): Int = dayIdx.getOrPut(date) { Stats.daysBefore(date, today) ?: Int.MIN_VALUE }
        fun inWin(date: String, from: Int, to: Int): Boolean { val a = idxOf(date); return a in from until to }

        val bikes = sessions.filter { it.type in Zones.CYCLING }
        val hrCeiling = EffortQuality.hrCeiling(bikes, p)

        // ---- 1) Leistung: bestes eFTP je Fenster ----
        var eNow = 0.0; var ePrev = 0.0; var eNowAt: Int? = null; var ePrevAt: Int? = null
        var eftpCount = 0
        bikes.forEach { s ->
            val e = s.eftp ?: return@forEach
            if (inWin(s.localDate, 0, win)) { eftpCount++; if (e > eNow) { eNow = e; eNowAt = idxOf(s.localDate) } }
            else if (inWin(s.localDate, win, 2 * win)) { eftpCount++; if (e > ePrev) { ePrev = e; ePrevAt = idxOf(s.localDate) } }
        }
        /* Trennschärfe der Maximum-Statistik: der zeitliche Abstand der Bestwerte zweier
           benachbarter Fenster kann zwischen zwei Tagen und der doppelten Fensterlänge
           liegen. Liegen beide nah an der gemeinsamen Grenze, wird die Differenz
           systematisch zu klein — der Fehler ist konservativ, gehört aber ausgewiesen. */
        val eftpSep = if (eNowAt != null && ePrevAt != null) abs(ePrevAt!! - eNowAt!!) else null

        /* Ein eFTP-RÜCKGANG ist nur dann eine Aussage, wenn im aktuellen Fenster überhaupt
           ein Antritt lag, der eFTP hätte setzen können — intervals.icu hebt den Wert nur
           bei maximalen Antritten an und lässt ihn sonst zerfallen. */
        val eftpAttemptNow = bikes.any { inWin(it.localDate, 0, win) && EffortQuality.canSetEftp(it, hrCeiling, p) }

        // ---- 2) Aerobe Effizienz: NP/HF bei aeroben Einheiten, um die Intensität bereinigt ----
        /* NP/HF wächst innerhalb des aeroben Bandes mit der Intensität. Deshalb wird die
           Intensität nicht nur gefiltert, sondern herausgerechnet. */
        val efNowP = mutableListOf<EfPoint>()
        val efPrevP = mutableListOf<EfPoint>()
        var diag = ProgressionDiag(rides = bikes.size)
        bikes.forEach { s ->
            val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
            if (!cur && !prv) return@forEach
            diag = diag.copy(ridesInWindow = diag.ridesInWindow + 1)
            val np = s.normalizedPower; val hr = s.avgHeartRate
            if (np == null || hr == null || hr < 60) return@forEach
            diag = diag.copy(withPowerHr = diag.withPowerHr + 1)
            if (s.movingTimeSec < 1800) return@forEach
            diag = diag.copy(longEnough = diag.longEnough + 1)
            val inten = s.intensity ?: 0.0
            if (inten > pp.efIntensityMax || inten < pp.efIntensityMin) return@forEach   // keine Intervalle, keine Rollerei
            diag = diag.copy(aerobic = diag.aerobic + 1)
            if (cur) efNowP += EfPoint(inten, np / hr, s.trainer) else efPrevP += EfPoint(inten, np / hr, s.trainer)
        }
        diag = diag.copy(eftpValues = eftpCount)

        val ef = efficiency(efNowP, efPrevP, pp)
        val efNowVal = ef.now; val efPrevVal = ef.prev
        val efDelta = ef.deltaPct

        // ---- 2b) Aerobe Entkopplung (Seiler): fallend ist gut ----
        val dcNow = mutableListOf<Double>(); val dcPrev = mutableListOf<Double>()
        bikes.forEach { s ->
            val v = s.decoupling ?: return@forEach
            if (s.movingTimeSec < 3600) return@forEach
            if ((s.intensity ?: 0.0) > 0.85) return@forEach
            if (inWin(s.localDate, 0, win)) dcNow += v else if (inWin(s.localDate, win, 2 * win)) dcPrev += v
        }
        val decNow = Stats.median(dcNow); val decPrev = Stats.median(dcPrev)
        // absolute Differenz in Prozentpunkten (Decoupling ist selbst schon eine Prozentzahl)
        val decDelta = if (dcNow.size >= 2 && dcPrev.size >= 2 && decNow != null && decPrev != null) decNow - decPrev else null
        // Streuung in Prozentpunkten; Median statt Mittel → Faktor ≈ 1,25
        val decBand = diffBand(if (dcPrev.size >= 3) Stats.sampleSd(dcPrev)?.times(1.25) else null,
            dcNow.size, dcPrev.size, pp.minBandDecPp, pp.bandZ)
        /* Maxima (eFTP, Kraftdauern) haben keine Stichprobe, aus der sich eine Streuung
           schätzen ließe: ein Bestwert je Fenster. Ihr Band ist deshalb die typische
           Streuung eines Einzelbestwerts, für die Differenz zweier Fenster × √2. */
        val maxStatBand = pp.bandZ * pp.maxStatNoisePct * kotlin.math.sqrt(2.0)

        // ---- 2c) Kraft: Leistung bei konditionierter Trittfrequenz, je Dauer getrennt ----
        val durations = Streams.DURATIONS.map { key ->
            var now = 0; var prev = 0; var nNow = 0; var nPrev = 0
            var aNow = 0; var aPrev = 0
            var nowAt: Int? = null; var prevAt: Int? = null
            bikes.forEach { s ->
                val t = s.torque ?: return@forEach
                val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
                if (!cur && !prv) return@forEach
                val v = when (key) { DurationKey.D60 -> t.d60; DurationKey.D300 -> t.d300; DurationKey.D600 -> t.d600 }
                val c = when (key) { DurationKey.D60 -> t.n60; DurationKey.D300 -> t.n300; DurationKey.D600 -> t.n600 }
                val attempt = EffortQuality.isAttempt(t, key, hrCeiling, p)
                if (cur) { if (v != null && v > now) { now = v; nowAt = idxOf(s.localDate) }; nNow += c; if (attempt) aNow++ }
                else { if (v != null && v > prev) { prev = v; prevAt = idxOf(s.localDate) }; nPrev += c; if (attempt) aPrev++ }
            }
            /* ASYMMETRIE. Ein Maximum belegt eine UNTERE SCHRANKE der Leistungsfähigkeit.
               Ein Anstieg ist damit immer ein Beweis. Ein RÜCKGANG beweist für sich genommen
               nichts: er kann ebenso gut heißen, dass die Dauer diesmal nie maximal gefahren
               wurde. Deshalb zählt er nur mit nachgewiesenem Versuch. */
            val raw = if (now > 0 && prev > 0) pct(now.toDouble(), prev.toDouble()) else null
            val suppress = raw != null && raw < 0 && aNow == 0
            DurationProgress(
                key = key,
                now = now.takeIf { it > 0 }, prev = prev.takeIf { it > 0 },
                band = maxOf(pp.minBandDuration, maxStatBand),
                nNow = nNow, nPrev = nPrev,
                deltaPct = if (suppress) null else raw,
                // „°" in der Anzeige: in einem der Zeiträume fehlt ein Maximalversuch
                thin = aNow == 0 || aPrev == 0,
                separationDays = if (nowAt != null && prevAt != null) abs(prevAt!! - nowAt!!) else null,
                attemptsNow = aNow, attemptsPrev = aPrev, suppressedNoAttempt = suppress,
            )
        }

        val lcEfN = mutableListOf<Double>(); val lcEfP = mutableListOf<Double>()
        var tqN = 0.0; var tqP = 0.0
        bikes.forEach { s ->
            val t = s.torque ?: return@forEach
            val cur = inWin(s.localDate, 0, win); val prv = inWin(s.localDate, win, 2 * win)
            if (!cur && !prv) return@forEach
            t.efficiency?.let { if (cur) lcEfN += it else lcEfP += it }
            t.peakTorque30s?.let { if (cur) { if (it > tqN) tqN = it } else { if (it > tqP) tqP = it } }
        }
        val lcEfDelta = if (lcEfN.isNotEmpty() && lcEfP.isNotEmpty()) pct(Stats.median(lcEfN), Stats.median(lcEfP)) else null
        val lcEfBand = diffBand(Stats.median(lcEfP)?.let { m ->
            if (lcEfP.size >= 3 && m > 0) Stats.sampleSd(lcEfP)?.let { it / m * 100 * 1.25 } else null
        }, lcEfN.size, lcEfP.size, pp.minBandLcEf, pp.bandZ)
        val lcEfThin = !(lcEfN.size >= 2 && lcEfP.size >= 2)

        // ---- 3) Belastung: CTL-Trend, Rampe, Deload ----
        val wByDate = wellness.associateBy { it.date }
        fun ctlNear(back: Int): Double? {
            for (off in listOf(0, 1, -1, 2, -2, 3, -3)) {
                val k = back + off; if (k < 0) continue
                wByDate[today.minusDays(k.toLong()).toString()]?.ctl?.let { return it }
            }
            return null
        }
        val ctlNow = ctlNear(0); val ctlPrev = ctlNear(win)
        val loadByDay = HashMap<String, Double>()
        sessions.forEach { loadByDay[it.localDate] = (loadByDay[it.localDate] ?: 0.0) + it.trainingLoad }
        fun weekLoad(from: Int, to: Int): Double {
            var s = 0.0; for (i in from until to) s += loadByDay[today.minusDays(i.toLong()).toString()] ?: 0.0
            return s
        }
        val week0 = weekLoad(0, 7); val week4avg = weekLoad(7, 35) / 4
        val deload = week4avg > 0 && week0 < 0.7 * week4avg

        // ---- 4) Intensitätsverteilung 28 Tage ----
        var z12 = 0; var z3 = 0; var z4p = 0
        bikes.forEach { s ->
            if (!s.hasZones || !inWin(s.localDate, 0, 28)) return@forEach
            z12 += Zones.secondsWhere(s.zoneSeconds) { it <= 2 }
            z3 += Zones.secondsWhere(s.zoneSeconds) { it == 3 }
            z4p += Zones.secondsWhere(s.zoneSeconds) { it >= 4 }
        }
        val zTot = z12 + z3 + z4p

        // ---- 5) HRV-Chronik: 28 Tage gegen die 28 davor ----
        val lnNow = mutableListOf<Double>(); val lnPrev = mutableListOf<Double>()
        wellness.forEach { d ->
            val v = Stats.valid(d.hrv) ?: return@forEach
            if (d.date in confounded) return@forEach
            val a = idxOf(d.date)
            if (a in 0..27) lnNow += ln(v) else if (a in 28..55) lnPrev += ln(v)
        }
        val hrvChronNow = if (lnNow.size >= 10) exp(lnNow.average()) else null
        val hrvChronPrev = if (lnPrev.size >= 10) exp(lnPrev.average()) else null

        // ---- Verdikt ----
        val dose = pct(ctlNow, ctlPrev)?.let { if (it > 3) 1 else if (it < -3) -1 else 0 }

        /* Antwortseite MEHRGLEISIG und bewusst NICHT nur als Mittelwert. Ein Plateau liegt
           physiologisch erst dann vor, wenn sich NIRGENDS etwas bewegt. */
        val eftpRaw = pct(if (eNow > 0) eNow else null, if (ePrev > 0) ePrev else null)
        val eftpSuppressed = eftpRaw != null && eftpRaw < 0 && !eftpAttemptNow
        val eftpDelta = if (eftpSuppressed) null else eftpRaw

        val eftpBand = maxOf(pp.minBandEftp, maxStatBand)
        val markers = mutableListOf<MarkerDelta>()
        eftpDelta?.let { markers += MarkerDelta(Marker.EFTP, it, eftpBand) }
        efDelta?.let { markers += MarkerDelta(Marker.AEROBIC_EF, it, ef.band) }
        /* Entkopplung ohne Verstärkungsfaktor: ein Prozentpunkt zählt wie ein Prozent. */
        decDelta?.let { markers += MarkerDelta(Marker.DECOUPLING, -it, decBand) }             // fallend = gut
        durations.forEach { d -> d.deltaPct?.let { markers += MarkerDelta(Marker.of(d.key), it, d.band) } }
        if (!lcEfThin && lcEfDelta != null) markers += MarkerDelta(Marker.LC_EFFICIENCY, lcEfDelta, lcEfBand)

        val noAttempt = durations.filter { it.suppressedNoAttempt }.map { Marker.of(it.key) } +
            (if (eftpSuppressed) listOf(Marker.EFTP) else emptyList())

        /* KONSENS statt „einer reicht". Jeder Marker wird gegen SEIN Rauschband gelesen.
           Ein Verdikt trägt erst, wenn mindestens zwei UNABHÄNGIGE Marker-Familien
           gleichgerichtet über ihrem Band liegen oder ein Marker weit darüber (doppeltes
           Band) — und nichts dagegen spricht. Mit sieben verrauschten Markern bewegt sich
           sonst fast immer irgendeiner, und korrelierte Marker (1, 5 und 10 min aus denselben
           Einheiten) würden denselben Zufall mehrfach zählen. */
        val up = markers.filter { it.deltaPct > it.band }
        val down = markers.filter { it.deltaPct < -it.band }
        val upFamilies = up.map { it.marker.family }.distinct().size
        val downFamilies = down.map { it.marker.family }.distinct().size
        val strongUp = up.any { it.deltaPct > pp.strongFactor * it.band }
        val strongDown = down.any { it.deltaPct < -pp.strongFactor * it.band }
        val balance = if (markers.isNotEmpty()) markers.map { it.deltaPct / it.band }.average() else 0.0
        var basis = ResponseBasis.NONE
        var weak: Marker? = null
        val rsp: Int? = when {
            markers.isEmpty() -> null
            up.isNotEmpty() && down.isNotEmpty() -> {
                basis = ResponseBasis.BALANCE
                if (balance > 1) 1 else if (balance < -1) -1 else 0
            }
            upFamilies >= pp.consensusMin -> { basis = ResponseBasis.CONSENSUS; 1 }
            downFamilies >= pp.consensusMin -> { basis = ResponseBasis.CONSENSUS; -1 }
            strongUp -> { basis = ResponseBasis.STRONG_SINGLE; 1 }
            strongDown -> { basis = ResponseBasis.STRONG_SINGLE; -1 }
            up.isNotEmpty() || down.isNotEmpty() -> { basis = ResponseBasis.WEAK_SINGLE; weak = (up + down).first().marker; 0 }
            else -> { basis = ResponseBasis.FLAT; 0 }
        }

        val verdict = when {
            dose == null || rsp == null -> ProgressionVerdict.INSUFFICIENT
            dose >= 0 && rsp == 1 -> if (up.size < markers.size) ProgressionVerdict.FOCUSED else ProgressionVerdict.PRODUCTIVE
            dose == 1 && rsp == 0 -> ProgressionVerdict.STIMULUS_PENDING
            dose == 1 && rsp == -1 -> ProgressionVerdict.LOAD_UP_RESPONSE_DOWN
            dose == -1 && rsp >= 0 -> ProgressionVerdict.DELOAD_WORKS
            dose == -1 && rsp == -1 -> ProgressionVerdict.DETRAINING
            else -> ProgressionVerdict.PLATEAU
        }
        val chronDelta = pct(hrvChronNow, hrvChronPrev)

        return Progression(
            ok = true, windowDays = win, verdict = verdict, dose = dose, response = rsp,
            hrvChronWarning = chronDelta != null && dose == 1 && chronDelta < -3,
            responseBasis = basis, weakMarker = weak,
            eftpBand = eftpBand, efBand = ef.band, decBand = decBand, lcEfBand = lcEfBand,
            efEnvironment = ef.environment,
            eftpNow = eNow.takeIf { it > 0 }?.roundToInt(), eftpPrev = ePrev.takeIf { it > 0 }?.roundToInt(),
            eftpDeltaPct = eftpDelta,
            eftpSeparationDays = eftpSep,
            eftpSuppressedNoAttempt = eftpSuppressed,
            efNow = efNowVal, efPrev = efPrevVal, efDeltaPct = efDelta,
            efN = ef.nNow, efNPrev = ef.nPrev, efIntensityAdjusted = ef.adjusted,
            ctlNow = ctlNow, ctlPrev = ctlPrev, ctlDeltaPct = pct(ctlNow, ctlPrev),
            rampPerWeek = if (ctlNow != null && ctlPrev != null) (ctlNow - ctlPrev) / (win / 7.0) else null,
            deloadNow = deload,
            share12 = if (zTot > 0) z12 * 100.0 / zTot else null,
            share3 = if (zTot > 0) z3 * 100.0 / zTot else null,
            share4 = if (zTot > 0) z4p * 100.0 / zTot else null,
            zoneHours = if (zTot > 0) zTot / 3600.0 else null,
            hrvChronNow = hrvChronNow, hrvChronPrev = hrvChronPrev, hrvChronDeltaPct = chronDelta,
            decNow = decNow, decPrev = decPrev, decDeltaPp = decDelta,
            decN = dcNow.size, decNPrev = dcPrev.size,
            durations = durations,
            lcEfNow = Stats.median(lcEfN), lcEfPrev = Stats.median(lcEfP), lcEfDeltaPct = lcEfDelta,
            lcEfN = lcEfN.size, lcEfNPrev = lcEfP.size, lcEfThin = lcEfThin,
            peakTorqueNow = tqN.takeIf { it > 0 }, peakTorquePrev = tqP.takeIf { it > 0 },
            markersUsed = markers.map { it.marker }, drivers = up.map { it.marker }, decliners = down.map { it.marker },
            noAttemptMarkers = noAttempt,
            diag = diag, torqueScan = scan,
        )
    }
}
