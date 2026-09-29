package com.readiness.app.domain

import java.time.LocalDate

/**
 * Setzt die Domänenschritte zusammen. Reine Funktion: keine Netzzugriffe, kein
 * Speicherzugriff, keine Konfigurationsquelle — alles kommt als Argument herein.
 *
 * Damit ist die gesamte wissenschaftliche Logik ohne Android-Umgebung testbar.
 */
object ReadinessEngine {

    fun evaluate(
        wellness: List<WellnessDay>,
        sessions: List<Session>,
        thresholds: Thresholds,
        cfg: AnalysisConfig,
        today: LocalDate = LocalDate.now(),
        torqueScan: TorqueScan? = null,
    ): ReadinessResult {
        val p = cfg.params
        val metrics = MetricsBuilder.build(wellness, sessions, cfg, today)
        val load = LoadHistoryAnalyzer.analyze(
            sessions, metrics.ctl, metrics.hrvRecoveryAlarm && !metrics.confounded, today, p, metrics.tsbPct)
        val base = ScoreEngine.buildScore(metrics, p)
        val limits = ScoreEngine.limitingFactors(base.components, metrics, p)
        val score = base.total?.let { maxOf(0, it - load.deduction) }
        val reco = ScoreEngine.buildRecommendation(score, metrics, load, limits, base.components, base.total, p)
        val prog = ProgressionAnalyzer.analyze(wellness, sessions, cfg, today, torqueScan)
        val chart = buildChart(wellness, sessions, today)

        return ReadinessResult(
            score = score, baseScore = base.total, deduction = load.deduction,
            metrics = metrics, components = base.components, limitingFactors = limits,
            loadHistory = load, recommendation = reco, thresholds = thresholds, progression = prog,
            chart = chart,
        )
    }

    /** Tagesreihe der letzten 30 Tage für die Verlaufsdiagramme. */
    private fun buildChart(wellness: List<WellnessDay>, sessions: List<Session>, today: LocalDate): List<ChartPoint> {
        val loadByDay = HashMap<String, Double>()
        sessions.forEach { loadByDay[it.localDate] = (loadByDay[it.localDate] ?: 0.0) + it.trainingLoad }
        val byDate = wellness.associateBy { it.date }
        return (29 downTo 0).map { back ->
            val d = today.minusDays(back.toLong()).toString()
            val w = byDate[d]
            // Form wie überall in der App: Stand zum Ende des Vortags, also mit dem man in den Tag geht
            val prev = byDate[today.minusDays(back + 1L).toString()]
            ChartPoint(
                date = d, ctl = w?.ctl, atl = w?.atl,
                tsb = if (prev?.ctl != null && prev.atl != null) prev.ctl - prev.atl else null,
                hrv = w?.hrv, load = loadByDay[d] ?: 0.0,
            )
        }
    }
}
