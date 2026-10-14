package com.jashith.stride.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.jashith.stride.MainActivity
import com.jashith.stride.R
import com.jashith.stride.StrideApplication
import com.jashith.stride.core.model.Fmt
import com.jashith.stride.core.recording.RecordingState
import com.jashith.stride.core.recording.RecordingStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the GPS delivering while the screen is off. It holds no recording state of
 * its own: the notification just mirrors [RecordingController.stats].
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var promoted = false
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = (application as StrideApplication).graph.recorder
        when (intent?.action) {
            ACTION_TOGGLE_PAUSE -> controller.togglePause()
        }

        ensureChannel()
        if (!promoted) {
            // startForeground has to follow startForegroundService within a few seconds, so do it first.
            val notification = build(controller.stats.value)
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            promoted = true
            acquireWakeLock()
            scope.launch {
                controller.stats.collect { stats ->
                    val live = stats.state.isActive || stats.state == RecordingState.Countdown
                    if (!live) {
                        stopSelf()
                    } else {
                        update(stats)
                    }
                }
            }
        }
        // A killed process has no recording to resume; recovery happens from the file when the app next opens.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    private fun update(stats: RecordingStats) {
        val text = text(stats)
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(stats))
    }

    private fun text(stats: RecordingStats): String {
        val settings = (application as StrideApplication).graph.store.current.settings
        val distance = Fmt.distanceWithUnit(stats.distance, settings.units)
        val time = Fmt.duration(stats.elapsed)
        val prefix = when (stats.state) {
            RecordingState.Paused, RecordingState.AutoPaused -> "Paused · "
            RecordingState.Countdown -> "Starting · "
            else -> ""
        }
        return "$prefix$time · $distance"
    }

    private fun build(stats: RecordingStats): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            this, 1,
            Intent(this, RecordingService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = stats.state.isPaused
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_stride)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text(stats))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, if (paused) "Resume" else "Pause", toggle)
            .build()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.recording_channel_description) }
        manager.createNotificationChannel(channel)
    }

    private fun acquireWakeLock() {
        val power = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Stride:recording").apply {
            setReferenceCounted(false)
            // A very long ride is fine, but a lock that outlives a forgotten recording should not drain the battery forever.
            acquire(WAKE_LOCK_LIMIT_MILLIS)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    companion object {
        const val CHANNEL_ID = "recording"
        const val NOTIFICATION_ID = 1
        const val ACTION_TOGGLE_PAUSE = "com.jashith.stride.TOGGLE_PAUSE"
        private const val WAKE_LOCK_LIMIT_MILLIS = 24L * 60 * 60 * 1000
    }
}
