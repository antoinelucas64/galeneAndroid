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
 * Service de premier plan qui maintient l'appel actif quand l'app passe en arrière-plan.
 *
 * Depuis Android 11, une app non visible perd l'accès au micro, sauf si elle fait tourner
 * un service de premier plan de type `microphone`. Ce service ne fait rien d'autre :
 * il n'exécute aucune logique d'appel (qui reste dans le ViewModel), il signale simplement
 * au système qu'un appel est en cours et affiche la notification correspondante.
 *
 * Contraintes Android 14+ respectées par l'appelant ([start]) :
 * - le service doit être démarré pendant que l'app est au premier plan ;
 * - la permission RECORD_AUDIO doit être accordée au moment du démarrage.
 *
 * La caméra n'est volontairement pas couverte : elle est coupée en arrière-plan
 * (économie de batterie, et personne ne regarde l'aperçu).
 */
class CallForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "galene_call"
        private const val NOTIFICATION_ID = 1

        /** Démarre le service. À appeler app visible et micro autorisé. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, CallForegroundService::class.java))
        }

        /** Arrête le service ; sans effet s'il ne tourne pas. */
        fun stop(context: Context) {
            context.stopService(Intent(context, CallForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Type explicite : sans lui, Android 14 vérifie tous les types déclarés au manifeste
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        // Pas de redémarrage automatique : un appel ne se reprend pas tout seul après la mort du process
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** L'utilisateur a balayé l'app depuis les tâches récentes : l'appel s'arrête avec elle. */
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
            NotificationManager.IMPORTANCE_LOW,     // pas de son ni de pop-up : simple rappel
        ).apply {
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        // Toucher la notification ramène dans l'appel (l'Activity existante, pas une nouvelle)
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
