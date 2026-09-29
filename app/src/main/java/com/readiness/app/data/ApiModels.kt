package com.readiness.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Transportmodelle der intervals.icu-API. Bewusst getrennt von den Domänenmodellen:
 * die API darf sich ändern, ohne dass die wissenschaftliche Logik angefasst wird.
 * Die Übersetzung übernimmt der Mapper im Repository.
 */

@Serializable
data class WellnessDto(
    val id: String = "",
    val ctl: Double? = null,
    val atl: Double? = null,
    val hrv: Double? = null,
    val restingHR: Double? = null,
    val sleepSecs: Double? = null,
    val sleepScore: Double? = null,
    // Subjektive Angaben, intervals.icu-Skala 1 (bestens) … 4 (schlecht)
    val soreness: Double? = null,
    val fatigue: Double? = null,
    val stress: Double? = null,
    val mood: Double? = null,
    val motivation: Double? = null,
    val injury: Double? = null,
)

@Serializable
data class ActivityDto(
    val id: String = "",
    val type: String? = null,
    val trainer: Boolean? = null,
    @SerialName("start_date_local") val startDateLocal: String? = null,
    @SerialName("moving_time") val movingTime: Double? = null,
    @SerialName("icu_training_load") val trainingLoad: Double? = null,
    @SerialName("icu_intensity") val intensity: Double? = null,
    @SerialName("icu_eftp") val eftp: Double? = null,
    @SerialName("icu_zone_times") val zoneTimes: JsonElement? = null,
    // Schreibvarianten für normalisierte Leistung und Herzfrequenz — die Benennung
    // variiert je nach Datenquelle, ein geratener Name liefert sonst still null.
    @SerialName("icu_weighted_avg_watts") val weightedAvgWatts: Double? = null,
    @SerialName("icu_normalized_watts") val normalizedWatts: Double? = null,
    @SerialName("icu_average_watts") val icuAverageWatts: Double? = null,
    @SerialName("average_watts") val averageWatts: Double? = null,
    @SerialName("average_heartrate") val averageHeartrate: Double? = null,
    @SerialName("icu_average_hr") val icuAverageHr: Double? = null,
    @SerialName("average_hr") val averageHr: Double? = null,
    @SerialName("icu_decoupling") val icuDecoupling: Double? = null,
    val decoupling: Double? = null,
) {
    /**
     * NUR echte normalisierte Leistung. Früher fiel der Wert auf die Durchschnittsleistung
     * zurück — die aerobe Effizienz verglich dann NP/HF mit AP/HF, und weil NP ≥ AP ist,
     * entstand aus dem bloßen Datenfeld ein Scheinunterschied zwischen Einheiten.
     */
    val normalizedPower: Double?
        get() = weightedAvgWatts ?: normalizedWatts
    val avgPower: Double?
        get() = icuAverageWatts ?: averageWatts
    val heartRate: Double?
        get() = averageHeartrate ?: icuAverageHr ?: averageHr
    val decouplingValue: Double?
        get() = icuDecoupling ?: decoupling

    companion object {
        /**
         * IF kommt je nach Quelle als Bruch (0,72) oder als Prozentwert (72). Entschieden
         * wird je ABRUF, nicht je Wert: eine Einzelwert-Heuristik („> 3 heißt Prozent") läse
         * eine sehr lockere Einheit mit 2,5 % als IF 2,5 — die härteste Einheit des Jahres.
         */
        fun intensityIsPercent(all: List<ActivityDto>): Boolean = all.any { (it.intensity ?: 0.0) > 3 }

        val FIELDS = listOf(
            "id", "type", "trainer", "start_date_local", "moving_time",
            "icu_training_load", "icu_intensity", "icu_zone_times", "icu_eftp", "icu_ftp",
            "icu_weighted_avg_watts", "icu_normalized_watts", "icu_average_watts", "average_watts",
            "average_heartrate", "icu_average_hr", "average_hr",
            "icu_power_hr", "power_hr", "icu_decoupling", "decoupling",
        ).joinToString(",")
    }
}

@Serializable
data class MmpModelDto(val ftp: Double? = null)

@Serializable
data class SportSettingsDto(
    val types: List<String>? = null,
    val type: String? = null,
    val ftp: Double? = null,
    @SerialName("indoor_ftp") val indoorFtp: Double? = null,
    @SerialName("icu_ftp") val icuFtp: Double? = null,
    val eFTPSupported: Boolean? = null,
    @SerialName("mmp_model") val mmpModel: MmpModelDto? = null,
    val lthr: Double? = null,
    @SerialName("max_hr") val maxHr: Double? = null,
)

@Serializable
data class AthleteDto(val id: String? = null, val name: String? = null)

/** Ein Stream-Satz einer Aktivität in der Objektform der API. */
@Serializable
data class StreamDto(val data: List<Double?>? = null)
