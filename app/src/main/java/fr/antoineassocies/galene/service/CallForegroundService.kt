package fr.antoineassocies.galene.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fr.antoineassocies.galene.MainActivity
import fr.antoineassocies.galene.R

/**
 * Foreground service that keeps the call alive when the app goes to the background.
 *
 * Since Android 11, an app that is not visible loses microphone access unless it runs a
 * foreground service of type `microphone`. This service does nothing else: it runs no
 * call logic (which stays in the ViewModel), it merely tells the system a call is in
 * progress and shows the matching notification.
 *
 * Android 14+ constraints, honoured by the caller ([start]):
 * - the service must be started while the app is in the foreground;
 * - the RECORD_AUDIO permission must be granted when it starts.
 *
 * The camera is deliberately not covered: it is turned off in the background
 * (saves battery, and nobody is looking at the preview).
 */
class CallForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "galene_call"
        private const val NOTIFICATION_ID = 1

        /** Starts the service. Call it while the app is visible and the microphone allowed. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
        }

        /** Stops the service; no effect if it is not running. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit type: without it, Android 14 checks every type declared in the manifest
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // No automatic restart: a call does not resume by itself after the process dies
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** The user swiped the app away from recent tasks: the call ends with it. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_call),
            NotificationManager.IMPORTANCE_LOW,     // no sound, no pop-up: just a reminder
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Tapping the notification goes back to the call (the existing Activity, not a new one)
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_call_ongoing))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .build()
    }
}
