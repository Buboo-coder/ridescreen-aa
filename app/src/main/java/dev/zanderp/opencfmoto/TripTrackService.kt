// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * Keeps the trip recorder running while the screen is off or another app is in front, so a ride
 * from A to B is saved even with no navigation and no map open. Started and stopped by the rider
 * (home tile / notification button); shows an ongoing notification the whole time (Android rule
 * for background location). Saves into the same Saved trips list as every other ride.
 */
class TripTrackService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutDown()
            return START_NOT_STICKY
        }
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation) {
            LogBus.log("[trip] route recording needs location permission")
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = buildNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            LogBus.log("[trip] could not start route recording service: $e")
            stopSelf()
            return START_NOT_STICKY
        }
        running = true
        TripAutoLog.setManualTracking(this, true)
        if (TripLogger.current?.recording != true) {
            LogBus.log("[trip] route recording is on but GPS is not available (turn on Location)")
        } else {
            LogBus.log("[trip] route recording started by rider")
        }
        return START_NOT_STICKY
    }

    private fun shutDown() {
        running = false
        TripAutoLog.setManualTracking(this, false)   // saves the ride unless AA / map still need it
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
        LogBus.log("[trip] route recording stopped by rider")
    }

    override fun onDestroy() {
        if (running) {
            running = false
            TripAutoLog.setManualTracking(this, false)
        }
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.trk_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 11, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 12, Intent(this, TripTrackService::class.java).setAction(ACTION_STOP), flags,
        )
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        return b.setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(getString(R.string.trk_notif_title))
            .setContentText(getString(R.string.trk_notif_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel), getString(R.string.trk_notif_stop), stop).build())
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "ridescreen_route_record"
        private const val NOTIF_ID = 31
        private const val ACTION_STOP = "dev.zanderp.opencfmoto.TRIP_TRACK_STOP"

        @Volatile var running: Boolean = false
            private set

        fun start(ctx: Context) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, TripTrackService::class.java))
        }

        fun stop(ctx: Context) {
            // Tell the service to wind down properly (saves the ride); fall back to stopService.
            try {
                ctx.startService(Intent(ctx, TripTrackService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
                ctx.stopService(Intent(ctx, TripTrackService::class.java))
            }
        }
    }
}
