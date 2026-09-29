package com.readiness.app.repo

import android.content.Context
import com.readiness.app.data.IcuClient
import com.readiness.app.data.RawBundle
import com.readiness.app.data.RawStore
import com.readiness.app.data.ScoreHistoryStore
import com.readiness.app.data.SecurePrefs
import com.readiness.app.data.Snapshot
import com.readiness.app.data.SnapshotMapper
import com.readiness.app.data.SnapshotStore
import com.readiness.app.data.TorqueStore
import com.readiness.app.R
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Session
import com.readiness.app.domain.TorqueScan
import com.readiness.app.domain.ReadinessEngine
import com.readiness.app.domain.ReadinessResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate

/**
 * Orchestriert Beschaffung, Auswertung und Zwischenspeicherung.
 *
 * Leitgedanke der Zwischenspeicherung: Rohdaten werden EINMAL je Tag geholt, in voller
 * Tiefe, und danach nur noch neu ausgewertet. Ein Wechsel des Vergleichszeitraums oder
 * ein erneuter App-Start kommt damit ohne Netzabruf aus.
 *
 * NEBENLÄUFIGKEIT. App (Vordergrund-Refresh, Nachladen der Kraftdaten) und WorkManager
 * (Morgenlauf, Widget) greifen auf dieselben Dateien zu. Früher hatte jeder seine eigene
 * Instanz und schrieb per Lesen-Ändern-Schreiben: parallele Läufe überschrieben sich
 * gegenseitig den Kraft-Cache. Deshalb gibt es genau EINE Instanz je Prozess
 * ([RepoProvider]), und alle Läufe sind über einen Mutex serialisiert.
 */
class ReadinessRepository internal constructor(context: Context) {

    private val prefs = SecurePrefs(context)
    private val client = IcuClient { prefs.apiKey to prefs.athlete }
    private val icu = IcuRepository(client)
    private val torqueStore = TorqueStore(context)
    private val torque = TorqueRepository(client, torqueStore)
    private val snapshots = SnapshotStore(context)
    private val rawStore = RawStore(context)
    private val scoreHistory = ScoreHistoryStore(context)
    private val mutex = Mutex()

    /** Build-Typ „demo": synthetische Daten ([DemoData]), kein Netzabruf, kein API-Key nötig. */
    val isDemo: Boolean = context.resources.getBoolean(R.bool.demo_mode)

    @Volatile private var memRaw: RawBundle? = null

    companion object {
        /** Rohdaten gelten vier Stunden als aktuell genug. */
        const val MAX_AGE_MS = 4 * 60 * 60 * 1000L
    }

    val settings: SecurePrefs get() = prefs

    fun cached(): Snapshot? = snapshots.load()
    fun cacheSizeKb(): Long = rawStore.sizeKb()

    /** Rohdaten aus Arbeitsspeicher, Platte oder Netz — in dieser Reihenfolge. */
    private fun raw(cfg: AnalysisConfig, today: LocalDate, forceNetwork: Boolean): RawBundle {
        if (isDemo) return memRaw?.takeIf { it.day == today.toString() }
            ?: DemoData.bundle(today, cfg.fetchDays).also { memRaw = it }
        if (!forceNetwork) {
            val candidate = memRaw ?: rawStore.load()?.also { memRaw = it }
            /* Wiederverwenden nur, wenn die Daten vom selben Tag stammen, tief genug
               reichen UND nicht zu alt sind. Ohne Altersgrenze würde die App den ganzen
               Tag auf dem Morgenstand hängen bleiben, obwohl Garmin und Karoo laufend
               nachliefern. */
            val ageOk = candidate != null && System.currentTimeMillis() - candidate.savedAt < MAX_AGE_MS
            if (candidate != null && ageOk && candidate.day == today.toString() &&
                candidate.fetchDays >= cfg.fetchDays) return candidate
        }
        val fresh = icu.fetchRaw(cfg, today)
        memRaw = fresh
        rawStore.save(fresh)
        return fresh
    }

