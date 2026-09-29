package com.readiness.app.domain

/**
 * Sportartgruppen und Zonen-Auswertung.
 */
object Zones {

    /** Rad: kalibrierte FTP vorhanden, Zonenzeiten sind Leistungszonen → volle Kriterien. */
    val CYCLING = setOf("Ride", "VirtualRide", "GravelRide", "MountainBikeRide")

    /**
     * Laufen: eigene Laufschwelle. Hier greifen NUR die Z5+/Z6+-Kriterien, nicht das
     * Schwellenkriterium. Je nach Datenlage können Laufzonen leistungs-, tempo- oder
     * herzfrequenzbasiert sein; „≥6 min Z5+" ist in allen drei Lesarten ein harter Reiz,
     * „20 min Z4" in der HF-Lesart dagegen nur ein lockerer Dauerlauf.
     */
    val RUNNING = setOf("Run", "VirtualRun", "TrailRun")

    /**
     * Zeit in Zonen aus der API-Struktur.
     *
     * WICHTIG: Die Zonenliste enthält neben Z1–Z7 auch ÜBERLAPPENDE Bänder — allen voran
     * „SS" (Sweet Spot, 84–97 % FTP). Diese sind keine eigene Intensitätsstufe, sondern
     * ein Ausschnitt aus Z3/Z4, und ihre Sekunden stecken in den regulären Zonen bereits
     * drin. Wer sie mitzählt, addiert Zeit doppelt und stuft sie — weil „SS" hinter Z7
     * steht — sogar als höchste Intensität ein. Genau dieser Fehler hat im Prototyp eine
     * lockere Ausfahrt als harten Reiz ausgewiesen.
     *
     * Deshalb: Einträge mit ID zählen NUR bei echter Zonennummer (Z1…Z7); benannte Bänder
     * werden verworfen. Die Position im Array zählt nur bei reinen Zahlenlisten ohne IDs.
     */
    fun parseZoneSeconds(entries: List<ZoneEntry>): Map<Int, Int> {
        val out = HashMap<Int, Int>()
        entries.forEachIndexed { index, e ->
            val zone: Int? = when {
                e.id != null -> Regex("^\\s*[zZ]?\\s*(\\d+)\\s*$").find(e.id)?.groupValues?.get(1)?.toIntOrNull()
                e.zone != null -> e.zone
                else -> index + 1
            }
            if (zone != null) out[zone] = (out[zone] ?: 0) + e.seconds
        }
        return out
    }

    data class ZoneEntry(val id: String?, val zone: Int?, val seconds: Int)

    fun secondsWhere(zones: Map<Int, Int>, pred: (Int) -> Boolean): Int =
        zones.entries.filter { pred(it.key) }.sumOf { it.value }
}
