package com.readiness.app.data

import android.content.Context
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Verlauf der Tageswerte, um den Score einordnen zu können.
 *
 * Das Problem, das damit gelöst wird: Alle Teilscores messen gegen die EIGENE Baseline.
 * Wer stabil und gut erholt ist, liegt deshalb fast immer im oberen Bereich — der Wert
 * wirkt dann pauschal hoch, obwohl er korrekt ist. Statt die Skala künstlich zu spreizen,
 * wird der Wert gegen die eigene Verteilung eingeordnet.
 *
 * Gespeichert wird der BASISWERT (Erholungszustand ohne Belastungsabzug) und nur der
 * Morgenwert je Tag. Die frühere Datei `scores.json` enthielt angezeigte Werte mit Abzug
 * und wurde nach dem Training überschrieben; sie wird deshalb nicht weitergeführt.
 */
class ScoreHistoryStore(context: Context) {
    private val file = File(context.filesDir, "scores_base.json")
    private val ser = MapSerializer(String.serializer(), Int.serializer())

    fun load(): Map<String, Int> = AppLog.attempt("Score-Verlauf lesen") {
        if (file.exists()) AppJson.decodeFromString(ser, file.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    /** @param overwrite false nach einer heutigen Einheit: ein vorhandener Morgenwert bleibt stehen */
    fun record(date: String, score: Int?, keepFrom: String, overwrite: Boolean) {
        val before = load()
        val after = ScoreHistory.merge(before, date, score, keepFrom, overwrite)
        if (after == before) return
        AppLog.attempt("Score-Verlauf speichern") { AtomicWrite.write(file, AppJson.encodeToString(ser, after)) }
    }

    fun classify(score: Int?, today: String, window: Int = 60): ScoreHistory.Context? =
        ScoreHistory.classify(load(), score, today, window)
}

/** Reine Logik des Score-Verlaufs — ohne Dateizugriff testbar. */
object ScoreHistory {

    data class Context(val percentile: Int, val median: Int, val days: Int, val label: String)

    fun merge(all: Map<String, Int>, date: String, score: Int?, keepFrom: String, overwrite: Boolean): Map<String, Int> {
        if (score == null || date.isBlank()) return all
        if (!overwrite && all.containsKey(date)) return all
        return (all + (date to score)).filterKeys { it >= keepFrom }
    }

    /** Einordnung des heutigen Werts in die eigene Verteilung der letzten `window` Tage. */
    fun classify(all: Map<String, Int>, score: Int?, today: String, window: Int = 60): Context? {
        if (score == null) return null
        val from = runCatching { LocalDate.parse(today).minusDays(window.toLong()).toString() }.getOrNull() ?: return null
        val hist = all.filterKeys { it != today && it >= from && it < today }.values.toList()
        if (hist.size < 14) return null      // zu wenig Verlauf für eine Einordnung
        val below = hist.count { it < score }
        val pct = (below * 100.0 / hist.size).roundToInt()
        val median = hist.sorted().let {
            if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2]) / 2
        }
        val label = when {
            pct >= 80 -> "einer deiner besten Tage"
            pct >= 60 -> "überdurchschnittlich für dich"
            pct >= 40 -> "ein durchschnittlicher Tag"
            pct >= 20 -> "unterdurchschnittlich für dich"
            else -> "einer deiner schwächsten Tage"
        }
        return Context(pct, median, hist.size, label)
    }
}