    private fun evaluate(bundle: RawBundle, cfg: AnalysisConfig, today: LocalDate,
                         streamBudget: Int): Pair<Snapshot, ReadinessResult> {
        val mapped = icu.map(bundle, today)
        val enriched = if (isDemo) {
            val tq = DemoData.torque(bundle.activities, today)
            TorqueRepository.Result(mapped.sessions.map { s -> tq[s.id]?.let { s.copy(torque = it) } ?: s },
                TorqueScan(tq.size, 0, 0))
        } else {
            val withWork = torque.detectRecentTorqueWork(mapped.sessions, mapped.thresholds, today, streamBudget > 0)
            torque.enrich(withWork, mapped.thresholds, cfg, today, streamBudget)
        }
        if (isDemo) backfillDemoHistory(mapped, enriched.sessions, cfg, today)
        val result = ReadinessEngine.evaluate(
            mapped.wellness, enriched.sessions, mapped.thresholds, cfg, today, enriched.scan)

        /* Erholungsbasis festhalten (nur den Morgenwert) und gegen die eigene Verteilung
           einordnen. Der Basiswert statt des angezeigten Scores: der Belastungsabzug
           beschreibt den Trainingsplan, nicht den Erholungszustand. */
        val dateKey = result.metrics.dataDate ?: today.toString()
        scoreHistory.record(dateKey, result.baseScore, today.minusDays(180).toString(),
            overwrite = !result.loadHistory.trainedToday)
        val ctx = scoreHistory.classify(result.baseScore, dateKey)?.let {
            "Erholungslage: ${it.label} · Perzentil ${it.percentile} deiner letzten ${it.days} Messtage (Basis-Median ${it.median})"
        }
        val snap = SnapshotMapper.map(result, cfg, ctx)
        snapshots.save(snap)
        return snap to result
    }

    /** Demo: Score-Verlauf der letzten Wochen einmalig nachrechnen, damit die Einordnung sichtbar ist. */
    private fun backfillDemoHistory(mapped: IcuMapper.RawData, sessions: List<Session>, cfg: AnalysisConfig, today: LocalDate) {
        if (scoreHistory.load().size >= 14) return
        for (back in 40 downTo 1) {
            val day = today.minusDays(back.toLong()); val iso = day.toString()
            val r = ReadinessEngine.evaluate(mapped.wellness.filter { it.date <= iso },
                sessions.filter { it.localDate < iso }, mapped.thresholds, cfg, day)
            scoreHistory.record(iso, r.baseScore, today.minusDays(180).toString(), overwrite = true)
        }
    }

    /** Vollständiger Durchlauf mit Netzabruf. Muss außerhalb des Hauptthreads laufen. */
    suspend fun refresh(streamBudget: Int? = null, today: LocalDate = LocalDate.now(),
                        forceNetwork: Boolean = true): Pair<Snapshot, ReadinessResult> = mutex.withLock {
        val cfg = prefs.config()
        evaluate(raw(cfg, today, forceNetwork), cfg, today, streamBudget ?: cfg.streamBudget)
    }

    /** Nur neu auswerten — ohne Netz, für den Wechsel des Vergleichszeitraums. */
    suspend fun reevaluate(today: LocalDate = LocalDate.now()): Pair<Snapshot, ReadinessResult> = mutex.withLock {
        val cfg = prefs.config()
        evaluate(raw(cfg, today, forceNetwork = false), cfg, today, streamBudget = 0)
    }

    /**
     * Schlanker Lauf für das Widget: frische Rohdaten, neu bewerten, KEINE Stream-Abrufe.
     * Damit bleibt der Hintergrundaufwand auf wenige HTTP-Anfragen begrenzt.
     */
    suspend fun refreshLight(today: LocalDate = LocalDate.now()): Snapshot = mutex.withLock {
        val cfg = prefs.config()
        evaluate(raw(cfg, today, forceNetwork = true), cfg, today, streamBudget = 0).first
    }

    /** Kraftdaten weiter auffüllen und neu bewerten — für den Hintergrundlauf. */
    suspend fun fillTorqueStep(today: LocalDate = LocalDate.now()): Pair<Snapshot, Boolean> = mutex.withLock {
        val cfg = prefs.config()
        val bundle = raw(cfg, today, forceNetwork = false)
        val (snap, result) = evaluate(bundle, cfg, today, streamBudget = 6)
        snap to ((result.progression.torqueScan?.missing ?: 0) > 0)
    }
}

/** Genau eine Repository-Instanz je Prozess — geteilt von App, Workern und Widget. */
object RepoProvider {
    @Volatile private var instance: ReadinessRepository? = null

    fun get(context: Context): ReadinessRepository =
        instance ?: synchronized(this) {
            instance ?: ReadinessRepository(context.applicationContext).also { instance = it }
        }
}
