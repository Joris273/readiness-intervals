package com.readiness.app.repo

import com.readiness.app.data.AppLog
import com.readiness.app.data.IcuClient
import com.readiness.app.data.TorqueEntry
import com.readiness.app.data.TorqueStore
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.domain.Session
import com.readiness.app.domain.Streams
import com.readiness.app.domain.Thresholds
import com.readiness.app.domain.TorqueScan
import com.readiness.app.domain.Zones
import java.time.LocalDate

/**
 * Kraftkennwerte aus den Roh-Streams, inkrementell und zwischengespeichert.
 *
 * Streams haben keinen Sammel-Endpunkt: eine Anfrage je Einheit. Beim ersten Aufbau
 * wird deshalb nur ein Kontingent geholt, damit sofort etwas angezeigt werden kann; den
 * Rest holt ein Hintergrundlauf nach. Das Budget wird HÄLFTIG auf aktuelles und älteres
 * Vergleichsfenster verteilt — sonst verbrauchen die neueren Einheiten alles und der
 * ältere Zeitraum bleibt über viele Durchläufe leer, was in der Anzeige wie „keine
 * Daten" aussieht, obwohl sie nur noch nicht geholt sind.
 */
class TorqueRepository(private val client: IcuClient, private val store: TorqueStore) {

    data class Result(val sessions: List<Session>, val scan: TorqueScan)

    fun enrich(sessions: List<Session>, thresholds: Thresholds, cfg: AnalysisConfig,
               today: LocalDate = LocalDate.now(), budget: Int = cfg.streamBudget): Result {
        val cache = store.load()
        val cutoff = today.minusDays((2L * cfg.windowDays)).toString()
        val mid = today.minusDays(cfg.windowDays.toLong()).toString()

        val candidates = sessions.filter {
            it.type in Zones.CYCLING && it.id.isNotEmpty() && it.localDate >= cutoff && it.movingTimeSec >= 300
        }
        val missing = candidates.filter { cache[it.id] == null }
        val recent = missing.filter { it.localDate >= mid }
        val older = missing.filter { it.localDate < mid }
        val half = (budget + 1) / 2
        val pending = (older.take(half) + recent.take(budget - minOf(half, older.size))).take(budget)

        /* FEHLSCHLAG IST KEIN ERGEBNIS. Bisher landete auch bei einem gescheiterten Abruf
           ein leerer Eintrag im Cache — ununterscheidbar von „geladen, aber ohne
           qualifizierendes Fenster" und nie wieder angefasst. Ein einzelner Netzfehler
           löschte damit dauerhaft die Kraftdaten einer Einheit, was still Maximalversuche
           verschwinden lässt. Nur das echte Leerergebnis wird gespeichert, der Fehlschlag
           bleibt offen und kommt im nächsten Durchlauf erneut dran. */
        pending.forEach { s ->
            val st = AppLog.attempt("Streams ${s.id} abrufen") { client.streams(s.id) }.getOrNull() ?: return@forEach
            val norm = Streams.normalize(st["watts"], st["cadence"], st["heartrate"], st["time"])
            cache[s.id] = if (norm == null) TorqueEntry() else TorqueEntry.from(Streams.metrics(norm))
        }

        /* Cache NICHT auf das aktuelle Fenster begrenzen, sondern auf das größtmögliche.
           Sonst wirft ein Wechsel auf einen kürzeren Vergleichszeitraum die Daten des
           längeren weg — und beim Zurückschalten müsste alles erneut geladen werden.
           Der Mehrbedarf ist mit rund 45 Byte je Einheit vernachlässigbar. */
        val keepFrom = today.minusDays(2L * AnalysisConfig.CYCLE_DAYS * 3).toString()
        val keep = sessions.filter {
            it.type in Zones.CYCLING && it.id.isNotEmpty() && it.localDate >= keepFrom
        }.map { it.id }.toSet()
        cache.keys.retainAll(keep)
        store.save(cache)

        val enriched = sessions.map { s -> cache[s.id]?.let { s.copy(torque = it.toDomain()) } ?: s }
        val openNew = candidates.count { cache[it.id] == null && it.localDate >= mid }
        val openOld = candidates.count { cache[it.id] == null && it.localDate < mid }
        return Result(enriched, TorqueScan(candidates.size, openNew + openOld, openOld))
    }

    /**
     * Drehmomentarbeit für heute und gestern (≥85 % FTP bei ≤70 rpm). Nur diese beiden
     * Tage, weil das Kriterium ausschließlich in die 48-Stunden-Regel eingeht.
     */
    fun detectRecentTorqueWork(sessions: List<Session>, thresholds: Thresholds,
                               today: LocalDate = LocalDate.now(), allowNetwork: Boolean = true): List<Session> {
        if (!allowNetwork) return sessions
        val days = setOf(today.toString(), today.minusDays(1).toString())
        return sessions.map { s ->
            if (s.type !in Zones.CYCLING || s.localDate !in days || s.id.isEmpty()) return@map s
            val ftp = (if (s.trainer) thresholds.indoorFtp ?: thresholds.outdoorFtp
                       else thresholds.outdoorFtp ?: thresholds.indoorFtp)?.toDouble() ?: return@map s
            AppLog.attempt("Drehmomentarbeit ${s.id}") {
                val st = client.streams(s.id, "watts,cadence,time")
                val norm = Streams.normalize(st["watts"], st["cadence"], null, st["time"])
                if (norm == null) s else s.copy(torqueWorkSec = Streams.torqueWorkSeconds(norm, ftp))
            }.getOrDefault(s)
        }
    }
}
