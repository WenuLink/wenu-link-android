package org.WenuLink.adapters

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import org.WenuLink.MainActivity
import org.WenuLink.mavlink.MAVLinkService
import org.WenuLink.webrtc.WebRTCService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class DroneService : Service() {

    companion object {
        fun start(context: Context) {
            val startFunction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context::startForegroundService
            } else {
                context::startService
            }
            startFunction(Intent(context, DroneService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DroneService::class.java))
        }
    }

    private val TAG: String = DroneService::class.java.simpleName
    private lateinit var mavlink: MAVLinkService
    private lateinit var webRTC: WebRTCService
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceWithNotification()
        startMAVLink()
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "MAVLINK_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "MAVLink Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val notification: Notification.Builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            Notification.Builder(this)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            MainActivity.getIntent(applicationContext),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        var contentText = "No service enabled yet"
        if (::mavlink.isInitialized)
            contentText = "Sending periodic heartbeats to GCS\n"
        if (::webRTC.isInitialized)
           contentText += "WebRTC streaming: ${webRTC.mediaOptions.VIDEO_CAMERA_NAME}"
       // .setContentText(webRTC.mediaOptions?.VIDEO_CAMERA_NAME)
        // TODO: update according to each present service
        startForeground(1, notification
            .setContentTitle("MAVLinkPoli is running")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent) // Open MainActivity when tapped
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startWebRTC()
        if (!::mavlink.isInitialized && !::webRTC.isInitialized) {
            Log.i(TAG, "Unable to start service, MAVLink and WebRTC are disabled.")
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMAVLink()
        stopWebRTC()
        serviceScope.cancel()
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWebRTC() {
        if (!WebRTCService.isEnabled) {
            return  // silently omit
        }
        webRTC = WebRTCService.getInstance()

        if (!webRTC.canStartClient()) {
            Log.e(TAG, "WebRTC client not ready, check if is enabled and a camera is present.")
            return
        }

        webRTC.startClient(serviceScope, applicationContext)
        WebRTCService.runProcess(true) // autostart
        Log.i(TAG, "WebRTC service started")
    }

    private fun stopWebRTC() {
        if (::webRTC.isInitialized)
            webRTC.disconnect()
        Log.i(TAG, "WebRTC service stop")
    }

    private fun startMAVLink() {
        if (!MAVLinkService.isEnabled) {
            return  // silently omit
        }
        mavlink = MAVLinkService.getInstance()

        if (!mavlink.canStartClient()) {
            Log.e(TAG, "MAVLink client not ready, check if is enabled.")
            return
        }

        mavlink.startClient(serviceScope)
        MAVLinkService.runProcess(true) // autostart
        Log.i(TAG, "MAVLink service started")
    }

    private fun stopMAVLink () {
        if (::mavlink.isInitialized)
            mavlink.stop()
        Log.i(TAG, "MAVLink service stop")
    }
}