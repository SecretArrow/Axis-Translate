package com.axis.translate.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.axis.translate.AxisApp
import com.axis.translate.MainActivity
import com.axis.translate.R
import com.axis.translate.di.AppContainer
import com.axis.translate.domain.BatchQueue
import com.axis.translate.domain.model.BatchState
import com.axis.translate.domain.model.BatchTask
import com.axis.translate.domain.model.HistoryItem
import com.axis.translate.domain.model.InputType
import com.axis.translate.domain.model.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground (dataSync) service that drives the shared [BatchQueue]
 * (SPEC #64): runs pending batch tasks sequentially and mirrors progress
 * into a low-importance notification. Only this runner coroutine is tied to
 * the service lifetime — the queue and its item states survive service
 * destruction so the UI keeps a consistent view.
 */
class BatchTranslationService : Service() {

    private lateinit var queue: BatchQueue
    private lateinit var container: AppContainer
    private lateinit var scope: CoroutineScope
    private var runJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                NOTIFICATION_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            )
        )
        queue = BatchQueue.shared()
        container = (application as AxisApp).container
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            service = this,
            id = NOTIFICATION_ID,
            notification = buildNotification(text = "Starting batch translation…", done = 0, total = 0),
            foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        if (runJob?.isActive == true) {
            // Already running — the foreground state above simply refreshed
            // the notification.
            return START_NOT_STICKY
        }

        if (queue.items.value.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        runJob = scope.launch {
            val progressJob = launch {
                queue.items.collect { items ->
                    val size = items.size
                    if (size > 0 && queue.isRunning.value) {
                        val done = items.count {
                            it.state != BatchState.PENDING && it.state != BatchState.RUNNING
                        }
                        notifyProgress(
                            text = "Translating… $done/$size",
                            done = done,
                            total = size
                        )
                    }
                }
            }
            val message = try {
                val result = queue.runAll { task -> translateAndRecord(task) }
                if (result.isSuccess) {
                    "Batch complete: ${result.getOrDefault(0)} done"
                } else {
                    // The shared queue is already being driven elsewhere.
                    "Batch translation already in progress"
                }
            } catch (ce: CancellationException) {
                "Batch cancelled"
            }
            progressJob.cancel()
            finish(message)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Cancel only the runner coroutine; the shared queue and its item
        // states are deliberately left untouched.
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private suspend fun translateAndRecord(task: BatchTask): String {
        val translation = container.translationManager.translate(
            TranslationRequest(
                text = task.sourceText,
                source = task.source,
                target = task.target,
                inputType = InputType.BATCH
            )
        )
        // History persistence is best-effort: a database failure must not
        // fail the batch item whose translation actually succeeded.
        try {
            container.historyRepository.add(
                HistoryItem(
                    sourceCode = task.source.code,
                    targetCode = task.target.code,
                    sourceText = task.sourceText,
                    translatedText = translation.translatedText,
                    inputType = InputType.BATCH,
                    durationMs = translation.durationMs,
                    detectedLanguageCode = translation.detectedLanguage?.code
                )
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Ignored deliberately.
        }
        return translation.translatedText
    }

    private fun notifyProgress(text: String, done: Int, total: Int) {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.notify(NOTIFICATION_ID, buildNotification(text = text, done = done, total = total))
    }

    /** Posts the final, non-ongoing notification and stops the service. */
    private fun finish(message: String) {
        // Detach (rather than remove) so the completion notification survives
        // the service being destroyed.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            NOTIFICATION_ID,
            buildNotification(text = message, done = 0, total = 0, ongoing = false)
        )
        stopSelf()
    }

    private fun buildNotification(text: String, done: Int, total: Int, ongoing: Boolean = true): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Axis Translate")
            .setContentText(text)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(mainActivityIntent())
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (ongoing) {
            builder.setProgress(total, done, total == 0)
        }
        return builder.build()
    }

    private fun mainActivityIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    companion object {
        const val CHANNEL_ID = "axis_batch"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFICATION_CHANNEL_NAME = "Batch translation"

        /** Starts the foreground batch runner. Safe to call repeatedly. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BatchTranslationService::class.java)
            )
        }
    }
}
