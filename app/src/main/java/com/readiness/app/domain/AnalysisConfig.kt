package com.readiness.app.domain

/**
 * Alle Stellschrauben der Auswertung an EINER Stelle, als unveränderliches Objekt.
 *
 * Im Prototyp waren Fensterlänge und Historientiefe globale veränderliche Variablen,
 * die der Ladevorgang als Seiteneffekt gesetzt hat. In einer Coroutine-Umgebung mit
 * parallel laufenden Aufträgen (Vordergrund-Refresh und Hintergrund-Worker) wäre das
 * eine Race Condition gewesen: Der zweite Lauf hätte dem ersten die Fensterlänge unter
 * den Füßen weggezogen. Die Konfiguration wird deshalb durchgereicht, nicht global
 * gehalten — das macht die Domänenschicht zugleich ohne Android-Umgebung testbar.
 */
data class AnalysisConfig(
    /** Vergleichszyklen des Formaufbaus (1–3). Ein Zyklus = 42 Tage. */
    val cycles: Int = 1,
    /** Individueller Schlafbedarf in Stunden; null = aus der eigenen Historie ableiten. */
    val sleepNeedHours: Double? = null,
    /**
     * Powernap-Minuten JE TAG (erfasst intervals.icu nicht).
     *
     * Ein globaler Durchschnitt wäre eine Fiktion: Nickerchen fallen nicht jeden Tag an,
     * und ein Mittelwert würde sie an schlaflosen Tagen erfinden und an Tagen mit langem
     * Nap unterschlagen. Für die Bilanz zählt, was an DIESEM Tag tatsächlich dazukam.
     */
    val napMinutesByDay: Map<String, Int> = emptyMap(),
    /** Tage mit bekannter, nicht trainingsbedingter Störung: ISO-Datum -> Ursachen. */
    val confounders: Map<String, List<String>> = emptyMap(),
    /** Schwellen und Gewichte der Auswertung. */
    val params: ScoringParams = ScoringParams.DEFAULT,
) {
    /** Länge eines Vergleichsfensters in Tagen. */
    val windowDays: Int get() = CYCLE_DAYS * cycles.coerceIn(1, 3)

    /** Auswertungstiefe: zwei Fenster plus Puffer für die Baselines. */
    val historyDays: Int get() = 2 * windowDays + 36

    /**
     * ABGERUFEN wird dagegen immer die maximal mögliche Tiefe, unabhängig von der Auswahl.
     *
     * Der Wechsel des Vergleichszeitraums ändert nur, welcher Ausschnitt ausgewertet wird —
     * nicht, welche Daten es gibt. Holt man jeweils nur das aktuell Nötige, löst jeder Klick
     * auf „2 Zyklen" einen Neuabruf aus und fühlt sich träge an. Mit dem vollen Fenster ist
     * jeder Wechsel eine reine Rechenoperation. Der Mehrbedarf sind rund 300 KB je Abruf,
     * einmalig statt bei jeder Umschaltung.
     */
    val fetchDays: Int get() = 2 * (CYCLE_DAYS * 3) + 36

    /** Neue Stream-Abrufe je Ladevorgang; skaliert mit der Fensterlänge. */
    val streamBudget: Int get() = 8 + 4 * (cycles.coerceIn(1, 3) - 1)

    /** Tage, an denen ein Störfaktor eingetragen ist. */
    val confoundedDays: Set<String> get() = confounders.filterValues { it.isNotEmpty() }.keys

    fun napHoursOn(date: String): Double = (napMinutesByDay[date] ?: 0) / 60.0

    companion object {
        const val CYCLE_DAYS = 42
        val CYCLE_OPTIONS = listOf(
            CycleOption(1, "1 Zyklus", "6 Wochen", "schnellste Reaktion, empfindlicher gegen Rauschen"),
            CycleOption(2, "2 Zyklen", "12 Wochen", "entspricht dem Horizont, in dem Trainierte messbare Sprünge zeigen"),
            CycleOption(3, "3 Zyklen", "18 Wochen", "höchste Sicherheit, reagiert am trägsten"),
        )
    }
}

data class CycleOption(val n: Int, val label: String, val sub: String, val hint: String)

/**
 * Störfaktoren sind NICHT gleichwertig — sie unterscheiden sich darin, was aus der
 * HRV-Absenkung folgt. Alle drei Klassen werden gleichermaßen aus Baseline und
 * SWC-Bandbreite ausgeschlossen, aber nur eine davon mildert eine Empfehlung ab.
 */
enum class ConfounderKind { EXTERNAL, MEDICAL, INVALID }

data class Confounder(val key: String, val label: String, val kind: ConfounderKind)

object Confounders {
    val ALL = listOf(
        // Absenkung real, aber kein Trainingsstress-Signal -> Intensität deckeln, kein Ruhetag
        Confounder("alcohol", "Alkohol", ConfounderKind.EXTERNAL),
        Confounder("stress", "außergewöhnlicher Alltagsstress", ConfounderKind.EXTERNAL),
        Confounder("travel", "Reise/Hitze/Höhe", ConfounderKind.EXTERNAL),
        Confounder("badnight", "gestörte Nacht (extern)", ConfounderKind.EXTERNAL),
        // Ruhe ist die indizierte Maßnahme und darf nie abgeschwächt werden
        Confounder("illness", "Krankheit/Infekt", ConfounderKind.MEDICAL),
        // Keine Messung -> HRV fällt aus der Gewichtung, statt bewertet zu werden
        Confounder("artifact", "Messartefakt", ConfounderKind.INVALID),
    )
    fun byKey(k: String): Confounder? = ALL.firstOrNull { it.key == k }
    fun label(k: String): String = byKey(k)?.label ?: k
}
