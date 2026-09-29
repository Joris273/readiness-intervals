package com.readiness.app.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * Persistenter Zwischenspeicher der Rohdaten.
 *
 * Ohne ihn beginnt jeder App-Start und jeder Wechsel des Vergleichszeitraums mit einem
 * vollständigen Netzabruf über bis zu 288 Tage. Auf einem Gerät mit schnellem, reichlich
 * vorhandenem Speicher ist das die falsche Sparsamkeit: ein paar hundert Kilobyte auf der
 * Platte sind billiger als Sekunden Wartezeit bei jeder Bedienung.
 *
 * Der Speicher ist bewusst großzügig bemessen — er hält die volle Abruftiefe vor, nicht
 * nur den gerade ausgewerteten Ausschnitt.
 */
@Serializable
data class RawBundle(
    val day: String,
    val savedAt: Long,
    val fetchDays: Int,
    val wellness: List<WellnessDto> = emptyList(),
    val activities: List<ActivityDto> = emptyList(),
    val sportSettings: List<SportSettingsDto> = emptyList(),
    val athleteName: String? = null,
)

class RawStore(context: Context) {
    private val file = File(context.filesDir, "raw.json")

    fun load(): RawBundle? = AppLog.attempt("Rohdaten-Cache lesen") {
        if (file.exists()) AppJson.decodeFromString(RawBundle.serializer(), file.readText()) else null
    }.getOrNull()

    fun save(b: RawBundle) {
        AppLog.attempt("Rohdaten-Cache speichern") { AtomicWrite.write(file, AppJson.encodeToString(RawBundle.serializer(), b)) }
    }

    /** Größe des Zwischenspeichers in KB — für die Anzeige in den Einstellungen. */
    fun sizeKb(): Long = if (file.exists()) file.length() / 1024 else 0
}
