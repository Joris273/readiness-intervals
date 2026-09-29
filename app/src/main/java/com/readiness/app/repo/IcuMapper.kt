package com.readiness.app.repo

import com.readiness.app.data.ActivityDto
import com.readiness.app.data.RawBundle
import com.readiness.app.data.SportSettingsDto
import com.readiness.app.data.WellnessDto
import com.readiness.app.domain.Session
import com.readiness.app.domain.SubjectiveEntry
import com.readiness.app.domain.Thresholds
import com.readiness.app.domain.WellnessDay
import com.readiness.app.domain.Zones
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Übersetzt API-Transportmodelle in Domänenmodelle. Rein rechnend, ohne Netz und ohne
 * Android — dadurch lässt sich derselbe Weg auch im Backtest auf echten Rohdaten fahren.
 */
object IcuMapper {

    data class RawData(
        val wellness: List<WellnessDay>,
        val sessions: List<Session>,
        val thresholds: Thresholds,
        val athleteName: String?,
    )

    fun map(b: RawBundle, today: LocalDate = LocalDate.now()): RawData =
        map(b.wellness, b.activities, b.sportSettings, b.athleteName, today)

    fun map(wellness: List<WellnessDto>, activities: List<ActivityDto>, settings: List<SportSettingsDto> = emptyList(),
            athleteName: String? = null, today: LocalDate = LocalDate.now()): RawData {
        val percent = ActivityDto.intensityIsPercent(activities)
        return RawData(
            wellness = wellness.map { it.toDomain() },
            sessions = activities.mapNotNull { it.toDomain(percent) },
            thresholds = parseThresholds(settings, activities, today),
            athleteName = athleteName,
        )
    }

    private fun WellnessDto.toDomain() = WellnessDay(
        date = id, ctl = ctl, atl = atl, hrv = hrv,
        restingHr = restingHR, sleepSeconds = sleepSecs, sleepScore = sleepScore,
        subjective = SubjectiveEntry(soreness, fatigue, stress, mood, motivation, injury).takeIf { it.hasAny })

    private fun ActivityDto.toDomain(intensityPercent: Boolean): Session? {
        val date = startDateLocal?.take(10) ?: return null
        val zones = parseZones(zoneTimes)
        return Session(
            id = id, type = type ?: "", trainer = trainer == true, localDate = date,
            movingTimeSec = movingTime ?: 0.0, trainingLoad = trainingLoad ?: 0.0,
            intensity = intensity?.let { if (intensityPercent) it / 100 else it }, eftp = eftp,
            normalizedPower = normalizedPower, avgHeartRate = heartRate,
            decoupling = decouplingValue, zoneSeconds = zones, hasZones = zones.isNotEmpty())
    }

    private fun parseZones(el: JsonElement?): Map<Int, Int> {
        val arr = el as? JsonArray ?: return emptyMap()
        val entries = arr.mapIndexed { i, e ->
            when (e) {
                is JsonObject -> Zones.ZoneEntry(
                    id = e["id"]?.jsonPrimitive?.content,
                    zone = e["zone"]?.jsonPrimitive?.content?.toIntOrNull(),
                    seconds = (e["secs"] ?: e["time"] ?: e["x"])?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0)
                is JsonPrimitive -> Zones.ZoneEntry(null, i + 1, e.content.toDoubleOrNull()?.toInt() ?: 0)
                else -> Zones.ZoneEntry(null, i + 1, 0)
            }
        }
        return Zones.parseZoneSeconds(entries)
    }

    private fun parseThresholds(settings: List<SportSettingsDto>, activities: List<ActivityDto>, today: LocalDate): Thresholds {
        var outdoor: Int? = null; var indoor: Int? = null; var eftp: Int? = null
        var lthr: Int? = null; var maxHr: Int? = null
        settings.forEach { s ->
            val types = s.types ?: s.type?.let { listOf(it) } ?: emptyList()
            if (types.any { it == "Ride" || it == "VirtualRide" }) {
                (s.ftp ?: s.icuFtp)?.let { outdoor = it.roundToInt() }
                s.indoorFtp?.let { indoor = it.roundToInt() }
                if (s.eFTPSupported == true) s.mmpModel?.ftp?.let { eftp = it.roundToInt() }
                s.lthr?.let { lthr = it.roundToInt() }
                s.maxHr?.let { maxHr = it.roundToInt() }
            }
        }
        /* Rückfall nur aus RADaktivitäten und nur aus den letzten 42 Tagen: die
           Lauf-eFTP hat eine eigene, deutlich höhere Schwelle und würde sonst gegen die
           Rad-FTP verglichen — eine Falschwarnung „FTP veraltet". */
        if (eftp == null) {
            val cut = today.minusDays(42).toString()
            activities.filter { (it.type ?: "") in Zones.CYCLING && (it.startDateLocal ?: "") >= cut }
                .mapNotNull { it.eftp }.maxOrNull()?.let { eftp = it.roundToInt() }
        }
        var stale: String? = null
        val ref = outdoor?.toDouble()
        val e = eftp
        if (ref != null && e != null && e > ref * 1.03) {
            val pct = ((e / ref - 1) * 100).roundToInt()
            stale = "eFTP $e W liegt $pct % über deiner Outdoor-FTP (${ref.roundToInt()} W) — evtl. FTP in intervals.icu anheben."
        }
        return Thresholds(outdoor, indoor, eftp, lthr, maxHr, stale)
    }
}
