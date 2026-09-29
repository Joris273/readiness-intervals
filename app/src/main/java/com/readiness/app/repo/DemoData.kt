package com.readiness.app.repo

import com.readiness.app.data.ActivityDto
import com.readiness.app.data.MmpModelDto
import com.readiness.app.data.RawBundle
import com.readiness.app.data.SportSettingsDto
import com.readiness.app.data.WellnessDto
import com.readiness.app.domain.TorqueMetrics
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Random
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Synthetischer Athlet für den Build-Typ „demo" (Screenshots, Ausprobieren ohne API-Key).
 *
 * Kein echter Mensch, keine echten Werte: ein gut trainierter Radfahrer im Aufbau, mit
 * realistischer Tagesstreuung. Das Szenario ist so gewählt, dass die Kernlogik sichtbar
 * wird — gestern ein VO2max-Reiz, heute unauffällige Erholungswerte (zweiter Qualitätstag
 * mit anderem Schwerpunkt), steigende Last mit messbarer Antwort im Formaufbau.
 * Deterministisch: gleiche Anzeige bei jedem Start.
 */
object DemoData {

    private data class Day(val date: LocalDate, val load: Double, val kind: Kind)
    private enum class Kind { REST, ENDURANCE, THRESHOLD, VO2, LONG }

    private fun kindOf(d: LocalDate): Kind = when (d.dayOfWeek) {
        DayOfWeek.MONDAY -> Kind.REST
        DayOfWeek.TUESDAY -> Kind.VO2
        DayOfWeek.WEDNESDAY -> Kind.ENDURANCE
        DayOfWeek.THURSDAY -> Kind.THRESHOLD
        DayOfWeek.FRIDAY -> Kind.REST
        DayOfWeek.SATURDAY -> Kind.LONG
        DayOfWeek.SUNDAY -> Kind.ENDURANCE
    }

    fun bundle(today: LocalDate, fetchDays: Int): RawBundle {
        val rnd = Random(20260929)
        val days = (fetchDays downTo 1).map { today.minusDays(it.toLong()) }
        // Gestern immer ein VO2max-Tag, damit das Szenario unabhängig vom Wochentag gleich bleibt
        fun kind(d: LocalDate) = if (d == today.minusDays(1)) Kind.VO2 else if (d == today.minusDays(2)) Kind.REST else kindOf(d)

        // Aufbau: Umfang steigt über die Monate um gut 25 %
        val plan = days.map { d ->
            val progress = 1.0 - ChronoUnit.DAYS.between(d, today).toDouble() / fetchDays
            val scale = 0.8 + 0.35 * progress * progress * progress   // Aufbau vor allem in den letzten Wochen
            val base = when (kind(d)) {
                Kind.REST -> 0.0; Kind.ENDURANCE -> 62.0; Kind.THRESHOLD -> 88.0; Kind.VO2 -> 92.0; Kind.LONG -> 135.0
            }
            Day(d, if (base == 0.0) 0.0 else base * scale * (0.9 + 0.2 * rnd.nextDouble()), kind(d))
        }

        // CTL/ATL wie intervals.icu (exponentiell, 42/7 Tage), Tageszeile inkl. Tageslast
        var ctl = 45.0; var atl = 45.0
        val kc = 1 - exp(-1.0 / 42); val ka = 1 - exp(-1.0 / 7)
        val wellness = mutableListOf<WellnessDto>()
        plan.forEach { p ->
            ctl += (p.load - ctl) * kc; atl += (p.load - atl) * ka
            wellness += wellnessFor(p.date, ctl, atl, rnd, isToday = false)
        }
        // Heute früh: noch nicht trainiert, Erholungswerte unauffällig
        ctl += (0 - ctl) * kc; atl += (0 - atl) * ka
        wellness += wellnessFor(today, ctl, atl, rnd, isToday = true)

        val activities = plan.filter { it.load > 0 }.map { activityFor(it, today, rnd) }
        val settings = listOf(SportSettingsDto(types = listOf("Ride", "VirtualRide"), ftp = 285.0, indoorFtp = 275.0,
            eFTPSupported = true, mmpModel = MmpModelDto(ftp = 289.0), lthr = 168.0, maxHr = 188.0))

        return RawBundle(day = today.toString(), savedAt = System.currentTimeMillis(), fetchDays = fetchDays,
            wellness = wellness, activities = activities, sportSettings = settings, athleteName = "Demo")
    }

