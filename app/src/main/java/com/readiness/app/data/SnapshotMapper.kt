package com.readiness.app.data

import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.ComponentId
import com.readiness.app.domain.Confounders
import com.readiness.app.domain.Corroborator
import com.readiness.app.domain.DurationKey
import com.readiness.app.domain.DurationProgress
import com.readiness.app.domain.EfEnvironment
import com.readiness.app.domain.ResponseBasis
import com.readiness.app.domain.Evidence
import com.readiness.app.domain.HardKind
import com.readiness.app.domain.HardReason
import com.readiness.app.domain.LimitCode
import com.readiness.app.domain.LimitingFactor
import com.readiness.app.domain.LoadHistory
import com.readiness.app.domain.LoadNoteCode
import com.readiness.app.domain.Marker
import com.readiness.app.domain.Metrics
import com.readiness.app.domain.Note
import com.readiness.app.domain.NoteCode
import com.readiness.app.domain.Progression
import com.readiness.app.domain.ProgressionVerdict
import com.readiness.app.domain.ReadinessResult
import com.readiness.app.domain.ScoreComponent
import com.readiness.app.domain.ScoringParams
import com.readiness.app.domain.Severity
import com.readiness.app.domain.StimulusType
import com.readiness.app.domain.Streams
import com.readiness.app.domain.Verdict
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Übersetzt das Domänenergebnis in das speicher- und anzeigbare Snapshot-Modell.
 *
 * ALLE Texte der App entstehen hier. Die Domäne liefert nur Entscheidungen, Codes und
 * Kennzahlen — so prüfen die Tests Entscheidungen statt Formulierungen, und eine
 * Textänderung kann die wissenschaftliche Prüfung nicht brechen.
 */
object SnapshotMapper {

    // ---------------------------------------------------------------- Formatierung

