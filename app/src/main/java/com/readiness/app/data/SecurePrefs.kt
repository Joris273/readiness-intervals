package com.readiness.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.readiness.app.domain.AnalysisConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Verschlüsselte Einstellungen. Der API-Key liegt AES-verschlüsselt im Android Keystore.
 *
 * Wichtig für die Architektur: Diese Klasse wird NIE aus der Domänenschicht gelesen.
 * Sie erzeugt ein AnalysisConfig-Objekt, das durchgereicht wird — dadurch bleibt die
 * Domäne ohne Android-Abhängigkeit und ohne Emulator testbar.
 */
class SecurePrefs(context: Context) {

    private val prefs: SharedPreferences = run {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context, "readiness_secure", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) { prefs.edit().putString("api_key", v.trim()).apply() }

    var athlete: String
        get() = prefs.getString("athlete", "0") ?: "0"
        set(v) { prefs.edit().putString("athlete", v.ifBlank { "0" }).apply() }

    var cycles: Int
        get() = prefs.getInt("cycles", 1).coerceIn(1, 3)
        set(v) { prefs.edit().putInt("cycles", v.coerceIn(1, 3)).apply() }

    var sleepNeedHours: Double?
        get() = prefs.getFloat("sleep_need", 0f).takeIf { it > 0f }?.toDouble()
        set(v) { prefs.edit().apply { if (v != null && v > 0) putFloat("sleep_need", v.toFloat()) else remove("sleep_need") }.apply() }

    /** Powernap-Minuten je Tag: { "2026-07-27": 25 } */
    private val napSerializer = MapSerializer(String.serializer(), Int.serializer())

    var napByDay: Map<String, Int>
        get() = runCatching {
            AppJson.decodeFromString(napSerializer, prefs.getString("nap_by_day", "{}") ?: "{}")
        }.getOrDefault(emptyMap())
        set(v) { prefs.edit().putString("nap_by_day", AppJson.encodeToString(napSerializer, v)).apply() }

    fun setNap(date: String, minutes: Int, keepFrom: String) {
        val all = napByDay.toMutableMap()
        if (minutes > 0) all[date] = minutes else all.remove(date)
        napByDay = all.filterKeys { it >= keepFrom }
    }

    /** Hintergrund-Aktualisierung des Widgets (Akkuverbrauch bewusst steuerbar). */
    var widgetAutoUpdate: Boolean
        get() = prefs.getBoolean("widget_auto", true)
        set(v) { prefs.edit().putBoolean("widget_auto", v).apply() }

    /**
     * { "2026-07-27": ["alcohol"] } — intervals.icu hat dafür kein Standardfeld.
     *
     * Serializer bewusst EXPLIZIT statt der bequemen einargumentigen Form: diese ist eine
     * reified Erweiterungsfunktion und braucht einen zusätzlichen Import. Fehlt der, löst
     * der Aufruf still auf die zweiargumentige Member-Variante auf und der Compiler
     * meldet einen schwer lesbaren Typfehler statt eines fehlenden Imports.
     */
    private val confSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))

    var confounders: Map<String, List<String>>
        get() = runCatching {
            AppJson.decodeFromString(confSerializer, prefs.getString("confounders", "{}") ?: "{}")
        }.getOrDefault(emptyMap())
        set(v) { prefs.edit().putString("confounders", AppJson.encodeToString(confSerializer, v)).apply() }

    fun setConfounder(date: String, causes: List<String>, keepDaysBefore: String) {
        val all = confounders.toMutableMap()
        if (causes.isEmpty()) all.remove(date) else all[date] = causes
        // Einträge außerhalb des Auswertungsfensters verfallen, sonst wächst der Speicher unbegrenzt
        confounders = all.filterKeys { it >= keepDaysBefore }
    }

    fun config(): AnalysisConfig = AnalysisConfig(
        cycles = cycles,
        sleepNeedHours = sleepNeedHours,
        napMinutesByDay = napByDay,
        confounders = confounders,
    )
}