    private fun wellnessFor(d: LocalDate, ctl: Double, atl: Double, rnd: Random, isToday: Boolean): WellnessDto {
        val g = { rnd.nextGaussian() }
        val lnHrv = ln(64.0) + (if (isToday) 0.03 else 0.075 * g())
        val sleepH = if (isToday) 7.6 else 7.4 + 0.5 * g()
        fun item(p1: Double, today: Double) =
            if (isToday) today else when { rnd.nextDouble() < p1 -> 1.0; rnd.nextDouble() < 0.85 -> 2.0; else -> 3.0 }
        return WellnessDto(
            id = d.toString(), ctl = (ctl * 10).roundToInt() / 10.0, atl = (atl * 10).roundToInt() / 10.0,
            hrv = (exp(lnHrv) * 10).roundToInt() / 10.0,
            restingHR = if (isToday) 46.0 else (46.5 + 1.6 * g()).roundToInt().toDouble(),
            sleepSecs = (sleepH.coerceIn(5.5, 9.5) * 3600).roundToInt().toDouble(),
            sleepScore = if (isToday) 84.0 else (80 + 6 * g()).coerceIn(55.0, 97.0).roundToInt().toDouble(),
            soreness = item(0.35, 2.0), fatigue = item(0.3, 2.0), stress = item(0.4, 1.0),
            mood = item(0.45, 1.0), motivation = item(0.5, 1.0),
        )
    }

    private fun zones(vararg secs: Pair<String, Int>) = JsonArray(secs.map { (id, s) ->
        JsonObject(mapOf("id" to JsonPrimitive(id), "secs" to JsonPrimitive(s)))
    })

    private fun activityFor(p: Day, today: LocalDate, rnd: Random): ActivityDto {
        val recent = ChronoUnit.DAYS.between(p.date, today) < 42
        // Antwort: aerobe Effizienz steigt, Entkopplung fällt
        val ef = (if (recent) 1.71 else 1.63) * (1 + 0.025 * rnd.nextGaussian())
        val (dur, ifPct, z) = when (p.kind) {
            Kind.ENDURANCE -> Triple(5400.0, 68.0, zones("Z1" to 1500, "Z2" to 3700, "Z3" to 200))
            Kind.LONG -> Triple(12600.0, 70.0, zones("Z1" to 3000, "Z2" to 8600, "Z3" to 900, "Z4" to 100))
            Kind.THRESHOLD -> Triple(5100.0, 82.0, zones("Z1" to 1400, "Z2" to 1300, "Z3" to 300, "Z4" to 2000, "Z5" to 100))
            Kind.VO2 -> Triple(4500.0, 85.0, zones("Z1" to 1500, "Z2" to 1400, "Z3" to 300, "Z4" to 500, "Z5" to 720, "Z6" to 80))
            Kind.REST -> Triple(0.0, 0.0, JsonArray(emptyList()))
        }
        val np = 285 * ifPct / 100
        val eftp = if (p.kind == Kind.VO2) (if (recent) 296.0 else 281.0) + 3 * rnd.nextGaussian() else null
        return ActivityDto(
            id = "demo-${p.date}", type = if (p.kind == Kind.ENDURANCE && p.date.dayOfMonth % 2 == 0) "VirtualRide" else "Ride",
            trainer = false, startDateLocal = "${p.date}T07:30:00", movingTime = dur,
            trainingLoad = (p.load * 10).roundToInt() / 10.0, intensity = ifPct, eftp = eftp, zoneTimes = z,
            weightedAvgWatts = np, averageHeartrate = (np / ef).roundToInt().toDouble(),
            icuDecoupling = if (dur >= 3600 && ifPct <= 75) ((if (recent) 3.2 else 5.4) + 1.2 * rnd.nextGaussian()) else null,
        )
    }

    /**
     * Kraftkennwerte je Einheit (in der echten App aus den Roh-Streams gewonnen). Nur die
     * Schwelleneinheiten enthalten Kraftausdauer-Blöcke bei niedriger Trittfrequenz.
     */
    fun torque(activities: List<ActivityDto>, today: LocalDate): Map<String, TorqueMetrics> =
        activities.filter { (it.intensity ?: 0.0) in 80.0..83.0 }.associate { a ->
            val recent = ChronoUnit.DAYS.between(LocalDate.parse(a.startDateLocal!!.take(10)), today) < 42
            val g = if (recent) 1.05 else 1.0
            val d300 = (292 * g).roundToInt()
            a.id to TorqueMetrics(
                d60 = (d300 * 1.24).roundToInt(), n60 = 3, d300 = d300, n300 = 2,
                d600 = (d300 / 1.08).roundToInt(), n600 = 1, p300 = d300 + 12,
                efficiency = (d300 / 164.0 * 1000).roundToInt() / 1000.0, efficiencyW = d300, efficiencyHr = 164,
                peakTorque30s = 52.0 * g)
        }
}