    private fun f(v: Double?, d: Int = 1) =
        if (v == null || v.isNaN()) "–" else String.format("%.${d}f", v).replace('.', ',')
    /** Ganzzahlig gerundet wie in der Domäne (roundToInt). */
    private fun i0(v: Double?) = if (v == null || !v.isFinite()) "–" else v.roundToInt().toString()
    private fun sgn(v: Double?) = if (v != null && v >= 0) "+" else ""
    /** Stunden ohne überflüssige Nachkommastelle: 5 → „5", 5,5 → „5,5". */
    private fun hours(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else f(v)

    // ---------------------------------------------------------------- Einstieg

    fun map(r: ReadinessResult, cfg: AnalysisConfig, scoreContext: String? = null): Snapshot {
        val m = r.metrics
        val lh = r.loadHistory
        val p = cfg.params
        val level = level(r.recommendation.verdict, r, p)

        val band = if (m.hrvBandLo != null && m.hrvBandHi != null)
            "${f(m.hrvBandLo, 0)}–${f(m.hrvBandHi, 0)} ms" else null
        val weekPart = m.hrvWeekDevPct?.let {
            " · Woche ${sgn(it)}${f(it)} %" + when {
                m.hrvWeekAlarm -> " ⚠"; m.hrvWeekDown -> " ↓"; m.hrvWeekUp -> " ↑"; else -> ""
            }
        } ?: ""
        val prov = if (m.hrvBandProvisional) " (vorläufig)" else ""
        val hrvSub = when {
            m.hrv == null -> "keine Messung"
            band == null -> "zu wenig Verlauf für dein Normalband"
            m.hrvSuppressed -> "↓ 7-T-Mittel unter deinem Normalband $band$prov$weekPart"
            m.hrvSaturation -> "↑ 7-T-Mittel auffällig hoch · Band $band$prov$weekPart"
            m.hrvAcuteDrop -> "↓ heute unter deinem Normalband $band$prov$weekPart"
            m.hrvAbove -> "↑ über deinem Normalband $band$prov$weekPart"
            else -> "→ in deinem Normalband $band$prov$weekPart"
        }

        val loadValue = when {
            lh.trainedToday && lh.hardToday -> "heute Qualitätsreiz"
            lh.trainedToday -> "heute trainiert"
            lh.forceRest -> "⚠ Ruhetag fällig"
            else -> "${lh.consecutiveDays} Trainingstag${if (lh.consecutiveDays == 1) "" else "e"} in Folge" +
                if (lh.hardYesterday) " · gestern hart" else ""
        }
        /* Einheiten konsistent halten: Dauer als h:mm, Zonenzeiten gebündelt mit EINER
           gemeinsamen Einheit. „Belastung" statt „Last" oder „TSS" — intervals.icu liefert
           icu_training_load, das bei Leistungsdaten dem TSS entspricht, bei reiner
           Herzfrequenz aber einem HRSS/TRIMP-Wert. „TSS" wäre also zu eng. */
        fun hm(sec: Double): String {
            val min = (sec / 60).roundToInt()
            return if (min >= 60) "${min / 60}:${(min % 60).toString().padStart(2, '0')} h" else "$min min"
        }
        fun zoneLine(z4: Int, z5: Int, z6: Int, torque: Int): String {
            val parts = mutableListOf("Z4/Z5+/Z6+ ${z4 / 60}/${z5 / 60}/${z6 / 60} min")
            if (torque > 0) parts += "Kraft ${torque / 60} min"
            return parts.joinToString(" · ")
        }
        val loadSub = when {
            lh.trainedToday && lh.today != null -> lh.today.let {
                "heute ${hm(it.zonedSec)} · ${zoneLine(it.z4, it.z5, it.z6, it.torque)} · Belastung ${it.load}" +
                    " · davor ${lh.consecutiveDays} Trainingstag${if (lh.consecutiveDays == 1) "" else "e"} in Folge"
            }
            lh.yesterday != null -> lh.yesterday.let {
                (if (it.hasZones)
                    "gestern ${hm(it.zonedSec)} · ${zoneLine(it.z4, it.z5, it.z6, it.torque)} · Belastung ${it.load}"
                else "gestern ${hm(it.zonedSec)} · keine Zonendaten · IF ${f(it.maxIf, 2)} · Belastung ${it.load}") +
                    " · Monotonie ${f(lh.monotony)}"
            }
            else -> "Monotonie ${f(lh.monotony)} (Foster)"
        }

        val tiles = listOf(
            Snapshot.Tile("Form · TSB", f(m.tsb),
                (m.tsbPct?.let { "${sgn(it)}${f(it, 0)} % · " } ?: "") + "CTL ${f(m.formCtl, 0)} · ATL ${f(m.formAtl, 0)}"),
            Snapshot.Tile("HRV (rMSSD)", m.hrv?.let { "${f(it, 0)} ms" } ?: "–", hrvSub),
            Snapshot.Tile("Ruhepuls", m.restingHr?.let { "${f(it, 0)} bpm" } ?: "–",
                m.restingHrDiff?.let { "${sgn(it)}${f(it)} bpm vs. Basis ${f(m.restingHrBase)}" +
                    (m.rhrZ?.let { z -> " · ${sgn(z)}${f(z)} SD" } ?: "") } ?: "keine Messung"),
            Snapshot.Tile("Schlaf", m.sleepScore?.let { "${it.roundToInt()}/100" } ?: (m.sleepHours?.let { "${f(it)} h" } ?: "–"),
                m.sleep7Effective?.let { "7-T-Mittel ${f(it)} h" + (m.sleepNeed?.let { n -> " · Bedarf ${f(n)} h" } ?: "") } ?: ""),
        ) + listOfNotNull(m.subjectiveIndex?.let { idx ->
            Snapshot.Tile("Befinden", "${(100 - idx * 100).roundToInt()}/100",
                m.subjectiveZ?.let { z -> "${sgn(-z)}${f(-z)} SD vs. üblich" } ?: "subjektiv, noch ohne Vergleich")
        }) + listOf(
            Snapshot.Tile("Belastungsverlauf", loadValue, loadSub),
            Snapshot.Tile("ACWR", f(m.acwr, 2), "EWMA 7/28 T., entkoppelt · grobe Orientierung · kein Risiko-Gate"),
        )

        return Snapshot(
            score = r.score, baseScore = r.baseScore, deduction = r.deduction,
            word = scoreWord(r.recommendation.verdict),
            colorHex = level.color,
            recoTitle = level.title, recoText = level.text,
            dataDate = m.dataDate ?: "", updatedAt = System.currentTimeMillis(),
            renormalized = r.components.any { it.sub == null },
            components = r.components.map {
                Snapshot.Component(componentKey(it.id), componentName(it.id),
                    ((if (it.effectiveWeight > 0) it.effectiveWeight else it.weight) * 100).roundToInt(),
                    it.sub, explanation(it, m, p), componentColor(it.id))
            },
            tiles = tiles,
            thresholds = Snapshot.Thresholds(r.thresholds.outdoorFtp, r.thresholds.indoorFtp,
                r.thresholds.eftp, r.thresholds.lthr, r.thresholds.maxHr, r.thresholds.staleMessage),
            limits = r.limitingFactors.map { limitLabel(it, m, p) },
            loadNote = lh.notes.joinToString("; ") { loadNoteText(it, lh) },
            hrvDate = m.hrvDate,
            confounders = cfg.confounders[m.hrvDate].orEmpty(),
            napMinutesToday = cfg.napMinutesByDay[m.hrvDate] ?: 0,
            scoreContext = scoreContext,
            progression = mapProgression(r.progression, cfg),
            chart = r.chart.map {
                Snapshot.ChartPoint(it.date, it.ctl, it.atl, it.tsb, it.hrv, it.load)
            },
        )
    }

    // ---------------------------------------------------------------- Komponenten

    private fun componentKey(id: ComponentId) = id.name.lowercase()

    private fun componentName(id: ComponentId) = when (id) {
        ComponentId.FORM -> "Form (TSB)"
        ComponentId.HRV -> "HRV vs. Baseline"
        ComponentId.SLEEP -> "Schlaf & Erholung"
        ComponentId.RHR -> "Ruhepuls-Trend"
        ComponentId.SUBJECTIVE -> "Befinden (subjektiv)"
    }

    private fun componentColor(id: ComponentId) = when (id) {
        ComponentId.FORM -> "#A78BFA"
        ComponentId.HRV -> "#4CC3FF"
        ComponentId.SLEEP -> "#3DDC97"
        ComponentId.RHR -> "#FFC53D"
        ComponentId.SUBJECTIVE -> "#F472B6"
    }

    private fun confLabel(m: Metrics) = m.confounderKeys.joinToString(", ") { Confounders.label(it) }

    /** Formbereich nach intervals.icu/Coggan. */
    private fun formWord(pct: Double, p: ScoringParams) = when {
        pct > p.form.veryHighAbovePct -> "Übergangsbereich, die Fitness sinkt"
        pct >= 5 -> "frisch"
        pct >= -10 -> "neutral"
        pct >= p.form.corroborateBelowPct -> "produktive Ermüdung"
        pct >= p.form.redBelowPct -> "hohe Ermüdung"
        else -> "tiefe Ermüdung"
    }

    /** Welche Kontextmarker die hohe HRV verdächtig machen. */
    private fun saturationContext(m: Metrics, p: ScoringParams): String {
        val parts = mutableListOf<String>()
        if (m.hrvCv7 != null && m.hrvCvRef != null && m.hrvCv7 > p.hrv.cvRatio * m.hrvCvRef) parts += "schwanken die Tageswerte ungewöhnlich stark"
        if (m.tsbPct != null && m.tsbPct < p.form.corroborateBelowPct) parts += "ist die akute Last hoch (Form ${i0(m.tsbPct)} %)"
        if (m.rhrElevated) parts += "ist der Ruhepuls erhöht"
        return parts.joinToString(" und ").ifEmpty { "liegen Belastungszeichen vor" }
    }

    private fun explanation(c: ScoreComponent, m: Metrics, p: ScoringParams): String {
        val age = c.ageDays
        val prefix = when {
            c.stale && age != null -> "Letzte Messung vor $age Tagen — zu alt, nicht bewertet. "
            age == 1 -> "Messung von gestern — zählt mit halbem Gewicht. "
            else -> ""
        }
        return prefix + componentBody(c, m, p)
    }

    private fun rhrLine(m: Metrics): String {
        val z = m.rhrZ?.let { " (${sgn(it)}${f(it)} SD)" } ?: ""
        return "${sgn(m.restingHrDiff)}${f(m.restingHrDiff)} bpm$z gegenüber deiner Basis von ${f(m.restingHrBase)} bpm"
    }

    private fun componentBody(c: ScoreComponent, m: Metrics, p: ScoringParams): String = when (c.id) {
        ComponentId.FORM -> {
            if (m.tsb == null || m.tsbPct == null) "Kein CTL/ATL verfügbar."
            else "Form ${sgn(m.tsbPct)}${i0(m.tsbPct)} % der Fitness (TSB ${f(m.tsb)}; CTL ${i0(m.formCtl)}, ATL ${i0(m.formAtl)}" +
                (if (m.formFromToday) ", heutiger Stand" else " zum Ende des Vortags") + ") — ${formWord(m.tsbPct, p)}."
        }
        ComponentId.HRV -> {
            val band = if (m.hrvBandLo != null && m.hrvBandHi != null)
                "Dein Normalband liegt derzeit bei ${i0(m.hrvBandLo)}–${i0(m.hrvBandHi)} ms" +
                    (if (m.hrvBandProvisional) " (vorläufig: erst ${m.hrvBandValues} Messtage Verlauf, " +
                        "es löst noch keine Empfehlung aus)" else "") + ". " else null
            val cv = if (m.hrvCv7 != null && m.hrvCvRef != null)
                " Schwankung der letzten 7 Tage: CV ${f(m.hrvCv7)} % (üblich ${f(m.hrvCvRef)} %)." else ""
            when {
                m.confInvalid -> "Messung vom ${m.hrvDate} als Artefakt markiert — nicht bewertet, Gewicht auf die übrigen Komponenten verteilt."
                m.hrv == null -> "Keine HRV-Messung in den letzten Tagen."
                band == null -> "rMSSD ${i0(m.hrv)} ms. Zu wenig Verlauf, um deine persönliche Normalbandbreite zu bestimmen."
                else -> "rMSSD ${i0(m.hrv)} ms (${sgn(m.hrvDeviationPct)}${f(m.hrvDeviationPct)} % vs. 7-T-Schnitt). " + band +
                    when {
                        (m.hrvSuppressed || m.hrvAcuteDrop) && m.confounded ->
                            "Darunter — Ursache extern angegeben: ${confLabel(m)}, kein Trainingsstress-Signal."
                        m.hrvSuppressed -> "Das 7-Tage-Mittel liegt darunter — Erholungssignal, heute keine harte Intensität."
                        m.hrvAcuteDrop -> "Heute deutlich darunter (${f(m.hrvZ?.let { -it })} SD), das 7-Tage-Mittel ist aber " +
                            "stabil — ein Einzelwert, noch kein Trend. Er zählt als Warnsignal, wenn Ruhepuls oder Trend mitziehen."
                        m.hrvSaturation -> "Das 7-Tage-Mittel liegt deutlich darüber, zugleich " + saturationContext(m, p) +
                            ". Hohe HRV ist normalerweise gute Erholung — in dieser Kombination kann sie aber auf funktionelle " +
                            "Überlastung hindeuten (parasympathische Sättigung). Punktzahl gedeckelt, heute keine harte Intensität."
                        m.hrvUnusual -> "Heute ungewöhnlich weit darüber. Einzelwerte in dieser Höhe entstehen häufig durch " +
                            "Messartefakte — die Punktzahl ist deshalb leicht gedeckelt. Falls die Messung unsauber war, markiere den Tag als Störfaktor."
                        m.hrvAbove -> "Heute darüber — gute Erholung, volle Punktzahl."
                        else -> "Heute mittendrin, also normale Erholungslage."
                    } + cv
            }
        }
        ComponentId.SLEEP -> {
            val nap = if (m.sleepNapHours > 0) " inkl. ${f(m.sleepNapHours)} h Nap" else ""
            val score = m.sleepScore?.let { sc ->
                "Garmin-Schlafscore ${sc.roundToInt()}/100" +
                    (m.sleepScoreBase?.let { b -> " (dein Mittel ${b.roundToInt()})" } ?: "")
            }
            val hours = m.sleepHours?.let { "${f(it)} h geschlafen$nap" }
            (listOfNotNull(score, hours).joinToString(", ").ifEmpty { "Keine Schlafdaten übertragen" } + ".") +
                (m.sleep7Effective?.let {
                    " 7-Tage-Mittel ${f(it)} h (inkl. eingetragener Powernaps)" +
                        (m.sleepNeed?.let { n -> " gegenüber deinem Bedarf ${f(n)} h" + when {
                            m.sleepNeedManual -> " (selbst gesetzt)."
                            m.sleepNeedFloored -> " (dein üblicher Schlaf liegt darunter; als Bedarf gilt mindestens " +
                                "${hours(p.sleep.needFloorH)} h — gewohnter Schlafmangel ist kein Bedarf)."
                            else -> " (aus deiner Historie)."
                        } } ?: ".")
                } ?: "")
        }
        ComponentId.SUBJECTIVE -> m.subjective?.let { e ->
            val mx = m.subjectiveScaleMax.toInt()
            fun item(label: String, v: Double?) = v?.let { "$label ${it.roundToInt()}/$mx" }
            val items = listOfNotNull(item("Erschöpfung", e.fatigue), item("Muskelkater", e.soreness),
                item("Stress", e.stress), item("Stimmung", e.mood), item("Motivation", e.motivation),
                item("Verletzung", e.injury)).joinToString(", ")
            val rel = m.subjectiveZ?.let { z ->
                when {
                    z >= p.subjective.elevatedZ -> " Deutlich schlechter als üblich."
                    z <= -p.subjective.elevatedZ -> " Besser als üblich."
                    else -> " Im üblichen Rahmen."
                }
            } ?: " Noch zu wenig Verlauf für einen Vergleich mit deinem Üblichen — absolut bewertet."
            "$items (1 = bestens).$rel"
        } ?: "Keine subjektiven Angaben — in intervals.icu unter Wellness eintragbar (Erschöpfung, Muskelkater, Stress, Stimmung, Motivation)."
        ComponentId.RHR ->
            if (m.restingHrDiff == null) "Kein Ruhepuls verfügbar."
            else "RHR ${i0(m.restingHr)} bpm, ${rhrLine(m)}" +
                (if (m.rhrZ == null) " (noch zu wenig Verlauf für deine persönliche Streuung)." else ".")
    }

    // ---------------------------------------------------------------- Limitierende Faktoren

    private fun evidenceSuffix(e: Evidence, m: Metrics) = when (e) {
        Evidence.NOT_APPLICABLE -> ""
        Evidence.EXTERNAL -> " — extern verursacht (${confLabel(m)})"
        Evidence.UNCONFIRMED -> " — Einzelbefund, von den übrigen Markern nicht bestätigt"
        Evidence.CONFIRMED -> " — durch weitere Marker bestätigt"
    }

    private fun limitLabel(l: LimitingFactor, m: Metrics, p: ScoringParams): String {
        val name = l.component?.let { componentName(it) } ?: ""
        val suffix = evidenceSuffix(l.evidence, m)
        return when (l.code) {
            LimitCode.HRV_STRONG ->
                "$name ${if (l.capped) "deutlich reduziert" else "stark unterdrückt"} (${f(l.value)} SD unter deiner Basis)$suffix"
            LimitCode.HRV_BELOW -> "$name deutlich unter deiner Basis (${f(l.value)} SD)"
            LimitCode.COMPONENT_CRITICAL ->
                "$name ${if (l.capped) "deutlich reduziert" else "kritisch niedrig"} (${l.value.roundToInt()}/100)$suffix"
            LimitCode.COMPONENT_REDUCED -> "$name deutlich reduziert (${l.value.roundToInt()}/100)$suffix"
            LimitCode.SLEEP_DEFICIT -> {
                val corr = l.corroborators.map {
                    when (it) {
                        Corroborator.HRV_BELOW_SWC -> "HRV unter SWC"
                        Corroborator.HRV_ALARM -> "HRV-Einbruch"
                        Corroborator.RHR_ELEVATED -> "Ruhepuls ${sgn(m.restingHrDiff)}${f(m.restingHrDiff)} bpm"
                    }
                }
                "Schlaf ${f(l.value)} h unter deinem Bedarf (${f(m.sleep7Effective)} h im Wochenschnitt vs. ${f(m.sleepNeed)} h)" +
                    " — bestätigt durch ${corr.joinToString(", ")}"
            }
            LimitCode.SLEEP_FLOOR -> "Wochenschnitt unter ${hours(if (l.severity == Severity.RED) p.sleep.floorRedH else p.sleep.floorH)} h Schlaf (${f(l.value)} h)"
            LimitCode.SUBJECTIVE_WORST -> {
                val corr = l.corroborators.map {
                    when (it) {
                        Corroborator.HRV_ALARM, Corroborator.HRV_BELOW_SWC -> "HRV-Einbruch"
                        Corroborator.RHR_ELEVATED -> "erhöhter Ruhepuls"
                    }
                }
                "Erschöpfung oder Muskelkater auf der höchsten Stufe angegeben" +
                    if (corr.isEmpty()) " — heute keine harte Intensität" else " — bestätigt durch ${corr.joinToString(", ")}"
            }
        }
    }

    // ---------------------------------------------------------------- Belastung

    private fun hardLabel(k: HardKind) = when (k) {
        HardKind.Z5_PLUS -> "Z5+"; HardKind.Z6_PLUS -> "Z6+"; HardKind.Z4 -> "Z4"
        HardKind.TORQUE -> "Kraftausdauer"; HardKind.IF_NO_ZONES -> "IF"
    }

    private fun hardReasonText(h: HardReason): String {
        val min = (h.seconds / 60.0).roundToInt()
        return when {
            h.kind == HardKind.IF_NO_ZONES -> "IF ${f(h.intensity, 2)} ohne Zonendaten"
            h.kind == HardKind.TORQUE -> "$min min Kraftausdauer (≤${Streams.LC_RPM} rpm über 85 % FTP)"
            h.absoluteMin != null -> "$min min ${hardLabel(h.kind)} (absolut ≥ ${h.absoluteMin} min)"
            else -> "$min min ${hardLabel(h.kind)} = ${h.sharePct} % der Fahrzeit"
        }
    }

    private fun loadNoteText(c: LoadNoteCode, lh: LoadHistory) = when (c) {
        LoadNoteCode.STREAK_LONG -> "${lh.consecutiveDays} Trainingstage ohne Pause"
        LoadNoteCode.STREAK_5 -> "5 Trainingstage in Folge"
        LoadNoteCode.STREAK_4_MONOTONY -> "4 Trainingstage bei hoher Monotonie"
        LoadNoteCode.HARD_YESTERDAY -> "gestern intensiv (${lh.hardReasons.joinToString(", ") { hardReasonText(it) }})"
        LoadNoteCode.HIGH_MONOTONY -> "hohe Monotonie ${f(lh.monotony)} bei hoher Wochenlast"
    }

    // ---------------------------------------------------------------- Empfehlung

    private data class Level(val color: String, val title: String, val text: String)

    fun scoreWord(verdict: Verdict): String = when (verdict) {
        Verdict.DONE -> "heute früh"
        Verdict.GREEN -> "Bereit"
        Verdict.AMBER -> "Angeschlagen"
        Verdict.RED -> "Erholung nötig"
        Verdict.UNKNOWN -> ""
    }

    /** Titel je Ampel — stabil, darauf verlassen sich Widget und Tests. */
    fun verdictTitle(v: Verdict, hardToday: Boolean = false) = when (v) {
        Verdict.GREEN -> "Grünes Licht für Intensität"
        Verdict.AMBER -> "Nur Grundlage / Z2"
        Verdict.RED -> "Ruhetag empfohlen"
        Verdict.DONE -> if (hardToday) "Qualitätseinheit erledigt" else "Einheit erledigt"
        Verdict.UNKNOWN -> "Zu wenig Daten"
    }

    private fun verdictColor(v: Verdict) = when (v) {
        Verdict.GREEN -> "#3DDC97"; Verdict.AMBER -> "#FFC53D"; Verdict.RED -> "#FF5C5C"
        Verdict.DONE -> "#6EA8FF"; Verdict.UNKNOWN -> "#8A97A8"
    }

    private fun verdictBody(v: Verdict) = when (v) {
        Verdict.GREEN -> "Erholung, Form und Belastungsmuster passen. Schwellenintervalle, VO2max oder ein langer Grundlagenblock sind heute gut platziert."
        Verdict.AMBER -> "Lockere Grundlagenfahrt in Z1–Z2, keine Intervalle, kein Krafttraining an der Grenze."
        Verdict.RED -> "Heute komplett pausieren oder maximal lockeres Ausrollen (< 45 min, Z1)."
        Verdict.UNKNOWN -> "Es liegen nicht genug Messwerte vor, um eine Empfehlung abzuleiten."
        Verdict.DONE -> ""
    }

    private fun noteText(n: Note, r: ReadinessResult, p: ScoringParams): String {
        val m = r.metrics; val lh = r.loadHistory
        return when (n.code) {
            NoteCode.FORM_DEEP_FATIGUE -> "Form ${i0(m.tsbPct)} % der Fitness, unter −${i0(abs(p.form.redBelowPct))} % (tiefe Ermüdung)"
            NoteCode.LOAD_FORCE_REST -> "Belastungsmuster + Erholungslage sprechen für Pause (${lh.notes.joinToString(", ") { loadNoteText(it, lh) }})"
            NoteCode.REST_DAY_DUE -> "${lh.consecutiveDays} Trainingstage in Folge — ohne weitere Warnzeichen kein Pflicht-Ruhetag, " +
                "aber heute Grundlage und in den nächsten Tagen einen Ruhetag einplanen"
            NoteCode.QUALITY_STREAK_END -> "dritter Qualitätstag in Folge — hier endet auch in der Blockmethodik der " +
                "Verdichtungsblock; jetzt Entlastung, sonst kippt der Reiz in unproduktive Ermüdung"
            NoteCode.BIG_DAY_YESTERDAY -> "gestern sehr großer Umfangstag — heute Grundlage statt Intensität"
            NoteCode.HARD_YESTERDAY_NOT_RECOVERED -> "gestern harter Reiz und die Erholungsmarker sind nicht unauffällig — " +
                "heute Grundlage; ein zweiter Qualitätstag wäre erst bei klarer Erholungslage sinnvoll"
            NoteCode.SECOND_QUALITY_DAY -> {
                val suggestion = when (lh.yesterdayType) {
                    StimulusType.VO2MAX -> "Gestern lag der Reiz bei VO2max. Heute ist ein zweiter Qualitätstag " +
                        "vertretbar, sinnvollerweise mit anderem Schwerpunkt — Schwelle oder Tempo statt erneut " +
                        "kurzer Maximalintervalle."
                    StimulusType.THRESHOLD -> "Gestern lag der Reiz an der Schwelle. Heute wären kurze VO2max-Intervalle " +
                        "die sinnvollere Ergänzung als ein zweiter Schwellenblock."
                    StimulusType.VOLUME -> "Gestern war vor allem Umfang. Ein Intensitätsreiz ist heute vertretbar."
                    else -> "Ein zweiter Qualitätstag ist heute vertretbar."
                }
                "$suggestion Danach ist ein Entlastungstag fällig (${lh.qualityStreak + 1}. Qualitätstag in Folge)"
            }
            NoteCode.HRV_BELOW_BAND -> "HRV-7-Tage-Mittel unter der individuellen Normalbandbreite"
            NoteCode.HRV_SATURATION -> "HRV-7-Tage-Mittel auffällig hoch, zugleich ${saturationContext(m, p)} — mögliche " +
                "funktionelle Überlastung (parasympathische Sättigung); Intensität erst wieder, wenn sich das normalisiert"
            NoteCode.HRV_WEEK_ALARM -> "HRV-Wochenmittel ${f(m.hrvWeekDevPct)} % unter den vier Wochen davor — gradueller Abfall des " +
                "7-Tage-Mittels ist der Frühindikator für Überlastung; Belastung in den nächsten Tagen eher zurücknehmen"
            NoteCode.LIMITING_FACTOR -> "limitierender Faktor: ${n.limit?.let { limitLabel(it, m, p) } ?: ""}"
            NoteCode.INSUFFICIENT_PHYSIOLOGY -> "heute kein aktueller HRV- oder Ruhepulswert mit belastbarer Basis — " +
                "ohne autonomen Messwert gibt es kein grünes Licht für Intensität"
            NoteCode.FORM_VERY_HIGH -> "Form über +${i0(p.form.veryHighAbovePct)} % — frisch, aber die Fitness sinkt bei weiterer Pause"
            NoteCode.ACWR_HIGH -> "ACWR ${f(m.acwr, 2)} (grobe Orientierung, kein Risiko-Gate)"
            NoteCode.ILLNESS -> "Krankheit/Infekt angegeben — Training pausieren, bis du symptomfrei bist, und danach schrittweise wieder aufbauen"
            NoteCode.HRV_ARTIFACT -> "HRV-Messung als Artefakt markiert — sie fließt heute nicht in den Score ein, die übrigen Komponenten tragen ihn"
            NoteCode.EXTERNAL_CAUSE -> "HRV-Absenkung extern verursacht (${confLabel(m)}) — kein Signal für Trainingsüberlastung; " +
                "Wert aus Baseline und SWC ausgeschlossen. Heute reduzierte Kapazität, den Trainingsplan aber nicht umbauen"
        }
    }

    private fun level(v: Verdict, r: ReadinessResult, p: ScoringParams): Level {
        val reco = r.recommendation
        val notes = reco.notes.map { noteText(it, r, p) }
        if (v != Verdict.DONE) return Level(verdictColor(v), verdictTitle(v),
            verdictBody(v) + if (notes.isNotEmpty()) " Hinweis: " + notes.joinToString("; ") + "." else "")

        /* Ist heute bereits trainiert worden, wechselt die Fragestellung: der Score wurde
           aus den Werten der Nacht gebildet und beschreibt die Bereitschaft VOR der
           Einheit. Eine Empfehlung „grünes Licht für Intensität" wäre danach sinnlos. */
        val lh = r.loadHistory
        val parts = mutableListOf<String>()
        val mins = lh.today?.let { (it.zonedSec / 60).roundToInt() }
        parts += if (lh.hardToday)
            "Heute wurde bereits ein Qualitätsreiz gesetzt" +
                (if (lh.todayReasons.isNotEmpty()) " (${lh.todayReasons.joinToString(", ") { hardReasonText(it) }})" else "") +
                (if (mins != null) ", $mins min" else "") + "."
        else "Heute wurde bereits trainiert" + (if (mins != null) ": $mins min" else "") +
                (lh.today?.let { ", Last ${it.load}" } ?: "") + "."
        parts += "Der Score ${r.score} stammt aus den Nachtwerten und beschreibt deine Bereitschaft von heute früh, also vor dieser Einheit."
        /* Keine starre 48-h-Regel mehr: ob ein zweiter Qualitätstag passt, entscheiden die
           Morgenwerte — begrenzt nur durch die Blockgrenze. */
        parts += if (lh.hardToday) {
            if (lh.qualityStreak >= p.verdict.qualityStreakMax)
                "Das war der ${lh.qualityStreak}. Qualitätstag in Folge — morgen ist Entlastung fällig, unabhängig von den Morgenwerten."
            else "Ob morgen ein weiterer Qualitätstag passt, entscheiden die Morgenwerte; spätestens nach dem " +
                "${p.verdict.qualityStreakMax}. Qualitätstag in Folge ist Entlastung fällig."
        } else "Ein zusätzlicher harter Reiz heute wäre nur sinnvoll, wenn die Einheit wirklich locker war und die Erholungslage passt."
        if (reco.morning == Verdict.RED) parts += "Beachte: Die Erholungsmarker sprachen heute früh gegen eine Belastung — beobachte die Reaktion morgen besonders genau."
        else if (reco.morning == Verdict.AMBER && lh.hardToday) parts += "Beachte: Die Morgenlage sprach für Grundlage statt Intensität — plane morgen entsprechend konservativ."
        if (notes.isNotEmpty()) parts += "Aus der Morgenlage: " + notes.joinToString("; ") + "."
        return Level(verdictColor(v), verdictTitle(v, lh.hardToday), parts.joinToString(" "))
    }

    // ---------------------------------------------------------------- Formaufbau

    fun markerName(k: Marker) = when (k) {
        Marker.EFTP -> "eFTP"
        Marker.AEROBIC_EF -> "aerobe Effizienz"
        Marker.DECOUPLING -> "Entkopplung"
        Marker.D60 -> "Kraft kurz"
        Marker.D300 -> "Kraft mittel"
        Marker.D600 -> "Kraft lang"
        Marker.LC_EFFICIENCY -> "Kraft-Effizienz"
    }

    private fun durationNote(k: DurationKey) = when (k) {
        DurationKey.D60 -> "1 min — neuromuskulär"
        DurationKey.D300 -> "5 min — Kraftausdauer"
        DurationKey.D600 -> "10 min — muskuläre Ausdauer"
    }

    private data class PLevel(val color: String, val title: String, val text: String)

    private fun progressionLevel(p: Progression): PLevel = when (p.verdict) {
        ProgressionVerdict.INSUFFICIENT -> {
            val miss = mutableListOf<String>()
            if (p.dose == null) miss += "CTL-Vergleich fehlt (heute ${i0(p.ctlNow)}, vor ${p.windowDays} Tagen ${i0(p.ctlPrev)})"
            if (Marker.EFTP !in p.markersUsed) miss += if (p.eftpSuppressedNoAttempt)
                "eFTP-Rückgang nicht bewertet — kein maximaler Antritt im aktuellen Fenster"
            else "eFTP-Werte: ${p.diag.eftpValues} in beiden Fenstern — intervals.icu setzt sie nur bei maximalen Antritten"
            if (p.efDeltaPct == null) miss += "aerobe Vergleichseinheiten: ${p.efNPrev} im älteren / ${p.efN} im aktuellen Fenster, mindestens 2 je Zeitraum nötig"
            PLevel("#8A97A8", "Noch zu wenig vergleichbare Daten", miss.joinToString(". ") + ".")
        }
        ProgressionVerdict.FOCUSED -> PLevel("#3DDC97", "Gezielte Verbesserung",
            "Einzelne Antwortmarker steigen bei gleicher oder höherer Last, die übrigen halten — genau das Bild eines gezielten Schwerpunkts. Solange nichts nachgibt, ist das produktiv.")
        ProgressionVerdict.PRODUCTIVE -> PLevel("#3DDC97", "Produktive Progression",
            "Die Antwortmarker steigen bei gleicher oder höherer Last — Volumen und Intensität passen zum Formaufbau. Kurs halten.")
        ProgressionVerdict.STIMULUS_PENDING -> PLevel("#FFC53D", "Reiz kommt an, Antwort steht noch aus",
            "Last steigt, Leistung und Effizienz stagnieren. Das ist im Aufbau normal und noch kein Warnzeichen: messbare aerobe " +
                "Anpassungen brauchen typischerweise sechs bis acht Wochen, bei bereits gut Trainierten eher länger. Erst wenn auch der " +
                "nächste Vergleich nichts zeigt, fehlt Erholung oder der Reiz ist zu monoton.")
        ProgressionVerdict.LOAD_UP_RESPONSE_DOWN -> PLevel("#FF5C5C", "Last steigt, Antwort fällt",
            "Klassisches Muster unzureichender Erholung: mehr Dosis, weniger Wirkung. Eine Entlastungswoche ist hier meist produktiver als weiteres Draufsatteln.")
        ProgressionVerdict.DELOAD_WORKS -> PLevel("#3DDC97", "Entlastung wirkt",
            "Last gesunken, Leistung gehalten oder verbessert — genau das erwartete Bild in Deload oder Taper.")
        ProgressionVerdict.DETRAINING -> PLevel("#FFC53D", "Detraining-Tendenz",
            "Last und Antwort fallen gemeinsam. Wenn das keine geplante Pause ist, fehlt Reiz.")
        ProgressionVerdict.PLATEAU -> PLevel("#FFC53D", "Plateau",
            "Weder Last noch einer der Antwortmarker bewegt sich über das Rauschband hinaus. Für einen bereits gut Trainierten sind " +
                "sechs Wochen ohne messbare Änderung nicht ungewöhnlich — substanzielle Sprünge liegen eher bei acht bis zwölf Wochen.")
    }

    /** Titel des Formaufbau-Verdikts. */
    fun progressionTitle(p: Progression) = progressionLevel(p).title

    private fun progressionText(p: Progression): String {
        var text = progressionLevel(p).text
        text += when (p.responseBasis) {
            ResponseBasis.STRONG_SINGLE -> " Getragen von einem einzelnen Marker, der weit über seinem Rauschband liegt."
            ResponseBasis.BALANCE -> " Die Marker laufen gegeneinander — entschieden nach der Bilanz in Rauschband-Einheiten."
            ResponseBasis.WEAK_SINGLE -> " Nur ${p.weakMarker?.let { markerName(it) } ?: "ein Marker"} liegt knapp über seinem " +
                "Rauschband — ein einzelner Marker ist bei sieben gleichzeitig beobachteten noch kein Beleg."
            else -> ""
        }
        if (p.drivers.isNotEmpty()) text += " Verbessert: ${p.drivers.joinToString(", ") { markerName(it) }}."
        if (p.decliners.isNotEmpty()) text += " Rückläufig: ${p.decliners.joinToString(", ") { markerName(it) }}."
        if (p.markersUsed.isNotEmpty()) text += " Ausgewertete Marker: ${p.markersUsed.joinToString(", ") { markerName(it) }}."
        /* Ohne diesen Satz sähe das Verschwinden eines Markers wie ein Fehler aus. */
        if (p.noAttemptMarkers.isNotEmpty()) text += " Nicht bewertet, weil im aktuellen Zeitraum kein " +
            "Maximalversuch dieser Dauer gefahren wurde: ${p.noAttemptMarkers.joinToString(", ") { markerName(it) }} — " +
            "ein niedrigerer Bestwert aus längeren Intervallen ist kein Leistungsverlust."
        if (p.deloadNow) text += " Aktuelle Woche ist eine Entlastungswoche (< 70 % der letzten vier Wochen) — im Trend berücksichtigt."
        if (p.hrvChronWarning)
            text += " Zusätzliche Warnung: HRV-Chronik ${f(p.hrvChronDeltaPct)} % bei steigender Last — Zeichen für Maladaptation."
        return text
    }

    private fun deltaKind(v: Double?, goodUp: Boolean, dead: Double): String = when {
        v == null -> "none"
        (if (goodUp) v > dead else v < -dead) -> "good"
        (if (goodUp) v < -dead else v > dead) -> "bad"
        else -> "flat"
    }

    private fun deltaText(v: Double?, unit: String, thin: Boolean = false) =
        v?.let { "${sgn(it)}${f(it)}$unit" + if (thin) "°" else "" }

    private fun mapProgression(p: Progression, cfg: AnalysisConfig): Snapshot.Progression {
        val level = progressionLevel(p)
        if (!p.ok) return Snapshot.Progression(false, p.windowDays, cfg.cycles, level.title, progressionText(p), level.color)

        val rows = mutableListOf<Snapshot.Row>()

        val eftpReason = when {
            p.eftpNow == null && p.eftpPrev == null -> "keine eFTP-Werte — setzt intervals.icu nur bei maximalen Antritten"
            p.eftpPrev == null -> "kein eFTP im älteren Zeitraum"
            p.eftpNow == null -> "kein eFTP im aktuellen Zeitraum"
            p.eftpSuppressedNoAttempt -> "nicht getestet — im aktuellen Zeitraum kein maximaler Antritt. " +
                "intervals.icu hebt eFTP nur dabei an und lässt ihn sonst zerfallen, der niedrigere Wert ist also kein Leistungsverlust"
            else -> null
        }
        rows += Snapshot.Row("Leistung", "bestes eFTP je ${p.windowDays} Tage",
            if (p.eftpPrev != null) "${p.eftpPrev} → ${p.eftpNow} W" else (p.eftpNow?.let { "$it W" } ?: ""),
            deltaText(p.eftpDeltaPct, " %"), deltaKind(p.eftpDeltaPct, true, p.eftpBand), eftpReason)

        rows += Snapshot.Row("Aerobe Effizienz",
            "NP/HF, aerobe Einheiten (n=${p.efNPrev}→${p.efN})" +
                (if (p.efIntensityAdjusted) ", intensitätsbereinigt" else "") + when (p.efEnvironment) {
                EfEnvironment.MIXED_ADJUSTED -> ", Rolle/Straße bereinigt"
                EfEnvironment.INDOOR -> ", nur Rolle"
                EfEnvironment.MIXED_RAW -> ", Rolle/Straße gemischt und nicht bereinigbar — eingeschränkt vergleichbar"
                else -> ""
            } + ", Rauschband ±${f(p.efBand)} %",
            if (p.efDeltaPct != null) "${f(p.efPrev, 2)} → ${f(p.efNow, 2)}" else "",
            deltaText(p.efDeltaPct, " %"), deltaKind(p.efDeltaPct, true, p.efBand),
            when {
                p.efDeltaPct == null && p.efNPrev < 2 -> "zu wenige aerobe Einheiten im älteren Zeitraum"
                p.efDeltaPct == null -> "zu wenige aerobe Einheiten im aktuellen Zeitraum"
                !p.efIntensityAdjusted -> "Rohvergleich — zu wenige Einheiten, um die Intensität herauszurechnen"
                else -> null
            })

        rows += Snapshot.Row("Belastung", "CTL, Rampe ${f(p.rampPerWeek)}/Woche",
            if (p.ctlPrev != null) "${f(p.ctlPrev, 0)} → ${f(p.ctlNow, 0)}" else "",
            deltaText(p.ctlDeltaPct, " %"), deltaKind(p.ctlDeltaPct, true, 3.0),
            if (p.ctlDeltaPct == null) "kein CTL-Vergleichswert" else null)

        val openOld = p.torqueScan?.openOlder ?: 0
        /* Was macht den Marker wieder scharf? Ohne diesen Hinweis bleibt „nicht getestet"
           eine Sackgasse, statt eine Handlungsanweisung zu sein. */
        fun lcHowTo(key: DurationKey) = when (key) {
            DurationKey.D60 -> "3×1 min all-out bei ≤${Streams.LC_RPM} rpm"
            DurationKey.D300 -> "2×5 min maximal bei ≤${Streams.LC_RPM} rpm"
            DurationKey.D600 -> "1×10 min maximal bei ≤${Streams.LC_RPM} rpm"
        }
        fun lcReason(d: DurationProgress): String? = when {
            d.now == null && d.prev == null -> "keine Krafteinheit im Zeitraum"
            d.prev == null -> if (openOld > 0) "wird gerade geladen …" else "kein Vergleichswert im älteren Zeitraum"
            d.now == null -> "im aktuellen Zeitraum keine solche Einheit"
            d.suppressedNoAttempt ->
                "nicht getestet — im aktuellen Zeitraum kein Maximalversuch über diese Dauer. Der Bestwert stammt " +
                "aus längeren Intervallen und ist kein Leistungsverlust. Für einen belastbaren Vergleich: ${lcHowTo(d.key)}"
            d.attemptsPrev == 0 || d.attemptsNow == 0 -> {
                val w = if (d.attemptsPrev == 0) "älteren" else "aktuellen"
                if ((d.deltaPct ?: 0.0) > 0)
                    "kein Maximalversuch im $w Zeitraum — dass es aufwärts geht, ist damit belegt, die Höhe des Zuwachses nicht"
                else "kein Maximalversuch im $w Zeitraum — der Vergleich ist nur eingeschränkt belastbar"
            }
            else -> null
        }
        p.durations.forEach { d ->
            if (d.now == null && d.prev == null) return@forEach
            rows += Snapshot.Row(markerName(Marker.of(d.key)),
                "${durationNote(d.key)}, ≤${Streams.LC_RPM} rpm (Versuche ${d.attemptsPrev}→${d.attemptsNow})",
                if (d.prev != null) "${d.prev} → ${d.now} W" else (d.now?.let { "$it W" } ?: ""),
                deltaText(d.deltaPct, " %", d.thin), deltaKind(d.deltaPct, true, d.band), lcReason(d))
        }
        if (p.lcEfNow != null || p.lcEfPrev != null) {
            rows += Snapshot.Row("Kraft-Effizienz", "W/bpm bei ≤${Streams.LC_RPM} rpm, ab 5 min (n=${p.lcEfNPrev}→${p.lcEfN})",
                if (p.lcEfPrev != null) "${f(p.lcEfPrev, 2)} → ${f(p.lcEfNow, 2)}" else (p.lcEfNow?.let { f(it, 2) } ?: ""),
                deltaText(p.lcEfDeltaPct, " %", p.lcEfThin), deltaKind(p.lcEfDeltaPct, true, p.lcEfBand),
                if (p.lcEfDeltaPct == null) (if (openOld > 0) "wird gerade geladen …" else "kein Vergleichswert") else null)
        }
        if (p.decNow != null || p.decPrev != null) {
            rows += Snapshot.Row("Aerobe Entkopplung",
                "HF-Leistungs-Decoupling (Seiler), lange Einheiten (n=${p.decNPrev}→${p.decN}) — je niedriger, desto besser",
                if (p.decPrev != null) "${f(p.decPrev)} → ${f(p.decNow)} %" else "${f(p.decNow)} %",
                p.decDeltaPp?.let { "${sgn(it)}${f(it)} Pp" }, deltaKind(p.decDeltaPp, false, p.decBand),
                if (p.decDeltaPp == null) "zu wenige lange aerobe Einheiten" else null)
        }
        if (p.peakTorqueNow != null) {
            /* Dasselbe Maximum-Problem wie bei den Kraftdauern, nur ohne Urteilswirkung. */
            val tqUntested = p.durations.any { it.key != DurationKey.D60 && it.attemptsNow == 0 }
            rows += Snapshot.Row("Spitzendrehmoment", "bestes 30-s-Mittel — Orientierung, geht nicht ins Urteil ein",
                if (p.peakTorquePrev != null) "${f(p.peakTorquePrev, 0)} → ${f(p.peakTorqueNow, 0)} Nm" else "${f(p.peakTorqueNow, 0)} Nm",
                null, "none",
                if (tqUntested) "nur Orientierung — kein Maximalversuch im Zeitraum, ein Rückgang wäre hier keine Formaussage"
                else "nur Orientierung")
        }
        if (p.decliners.isNotEmpty() || p.hrvChronDeltaPct != null) {
            p.hrvChronDeltaPct?.let {
                rows += Snapshot.Row("HRV-Chronik", "28-Tage-Mittel Ln-rMSSD",
                    "${f(p.hrvChronPrev, 0)} → ${f(p.hrvChronNow, 0)} ms",
                    deltaText(it, " %"), deltaKind(it, true, 3.0), null)
            }
        }

        val chips = buildList {
            p.ctlDeltaPct?.let { add(Snapshot.Chip("Belastung · CTL", deltaText(it, " %")!!, deltaKind(it, true, 3.0))) }
            p.eftpDeltaPct?.let { add(Snapshot.Chip("Leistung · eFTP", deltaText(it, " %")!!, deltaKind(it, true, p.eftpBand))) }
            if (size < 3) p.efDeltaPct?.let { add(Snapshot.Chip("Effizienz · NP/HF", deltaText(it, " %")!!, deltaKind(it, true, p.efBand))) }
            val lc = p.durations.firstOrNull { !it.thin && it.deltaPct != null }
                ?: p.durations.firstOrNull { it.deltaPct != null }
            if (size < 3 && lc != null) add(Snapshot.Chip("Kraft", deltaText(lc.deltaPct, " %")!!, deltaKind(lc.deltaPct, true, lc.band)))
            if (size < 3) p.hrvChronDeltaPct?.let { add(Snapshot.Chip("HRV-Chronik", deltaText(it, " %")!!, deltaKind(it, true, 3.0))) }
        }.take(3)

        val distNote = p.share12?.let {
            when {
                it < 70 -> " — zu wenig niedrige Intensität, Gefahr von Grauzonentraining"
                it > 93 -> " — fast nur Grundlage, Schwellen-/VO2max-Reiz fehlt"
                (p.share4 ?: 0.0) < 4 -> " — sehr wenig Z4+, für Schwellenentwicklung knapp"
                else -> " — im Rahmen der Referenz"
            }
        } ?: ""

        val sepMin = p.windowDays / 2
        val weak = buildList {
            if (p.eftpSeparationDays != null && p.eftpSeparationDays < sepMin) add("eFTP (${p.eftpSeparationDays} Tage)")
            p.durations.forEach { d ->
                if (d.deltaPct != null && d.separationDays != null && d.separationDays < sepMin)
                    add("${markerName(Marker.of(d.key))} (${d.separationDays} Tage)")
            }
        }
        val diagText = if (p.verdict == ProgressionVerdict.INSUFFICIENT)
            "Radeinheiten geladen: ${p.diag.rides} · in den beiden Fenstern: ${p.diag.ridesInWindow} · " +
                "mit Leistung und Herzfrequenz: ${p.diag.withPowerHr} · davon ≥30 min: ${p.diag.longEnough} · davon aerob: ${p.diag.aerobic}"
        else null
        val hint = buildString {
            append(diagText ?: ("CTL ist die Dosis, nicht die Wirkung — deshalb werden Leistung, aerobe Effizienz und Kraft " +
                "getrennt als Antwort geführt. Kraft misst Watt bei konditionierter Trittfrequenz (≤${Streams.LC_RPM} rpm), " +
                "nicht rohes Drehmoment: Nm allein bildet vor allem die Gangwahl ab. Die HF-Kopplung (W/bpm) gilt erst ab " +
                "fünf Minuten — darunter misst man die Anlaufkurve der Herzfrequenz, nicht die Beanspruchung. " +
                "Alle Bestwert-Marker (Kraft, eFTP, Drehmoment) sind Maxima und damit auf Maximalversuche angewiesen: ein " +
                "Anstieg ist immer ein Beleg, ein Rückgang nur mit gefahrenem Versuch. Unabhängig davon trägt die " +
                "Kraft-Effizienz (W/bpm), weil ein zu langsam gefahrener Effort Watt UND Herzfrequenz senkt und das " +
                "Verhältnis stehen bleibt — auf sie ist auch dann Verlass, wenn die Bestwerte schweigen."))
            if (weak.isNotEmpty()) append(" Geringe zeitliche Trennung bei ${weak.joinToString(", ")} — " +
                "echte Veränderung wird dadurch eher unter- als überschätzt.")
            p.torqueScan?.let { s ->
                if (s.missing > 0) append(" Kraftdaten werden gerade geladen: ${s.total - s.missing} von ${s.total} " +
                    "Einheiten ausgewertet" + (if (s.openOlder > 0) ", ${s.openOlder} davon noch offen im älteren Zeitraum" else "") +
                    ". Die Karte aktualisiert sich von selbst; ausgewertete Einheiten werden dauerhaft gespeichert.")
            }
        }

        return Snapshot.Progression(
            ok = true, windowDays = p.windowDays, cycles = cfg.cycles,
            title = level.title, text = progressionText(p), colorHex = level.color,
            chips = chips, rows = rows,
            share12 = p.share12, share3 = p.share3, share4 = p.share4,
            zoneHours = p.zoneHours, distributionNote = distNote, hint = hint,
            anyThin = p.durations.any { it.thin && it.deltaPct != null } || (p.lcEfThin && p.lcEfDeltaPct != null),
        )
    }
}
