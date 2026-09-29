package com.readiness.app

import com.readiness.app.data.AtomicWrite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Atomares Schreiben: ein Leser sieht immer einen vollständigen Stand. */
class AtomicWriteTest {

    private fun tempDir(): File = Files.createTempDirectory("atomic").toFile().also { it.deleteOnExit() }

    @Test fun ersetztVollstaendigUndHinterlaesstKeineTempDatei() {
        val dir = tempDir()
        val f = File(dir, "torque.json")
        AtomicWrite.write(f, "{\"v\":1}")
        AtomicWrite.write(f, "{\"v\":2}")
        assertEquals("{\"v\":2}", f.readText())
        assertFalse(File(dir, "torque.json.tmp").exists())
    }

    /* Simulierter Abbruch: ein Schreiber stirbt, nachdem die Temp-Datei halb geschrieben
       ist. Die Zieldatei muss den alten, vollständigen Inhalt behalten — mit writeText
       wäre sie in diesem Moment bereits gekürzt gewesen. */
    @Test fun abgebrochenerSchreibvorgangLaesstAltenInhaltLesbar() {
        val dir = tempDir()
        val f = File(dir, "torque.json")
        AtomicWrite.write(f, "{\"v\":1,\"d\":{}}")
        File(dir, "torque.json.tmp").writeText("{\"v\":2,\"d\":{\"a1\":")
        assertEquals("{\"v\":1,\"d\":{}}", f.readText())
        // der nächste reguläre Schreibvorgang räumt die Leiche auf
        AtomicWrite.write(f, "{\"v\":3}")
        assertEquals("{\"v\":3}", f.readText())
        assertFalse(File(dir, "torque.json.tmp").exists())
    }
}
