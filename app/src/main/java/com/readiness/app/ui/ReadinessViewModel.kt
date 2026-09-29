package com.readiness.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.readiness.app.data.Snapshot
import com.readiness.app.domain.AnalysisConfig
import com.readiness.app.repo.RepoProvider
import com.readiness.app.widget.WidgetUpdater
import com.readiness.app.work.Scheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val snapshot: Snapshot? = null,
    val loading: Boolean = false,
    val filling: Boolean = false,
    val error: String? = null,
    val settings: SettingsState = SettingsState(),
    val cacheKb: Long = 0,
)

data class SettingsState(
    val apiKey: String = "", val athlete: String = "0",
    val cycles: Int = 1, val sleepNeed: String = "",
    val widgetAutoUpdate: Boolean = true,
    /** Damit im Zweifel nachprüfbar ist, welcher Stand tatsächlich läuft. */
    val appVersion: String = "",
    /** Demo-Build mit synthetischen Daten — kein API-Key nötig. */
    val demo: Boolean = false,
)

class ReadinessViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = RepoProvider.get(app)
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var fillJob: Job? = null

    init {
        _state.value = _state.value.copy(snapshot = repo.cached(), settings = readSettings(),
            cacheKb = repo.cacheSizeKb())
        if (repo.settings.apiKey.isNotBlank() || repo.isDemo) refresh()
    }

    private fun readSettings() = with(repo.settings) {
        SettingsState(apiKey, athlete, cycles,
            sleepNeedHours?.let { String.format("%.2f", it).trimEnd('0').trimEnd('.', ',') } ?: "",
            widgetAutoUpdate, appVersion(), repo.isDemo)
    }

    /** Versionsname aus dem Paketmanager — verlässlicher als eine gepflegte Konstante. */
    private fun appVersion(): String = runCatching {
        val ctx = getApplication<Application>()
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        "${info.versionName} (${@Suppress("DEPRECATION") info.versionCode})"
    }.getOrDefault("")

    fun refresh() {
        if (_state.value.loading) return
        fillJob?.cancel()
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (snap, _) = withContext(Dispatchers.IO) { repo.refresh() }
                _state.value = _state.value.copy(snapshot = snap, loading = false,
                    cacheKb = repo.cacheSizeKb())
                WidgetUpdater.update(getApplication())
                startBackgroundFill()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Fehler beim Laden")
            }
        }
    }

    /**
     * Nach dem ersten Anzeigen im Hintergrund weiterladen, bis alle Kraftdaten
     * ausgewertet sind. Ohne das endete das Nachladen beim ersten Bild und der Rest kam
     * erst beim nächsten manuellen Aufruf — praktisch ein „komm später wieder".
     */
    private fun startBackgroundFill() {
        fillJob = viewModelScope.launch {
            _state.value = _state.value.copy(filling = true)
            try {
                /* Schleife mit echtem Abbruch: „return@repeat“ wäre nur ein „continue" und
                   hätte nach vollständigem Laden noch bis zu 40 Auswertungen angestoßen. */
                for (step in 0 until 40) {
                    val (snap, more) = withContext(Dispatchers.IO) { repo.fillTorqueStep() }
                    _state.value = _state.value.copy(snapshot = snap)
                    if (!more) break
                    delay(250)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // Nachladen ist bestes Bemühen — ein Fehler darf die Anzeige nicht kippen
            } finally {
                _state.value = _state.value.copy(filling = false)
                WidgetUpdater.update(getApplication())
            }
        }
    }

    fun saveSettings(s: SettingsState) {
        with(repo.settings) {
            apiKey = s.apiKey; athlete = s.athlete.ifBlank { "0" }
            cycles = s.cycles
            sleepNeedHours = s.sleepNeed.replace(',', '.').toDoubleOrNull()
            widgetAutoUpdate = s.widgetAutoUpdate
        }
        _state.value = _state.value.copy(settings = readSettings())
        Scheduler.scheduleWidgetRefresh(getApplication(), s.widgetAutoUpdate)
        refresh()
    }

    /**
     * Zyklenwechsel: erst die Auswahl sofort sichtbar machen, dann neu auswerten.
     *
     * Der Wechsel ändert nur den Auswertungszeitraum, nicht die Rohdaten. Reicht die
     * bereits geladene Historie tief genug, wird ohne Netzabruf gerechnet — das ist der
     * Unterschied zwischen „spürbar träge" und „sofort".
     */
    fun setCycles(n: Int) {
        if (n == repo.settings.cycles) return
        fillJob?.cancel()
        repo.settings.cycles = n
        _state.value = _state.value.copy(
            settings = _state.value.settings.copy(cycles = n), loading = true, error = null)
        viewModelScope.launch {
            try {
                val (snap, _) = withContext(Dispatchers.IO) { repo.reevaluate() }
                _state.value = _state.value.copy(snapshot = snap, loading = false)
                startBackgroundFill()
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Fehler beim Laden")
            }
        }
    }

    /** Tageseintrag speichern: Störfaktoren und Powernap gemeinsam, dann neu bewerten. */
    fun setDayEntry(date: String, causes: List<String>, napMinutes: Int) {
        val keep = java.time.LocalDate.now()
            .minusDays(AnalysisConfig(repo.settings.cycles).fetchDays.toLong()).toString()
        repo.settings.setConfounder(date, causes, keep)
        repo.settings.setNap(date, napMinutes, keep)
        // Reine Rechenänderung — kein Netzabruf nötig
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val (snap, _) = withContext(Dispatchers.IO) { repo.reevaluate() }
                _state.value = _state.value.copy(snapshot = snap, loading = false)
                WidgetUpdater.update(getApplication())
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Fehler")
            }
        }
    }
}
