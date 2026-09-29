package com.readiness.app

import com.readiness.app.data.ActivityDto
import com.readiness.app.data.AppJson
import com.readiness.app.data.WellnessDto
import com.readiness.app.domain.*
import com.readiness.app.repo.IcuMapper
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * Backtest auf ECHTEN Rohdaten: die Auswertung Tag für Tag über die letzten 90 Tage,
 * jeweils nur mit den Daten, die an diesem Tag bekannt waren.
 *
 * Datenquelle: Umgebungsvariable `READINESS_RAW` mit dem Pfad zu einer JSON-Datei, die
 * `wellness` und `activities` im intervals.icu-Format enthält — entweder der Rohdaten-Cache
 * der App (`raw.json`) oder die Ausgabe von `tools/fetch-backtest-data.ps1`. Ohne die
 * Variable wird der Test übersprungen; er ist eine Kalibrierhilfe, keine Prüfung.
 */
class BacktestTest {

    @Test fun letzteNeunzigTage() {
        val path = System.getenv("READINESS_RAW")
        assumeTrue("READINESS_RAW nicht gesetzt — Backtest übersprungen", !path.isNullOrBlank() && File(path).exists())
        val root = AppJson.parseToJsonElement(File(path!!).readText()).jsonObject
        val wellness = AppJson.decodeFromJsonElement(ListSerializer(WellnessDto.serializer()), root["wellness"] as? JsonArray ?: JsonArray(emptyList()))
        val activities = AppJson.decodeFromJsonElement(ListSerializer(ActivityDto.serializer()), root["activities"] as? JsonArray ?: JsonArray(emptyList()))
        val lastDay = wellness.maxOfOrNull { it.id }?.let { LocalDate.parse(it) } ?: return
        val data = IcuMapper.map(wellness, activities, today = lastDay)

        val verdicts = HashMap<Verdict, Int>()
        val notes = HashMap<String, Int>()
        var forced = 0
        println("Datum       Ampel    Basis  Gründe")
        for (back in 89 downTo 0) {
            val day = lastDay.minusDays(back.toLong())
            val iso = day.toString()
            val wl = data.wellness.filter { it.date <= iso }
            val ss = data.sessions.filter { it.localDate <= iso }
            if (wl.isEmpty()) continue
            val r = ReadinessEngine.evaluate(wl, ss, data.thresholds, AnalysisConfig(), day)
            val v = r.recommendation.morning
            verdicts[v] = (verdicts[v] ?: 0) + 1
            if (r.loadHistory.forceRest) forced++
            val why = r.recommendation.notes.map { n -> n.code.name + (n.limit?.let { ":" + it.code.name } ?: "") }
            why.forEach { notes[it] = (notes[it] ?: 0) + 1 }
            println("$iso  ${v.name.padEnd(7)}  ${(r.baseScore?.toString() ?: "–").padStart(5)}  ${why.joinToString(", ")}")
        }
        val total = verdicts.values.sum().coerceAtLeast(1)
        println("\nVerteilung: " + Verdict.entries.filter { verdicts.containsKey(it) }
            .joinToString { "${it.name} ${verdicts[it]} (${"%.0f".format(verdicts[it]!! * 100.0 / total)} %)" })
        println("Erzwungene Ruhetage aus der Belastungshistorie: $forced")
        println("Häufigste Gründe:")
        notes.entries.sortedByDescending { it.value }.take(12).forEach { println("  ${it.value.toString().padStart(3)}  ${it.key}") }
    }
}
