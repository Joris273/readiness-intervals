package com.readiness.app.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Atomares Schreiben: erst in eine Nachbardatei, dann per Umbenennen ersetzen.
 *
 * `File.writeText` kürzt die Zieldatei zuerst und schreibt dann. Bricht der Vorgang
 * dazwischen ab — Prozess beendet, Akku leer, zweiter Schreiber —, bleibt eine halbe
 * JSON-Datei zurück. Beim nächsten Lesen scheitert das Parsen, und der Kraft-Cache wurde
 * bisher stillschweigend als leer behandelt: alle Streams mussten neu geladen werden, und
 * bis dahin fehlten Maximalversuche im Urteil. Mit dem Umbenennen sieht ein Leser immer
 * entweder den alten oder den neuen vollständigen Inhalt.
 */
object AtomicWrite {

    fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.flush()
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
