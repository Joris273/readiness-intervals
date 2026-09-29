package com.readiness.app.data

import android.content.Context
import com.readiness.app.domain.TorqueMetrics
import kotlinx.serialization.Serializable
import java.io.File

/** Offline-Cache des letzten Ergebnisses; zugleich Datenquelle des Widgets. */
class SnapshotStore(context: Context) {
    private val file = File(context.filesDir, "snapshot.json")
    fun save(s: Snapshot) {
        AppLog.attempt("Snapshot speichern") { AtomicWrite.write(file, AppJson.encodeToString(Snapshot.serializer(), s)) }
    }
    fun load(): Snapshot? = AppLog.attempt("Snapshot lesen") {
        if (file.exists()) AppJson.decodeFromString(Snapshot.serializer(), file.readText()) else null
    }.getOrNull()
}

@Serializable
data class TorqueEntry(
    val d60: Int? = null, val n60: Int = 0,
    val d300: Int? = null, val n300: Int = 0,
    val d600: Int? = null, val n600: Int = 0,
    val p300: Int? = null,
    val ef: Double? = null, val efW: Int? = null, val efHr: Int? = null,
    val t30: Double? = null, val rs: Boolean = false,
) {
    fun toDomain() = TorqueMetrics(d60, n60, d300, n300, d600, n600, p300, ef, efW, efHr, t30, rs)
    companion object {
        fun from(t: TorqueMetrics) = TorqueEntry(
            t.d60, t.n60, t.d300, t.n300, t.d600, t.n600, t.p300,
            t.efficiency, t.efficiencyW, t.efficiencyHr, t.peakTorque30s, t.resampled)
    }
}

@Serializable
private data class TorqueFile(val v: Int = TorqueStore.VERSION, val d: Map<String, TorqueEntry> = emptyMap())

/**
 * Kraftkennwerte je Aktivität, einmalig berechnet und dauerhaft gespeichert.
 *
 * Der Grund für den Cache: Streams haben bei intervals.icu keinen Sammel-Endpunkt — es
 * ist eine Anfrage JE Einheit. Ohne Cache müsste bei jedem Start die gesamte Historie
 * erneut übertragen werden. Rund 45 Byte je Einheit, damit auch bei drei Zyklen
 * unkritisch.
 */
class TorqueStore(context: Context) {
    private val file = File(context.filesDir, "torque.json")

    fun load(): MutableMap<String, TorqueEntry> {
        if (!file.exists()) return mutableMapOf()
        return try {
            val f = AppJson.decodeFromString(TorqueFile.serializer(), file.readText())
            if (f.v != VERSION) mutableMapOf() else f.d.toMutableMap()   // Strukturwechsel: verwerfen
        } catch (e: Exception) {
            /* Unlesbar: zur Diagnose beiseitelegen statt still zu überschreiben. */
            AppLog.w("Kraft-Cache unlesbar, gesichert als torque.json.bad", e)
            runCatching { file.copyTo(File(file.parentFile, "torque.json.bad"), overwrite = true) }
            mutableMapOf()
        }
    }

    fun save(d: Map<String, TorqueEntry>) {
        AppLog.attempt("Kraft-Cache speichern") {
            AtomicWrite.write(file, AppJson.encodeToString(TorqueFile.serializer(), TorqueFile(VERSION, d)))
        }
    }

    companion object { const val VERSION = 3 }
}
