package com.readiness.app.domain

/**
 * VERSUCH ODER NUR EXPOSITION?
 *
 * Alle Kraftmarker der App sind MAXIMA über ein Zeitfenster. Ein Maximum misst
 * Leistungsfähigkeit aber nur dann, wenn im Fenster auch ein maximaler Versuch
 * stattgefunden hat. Wer sechs Wochen lang 5×5 min und 3×10 min fährt, hat als beste
 * Minute nur die schnellste Minute INNERHALB eines eingeteilten 5-min-Intervalls —
 * typisch 3–8 % über der 5-min-Leistung, während eine echte 1-min-Maximalleistung
 * 15–35 % darüber liegt. Ohne diese Unterscheidung meldet die App einen Kraftverlust von
 * über 20 %, obwohl sich nur der Trainingsinhalt geändert hat.
 *
 * Der bisherige Wächter verschärfte das Problem, statt es zu fangen: er zählte
 * überlappungsfreie 60-s-Fenster je Einheit — also EXPOSITION. Eine einzige
 * 5×5-min-Einheit liefert davon rund 25. Gerade der Block ohne jeden 1-min-Antritt
 * bekam so die höchste vermeintliche Sicherheit.
 *
 * Hier wird stattdessen je Einheit gefragt, auf WELCHE Dauer sie gezielt hat.
 *
 * PROFILVERHÄLTNIS. Das Verhältnis benachbarter Bestleistungen verrät den Zuschnitt einer
 * Einheit, ohne irgendetwas über den Formzustand vorauszusetzen:
 *
 *     r1 = d60 / d300     bei echten Maxima 1,15–1,35
 *     r2 = d300 / d600    bei echten Maxima 1,05–1,15
 *
 * Bewusst NICHT gegen die eigene Historie normiert. Ein Test der Art „liegt der Wert nahe
 * am bisherigen Bestwert?" wäre zirkulär: ein tatsächlich schwächer gewordener Athlet
 * erreichte seinen alten Bestwert nie wieder, sein Rückgang bliebe für immer unsichtbar.
 *
 * HF-AUSBELASTUNG. Für die längeren Dauern kommt ein zweiter, unabhängiger Beleg hinzu:
 * ein maximaler Effort endet nahe der eigenen Herzfrequenzdecke. `efficiencyHr` ist die
 * mittlere HF der zweiten Hälfte des besten Niedrigfrequenz-5-min-Fensters und liegt
 * bereits im Cache. Die HF-Decke ist ein STABILER physiologischer Anker — sie driftet
 * nicht mit der Form. Der Test ist damit zwar historienbezogen, aber nicht zirkulär
 * gegenüber der Leistung: wer ausbelastet und trotzdem langsamer ist, wird korrekt als
 * „hat getestet, ist schwächer" gewertet.
 *
 * Für 60 s gibt es keinen HF-Beleg: die Herzfrequenz hinkt über eine Minute zu weit nach,
 * um Ausbelastung zu zeigen. Dort ist das Profilverhältnis das einzige Werkzeug — und
 * zugleich das treffsicherste, weil es genau den gemeldeten Fall trennt.
 */
object EffortQuality {

    /*
     * Stellknöpfe (in [ScoringParams.EffortParams]):
     *  r1Min  — d60 hebt sich klar über das 5-min-Tempo ab, sonst war es nur dessen schnellste Minute.
     *  r2Min  — d300 hebt sich über das 10-min-Tempo ab.
     *  r2Max  — liegt d300 WEIT über d600, war das beste 10-min-Fenster durch Pausen verwässert.
     *  hrNearMax — Anteil der eigenen HF-Decke, ab dem ein Effort als ausbelastet gilt. Bewusst
     *    hoch, weil `efficiencyHr` bereits eine ÜBER FÜNF MINUTEN GEHALTENE Herzfrequenz ist:
     *    die Decke entspricht schon einem maximalen 5-min-Antritt. 93 % davon wären rund
     *    zwölf Schläge darunter und damit ein Tempo-, kein Maximalversuch.
     */

    /**
     * Ausbelastungs-HF-Decke über die geladene Historie. Die Decke driftet nicht mit der
     * Form, deshalb darf sie aus der Historie kommen — aber nicht als reines Maximum: ein
     * einziger Artefaktwert (optischer Sensor rastet auf die Trittfrequenz ein) hob sie
     * dauerhaft an und schaltete den HF-Test still ab, weil danach keine Einheit mehr
     * „nahe der Decke" lag. Deshalb physiologisch plausible Werte und ein hohes Perzentil.
     */
    fun hrCeiling(sessions: List<Session>, p: ScoringParams = ScoringParams.DEFAULT): Double? {
        val e = p.effort
        val hrs = sessions.mapNotNull { it.torque?.efficiencyHr?.toDouble() }.filter { it in e.hrMin..e.hrMax }
        return if (hrs.size < 5) hrs.maxOrNull() else Stats.percentile(hrs, e.hrCeilingPercentile)
    }

    private fun nearHrCeiling(t: TorqueMetrics, ceiling: Double?, p: ScoringParams): Boolean {
        if (ceiling == null || ceiling <= 0) return false
        val hr = t.efficiencyHr?.toDouble() ?: return false
        return hr >= p.effort.hrNearMax * ceiling
    }

    /** Wurde in dieser Einheit ein maximaler Versuch über die gegebene Dauer gefahren? */
    fun isAttempt(t: TorqueMetrics, key: DurationKey, hrCeiling: Double?,
                  p: ScoringParams = ScoringParams.DEFAULT): Boolean {
        val d60 = t.d60?.toDouble()
        val d300 = t.d300?.toDouble()
        val d600 = t.d600?.toDouble()
        val e = p.effort
        return when (key) {
            DurationKey.D60 -> when {
                d60 == null -> false
                d300 != null && d300 > 0 -> d60 / d300 >= e.r1Min
                else -> nearHrCeiling(t, hrCeiling, p)      // ohne 5-min-Bezug bleibt nur die HF
            }
            DurationKey.D300 -> when {
                d300 == null -> false
                d600 != null && d600 > 0 && d300 / d600 >= e.r2Min -> true
                else -> nearHrCeiling(t, hrCeiling, p)
            }
            DurationKey.D600 -> when {
                d600 == null -> false
                d300 != null && d600 > 0 && d300 / d600 <= e.r2Max -> true
                else -> nearHrCeiling(t, hrCeiling, p)
            }
        }
    }

    /**
     * Konnte im Fenster überhaupt ein eFTP-setzender Antritt stattgefunden haben?
     * intervals.icu hebt eFTP nur bei maximalen Antritten an und lässt ihn sonst
     * zerfallen — dieselbe Maximum-Falle wie bei den Kraftdauern.
     */
    fun canSetEftp(s: Session, hrCeiling: Double?, p: ScoringParams = ScoringParams.DEFAULT): Boolean {
        if ((s.intensity ?: 0.0) >= 0.85) return true
        val t = s.torque ?: return false
        return isAttempt(t, DurationKey.D300, hrCeiling, p) || isAttempt(t, DurationKey.D600, hrCeiling, p)
    }
}
