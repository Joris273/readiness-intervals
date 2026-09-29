package com.readiness.app.repo

import com.readiness.app.data.AppLog
import com.readiness.app.data.IcuClient
import com.readiness.app.data.RawBundle
import com.readiness.app.domain.AnalysisConfig
import java.time.LocalDate

/**
 * Beschafft Rohdaten und übersetzt sie in Domänenmodelle. Rechnet selbst nichts aus,
 * was zur Auswertung gehört — diese Trennung ersetzt die Ladefunktion des Prototyps,
 * die Abruf, Berechnung und Darstellung in einer einzigen Prozedur vermischt hatte.
 */
class IcuRepository(private val client: IcuClient) {

    /** Rohantwort holen — in der Form, in der sie sich unverändert speichern lässt. */
    fun fetchRaw(cfg: AnalysisConfig, today: LocalDate = LocalDate.now()): RawBundle {
        val newest = today.toString()
        // Immer die volle Tiefe holen, damit ein Zyklenwechsel ohne Netzabruf auskommt
        val oldest = today.minusDays(cfg.fetchDays.toLong()).toString()

        val wellness = client.wellness(oldest, newest).sortedBy { it.id }
        var activities = client.activities(oldest, newest)
        val settings = client.sportSettings()
        val name = client.profile()?.name

        /* Selbstheilung: fehlen Leistungs- oder HF-Feld vollständig, heißen sie
           vermutlich anders als erwartet. Dann einmalig eine kleine Stichprobe OHNE
           Feldfilter holen und die Namen am echten Objekt ermitteln. Der Regelfall
           bleibt der schlanke, gefilterte Abruf. */
        if (activities.none { it.normalizedPower != null } || activities.none { it.heartRate != null }) {
            AppLog.attempt("Feldnamen-Stichprobe") {
                val sample = client.activities(today.minusDays(30).toString(), newest, fields = null)
                val byId = sample.associateBy { it.id }
                activities = activities.map { a -> byId[a.id] ?: a }
            }
        }

        return RawBundle(
            day = newest, savedAt = System.currentTimeMillis(), fetchDays = cfg.fetchDays,
            wellness = wellness, activities = activities, sportSettings = settings, athleteName = name,
        )
    }

    /** Rohantwort in Domänenmodelle übersetzen. Rein rechnend, ohne Netzzugriff. */
    fun map(b: RawBundle, today: LocalDate = LocalDate.now()): IcuMapper.RawData = IcuMapper.map(b, today)
}
