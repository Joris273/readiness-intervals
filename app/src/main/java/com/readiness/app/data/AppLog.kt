package com.readiness.app.data

import android.util.Log

/**
 * Schmale Protokollierung für Daten- und Repository-Schicht.
 *
 * Bisher verschluckte jedes `runCatching {}.getOrDefault` seinen Fehler spurlos: ein
 * kaputter Cache, ein abgelehnter API-Schlüssel oder ein fehlgeschlagener Stream-Abruf
 * waren von „keine Daten" nicht zu unterscheiden. Das Verhalten bleibt tolerant, aber
 * jeder geschluckte Fehler hinterlässt eine Zeile in logcat.
 */
object AppLog {
    private const val TAG = "Readiness"

    fun w(msg: String, t: Throwable? = null) {
        runCatching { Log.w(TAG, msg, t) }
    }

    /** `runCatching` mit Protokollzeile im Fehlerfall. */
    inline fun <T> attempt(what: String, block: () -> T): Result<T> =
        runCatching(block).onFailure { w("$what fehlgeschlagen: ${it.message}", it) }
}
