package com.readiness.app.data

import kotlinx.serialization.Serializable

/**
 * Speicherbares Darstellungsmodell. Wird aus dem Domänenergebnis abgebildet, damit die
 * Domäne frei von Serialisierungs-Annotationen bleibt.
 *
 * Dient zugleich als Offline-Cache und als einzige Datenquelle des Homescreen-Widgets —
 * das Widget funkt bewusst nicht selbst.
 */
@Serializable
data class Snapshot(
    val score: Int? = null,
    val baseScore: Int? = null,
    val deduction: Int = 0,
    val word: String = "",
    val colorHex: String = "#8A97A8",
    val recoTitle: String = "",
    val recoText: String = "",
    val dataDate: String = "",
    val updatedAt: Long = 0L,
    val renormalized: Boolean = false,
    val components: List<Component> = emptyList(),
    val tiles: List<Tile> = emptyList(),
    val thresholds: Thresholds = Thresholds(),
    val limits: List<String> = emptyList(),
    val loadNote: String = "",
    val hrvDate: String? = null,
    val confounders: List<String> = emptyList(),
    val napMinutesToday: Int = 0,
    /** Einordnung in die eigene Verteilung — ohne sie wirkt jeder Wert pauschal hoch. */
    val scoreContext: String? = null,
    val progression: Progression? = null,
    val chart: List<ChartPoint> = emptyList(),
) {
    @Serializable data class ChartPoint(
        val date: String, val ctl: Double? = null, val atl: Double? = null,
        val tsb: Double? = null, val hrv: Double? = null, val load: Double = 0.0)
    @Serializable data class Component(
        val id: String, val name: String, val weightPct: Int, val sub: Int?,
        val explanation: String, val colorHex: String)
    @Serializable data class Tile(val label: String, val value: String, val sub: String)
    @Serializable data class Thresholds(
        val outdoorFtp: Int? = null, val indoorFtp: Int? = null, val eftp: Int? = null,
        val lthr: Int? = null, val maxHr: Int? = null, val staleMessage: String? = null)
    @Serializable data class Row(
        val label: String, val note: String, val value: String,
        val delta: String?, val deltaKind: String, val reason: String?)
    @Serializable data class Progression(
        val ok: Boolean, val windowDays: Int, val cycles: Int,
        val title: String, val text: String, val colorHex: String,
        val chips: List<Chip> = emptyList(),
        val rows: List<Row> = emptyList(),
        val share12: Double? = null, val share3: Double? = null, val share4: Double? = null,
        val zoneHours: Double? = null, val distributionNote: String = "",
        val hint: String = "", val anyThin: Boolean = false)
    @Serializable data class Chip(val key: String, val value: String, val kind: String)
}
