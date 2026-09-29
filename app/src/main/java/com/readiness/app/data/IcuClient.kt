package com.readiness.app.data

import android.util.Base64
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Reiner HTTP-Zugriff auf die intervals.icu-API. Kennt keine Domänenlogik und keine
 * Konfiguration außer den Zugangsdaten.
 */
class IcuClient(private val credentials: () -> Pair<String, String>) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun get(path: String): String {
        val (apiKey, _) = credentials()
        val auth = "Basic " + Base64.encodeToString("API_KEY:$apiKey".toByteArray(), Base64.NO_WRAP)
        val req = Request.Builder().url(BASE + path).header("Authorization", auth).build()
        http.newCall(req).execute().use { res ->
            if (!res.isSuccessful) throw IcuException(res.code, "HTTP ${res.code} bei $path")
            return res.body?.string() ?: "null"
        }
    }

    private fun athlete() = credentials().second

    fun wellness(oldest: String, newest: String): List<WellnessDto> =
        AppJson.decodeFromString(ListSerializer(WellnessDto.serializer()),
            get("/athlete/${athlete()}/wellness?oldest=$oldest&newest=$newest"))

    fun activities(oldest: String, newest: String, fields: String? = ActivityDto.FIELDS): List<ActivityDto> {
        val f = if (fields != null) "&fields=$fields" else ""
        return AppJson.decodeFromString(ListSerializer(ActivityDto.serializer()),
            get("/athlete/${athlete()}/activities?oldest=$oldest&newest=$newest$f"))
    }

    fun sportSettings(): List<SportSettingsDto> =
        AppLog.attempt("Sport-Einstellungen abrufen") {
            AppJson.decodeFromString(ListSerializer(SportSettingsDto.serializer()),
                get("/athlete/${athlete()}/sport-settings"))
        }.getOrDefault(emptyList())

    fun profile(): AthleteDto? =
        AppLog.attempt("Profil abrufen") { AppJson.decodeFromString(AthleteDto.serializer(), get("/athlete/${athlete()}")) }.getOrNull()

    /** Roh-Streams inklusive Zeitachse — nötig, um die Abtastrate zu prüfen. */
    fun streams(activityId: String, types: String = "watts,cadence,heartrate,time"): Map<String, List<Double?>> {
        val body = get("/activity/$activityId/streams.json?types=$types")
        val el = AppJson.parseToJsonElement(body)
        val out = HashMap<String, List<Double?>>()
        fun readData(o: JsonObject): List<Double?>? =
            (o["data"] as? JsonArray)?.map { it.jsonPrimitive.content.toDoubleOrNull() }
        when (el) {
            is JsonArray -> el.forEach { e ->
                val o = e.jsonObject
                val t = o["type"]?.jsonPrimitive?.content ?: return@forEach
                readData(o)?.let { out[t] = it }
            }
            is JsonObject -> el.forEach { (k, v) -> (v as? JsonObject)?.let { readData(it)?.let { d -> out[k] = d } } }
            else -> {}
        }
        return out
    }

    companion object { const val BASE = "https://intervals.icu/api/v1" }
}

class IcuException(val code: Int, message: String) : RuntimeException(message)
