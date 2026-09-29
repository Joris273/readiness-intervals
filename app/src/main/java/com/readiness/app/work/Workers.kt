package com.readiness.app.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.readiness.app.R
import com.readiness.app.data.AppLog
import com.readiness.app.repo.RepoProvider
import com.readiness.app.widget.WidgetUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Morgendlicher Lauf: Daten holen, bewerten, Widget aktualisieren, benachrichtigen.
 *
 * Zusätzlich werden die Kraft-Streams hier vollständig nachgeladen — auf Android der
 * eigentliche Gewinn gegenüber dem Browser-Prototyp: Der Nutzer sieht morgens fertige
 * Daten, ohne je eine Ladeanzeige erlebt zu haben.
 */
class DailyWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = RepoProvider.get(ctx)
            if (repo.settings.apiKey.isBlank()) return@withContext Result.success()

            val (snap, _) = repo.refresh()
            // Kraftdaten in Blöcken vervollständigen; begrenzt, damit der Job endlich bleibt
            var more = true
            var guard = 0
            while (more && guard++ < 40) {
                val (_, m) = repo.fillTorqueStep()
                more = m
                if (more) delay(200)
            }
            WidgetUpdater.update(ctx)
            if (inputData.getBoolean(KEY_NOTIFY, true)) notify(snap.score, snap.recoTitle)
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppLog.w("Morgenlauf fehlgeschlagen (Versuch $runAttemptCount)", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private fun notify(score: Int?, title: String) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, ctx.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT))
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_score)
            .setContentTitle("Zustandsscore ${score ?: "–"}/100")
            .setContentText(title).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(ctx).notify(1001, n) }
    }

    companion object {
        const val CHANNEL = "daily_score"
        const val KEY_NOTIFY = "notify"
    }
}

/**
 * Schlanke Aktualisierung nur für die Widgets.
 *
 * Zum Akkuverbrauch, weil das die eigentliche Frage ist: Dieser Lauf hält KEINEN
 * dauerhaften Wakelock und weckt das Gerät nicht zusätzlich auf. WorkManager reiht die
 * Aufgabe beim System ein, das sie in ohnehin stattfindende Wachphasen legt und im
 * Doze-Modus bis zum nächsten Wartungsfenster zurückstellt. Die Bedingungen „Netz
 * vorhanden" und „Akku nicht schwach" verhindern Läufe genau dann, wenn sie teuer wären.
 *
 * Der Aufwand je Lauf sind drei bis vier HTTP-Anfragen und wenige Millisekunden Rechnung;
 * Kraft-Streams werden bewusst NICHT nachgeladen, die bleiben dem nächtlichen Lauf
 * vorbehalten. Bei vier Läufen am Tag liegt das im Bereich einer einzelnen
 * E-Mail-Synchronisation.
 */
class WidgetRefreshWorker(private val ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val repo = RepoProvider.get(ctx)
            if (repo.settings.apiKey.isBlank() || !repo.settings.widgetAutoUpdate) return@withContext Result.success()
            repo.refreshLight()
            WidgetUpdater.update(ctx)
            Result.success()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            AppLog.w("Widget-Aktualisierung fehlgeschlagen (Versuch $runAttemptCount)", e)
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

object Scheduler {

    /** Täglich gegen 5:30 Ortszeit, nur mit Netz — vor dem üblichen Wecken. */
    fun scheduleDaily(context: Context) {
        val now = LocalDateTime.now()
        var next = now.withHour(5).withMinute(30).withSecond(0).withNano(0)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = ChronoUnit.MINUTES.between(now, next)
        val req = PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .setInputData(workDataOf(DailyWorker.KEY_NOTIFY to true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily_score", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    /** Widget-Aktualisierung alle sechs Stunden, abschaltbar. */
    fun scheduleWidgetRefresh(context: Context, enabled: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) { wm.cancelUniqueWork("widget_refresh"); return }
        val req = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build())
            .setInitialDelay(30, TimeUnit.MINUTES)
            .build()
        wm.enqueueUniquePeriodicWork("widget_refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun refreshNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<DailyWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(DailyWorker.KEY_NOTIFY to false)).build()
        WorkManager.getInstance(context).enqueueUniqueWork("refresh_now", ExistingWorkPolicy.REPLACE, req)
    }
}
