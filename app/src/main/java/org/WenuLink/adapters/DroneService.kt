package org.WenuLink.adapters

import android.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import io.getstream.log.taggedLogger
import org.WenuLink.MainActivity
import org.WenuLink.mavlink.MAVLinkService
import org.WenuLink.webrtc.WebRTCService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp
import kotlin.getValue

class DroneService : Service() {

    private lateinit var mavlink: MAVLinkService
    private lateinit var webRTC: WebRTCService
    private var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val logger by taggedLogger("DroneService")

    override fun onCreate() {
        super.onCreate()
        (application as WenuLinkApp).droneService = this // Store the service reference
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
            .setContentTitle("WenuLink service is running")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent) // Open MainActivity when tapped
            .setOngoing(true)
            .build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Initialize MAVLink and WebRTC
        startMAVLink()
        startWebRTC()

        // Check if any services were successfully initialized
        if (!::mavlink.isInitialized && !::webRTC.isInitialized) {
            logger.i { "No service enable to start, stopping." }
            stopSelf() // Stop the service if initialization fails
            return START_NOT_STICKY // Return to indicate that the service should not be recreated
        }

        // Start the foreground service if both services are initialized
        startForegroundServiceWithNotification()

        return START_STICKY // The service will continue running
    }

    override fun onDestroy() {
        stopMAVLink()
        stopWebRTC()
        serviceScope.cancel()
        logger.i { "Service destroyed" }
        (application as WenuLinkApp).droneService = null // Clear the reference
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun startWebRTC() {
        if (!WebRTCService.isEnabled) {
            return  // silently omit
        }

        if (!::webRTC.isInitialized)
            webRTC = WebRTCService.getInstance()

        if (!webRTC.canStartClient()) {
            logger.e { "WebRTC client not ready, check if is enabled and a camera is present." }
            return
        }

        webRTC.startClient(serviceScope, applicationContext)
        webRTC.runProcess(true) // autostart
        logger.i { "WebRTC service started" }
    }

    fun stopWebRTC() {
        if (::webRTC.isInitialized)
//            webRTC.disconnect()
            webRTC.runProcess(false) // autostart
        logger.i { "WebRTC service stop" }
    }

    fun getWebRTCState(): StateFlow<Boolean> = webRTC.isRunning

    fun isWebRTCUp(): Boolean = webRTC.isServiceUp

    fun startMAVLink() {
        if (!MAVLinkService.isEnabled) {
            return  // silently omit
        }

        if (!::mavlink.isInitialized)
            mavlink = MAVLinkService.getInstance()

        if (!mavlink.canStartClient()) {
            logger.e  { "MAVLink client not ready, check if is enabled or if already running." }
            return
        }

        mavlink.createClient(serviceScope)
        mavlink.runProcess(true) // autostart
        logger.i  { "MAVLink service started" }
    }

    fun stopMAVLink () {
        logger.i { "DroneService pre condition" }
        if (::mavlink.isInitialized){
            logger.i { "DroneService inside condition" }
            serviceScope.launch {  mavlink.runProcess(false) }
        }
        logger.i { "MAVLink service stop" }
    }

    fun getMAVLinkState(): StateFlow<Boolean> = mavlink.isRunning

    fun isMAVLinkUp(): Boolean = mavlink.isServiceUp

}