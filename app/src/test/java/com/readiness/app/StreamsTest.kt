package com.readiness.app

import com.readiness.app.domain.Streams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roh-Streams: Abtastrate, Pausen, Artefakte, Mittelung über gemessene Sekunden. */
class StreamsTest {

    private fun stream(n: Int, step: Int, f: (Int) -> Triple<Double, Double, Double>): Streams.Normalized? {
        val w = ArrayList<Double?>(); val c = ArrayList<Double?>(); val h = ArrayList<Double?>(); val t = ArrayList<Double?>()
        for (i in 0 until n) { val (a, b, cc) = f(i * step); w += a; c += b; h += cc; t += (i * step).toDouble() }
        return Streams.normalize(w, c, h, t)
    }

    private val block: (Int) -> Triple<Double, Double, Double> =
        { i -> if (i in 601..1199) Triple(270.0, 60.0, 165.0) else Triple(150.0, 90.0, 140.0) }

    @Test fun einHertz() {
        val s = stream(3600, 1, block)!!
        assertEquals(270, Streams.metrics(s).d300)
        assertFalse(s.resampled)
    }

    @Test fun vierSekundenRaster() {
        val s = stream(900, 4, block)!!
        assertTrue(s.resampled)
        assertEquals(3597, s.n)
        assertEquals(270, Streams.metrics(s).d300)
    }

    /* Jede 20. Sekunde ist ein Artefakt und damit „nicht gemessen“. Über die Fensterlänge
       gemittelt ergäbe das 285 W statt der gefahrenen 300. */
    @Test fun lueckenWerdenUeberGemesseneSekundenGemittelt() {
        val s = stream(600, 1) { i -> Triple(if (i % 20 == 0) 3000.0 else 300.0, 60.0, 150.0) }!!
        assertEquals(300, Streams.metrics(s).d300)
    }

    @Test fun artefakteWerdenVerworfen() {
        val s = stream(600, 1) { i -> Triple(if (i == 300) 3000.0 else 200.0, if (i == 301) 250.0 else 80.0, 150.0) }!!
        assertTrue(s.watts[300].isNaN())
        assertTrue(s.cadence[301].isNaN())
    }

    @Test fun ungleicheLaengenUndNullInDerZeitachse() {
        val w = List<Double?>(700) { 250.0 }
        val c = List<Double?>(650) { 65.0 }
        val t = (0 until 700).map<Int, Double?> { if (it == 350) null else it.toDouble() }
        val s = Streams.normalize(w, c, null, t)
        assertNotNull(s)
        Streams.metrics(s!!)   // darf nicht werfen
    }
}
