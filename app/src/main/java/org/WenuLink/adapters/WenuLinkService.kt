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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.WenuLink.WenuLinkApp
import kotlin.getValue

class WenuLinkService : Service() {

    private val logger by taggedLogger("WenuLinkService")
    private var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mavlink: MAVLinkService
    private lateinit var webRTC: WebRTCService

    override fun onCreate() {
        super.onCreate()
        (application as WenuLinkApp).wenuLinkService = this // Store the service reference
        // create WebRTC instance
        if (WebRTCService.isEnabled && !isWebRTCReady())
            webRTC = WebRTCService.getInstance()// create MAVLink instance if must

        if (MAVLinkService.isEnabled && !isMAVLinkReady())
            mavlink = MAVLinkService()

        logger.i { "WenuLinkService created." }
    }

    private fun startForegroundServiceWithNotification() {
        val channelId = "MAVLINK_CHANNEL"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "WenuLink Service",
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
        // Check if any services were successfully initialized
        if (!::mavlink.isInitialized && !::webRTC.isInitialized) {
            logger.i { "Unable to start, no services enabled." }
            stopSelf() // Stop the service if initialization fails
            return START_NOT_STICKY // Return to indicate that the service should not be recreated
        }

        // Start the foreground service if both services are initialized
        startForegroundServiceWithNotification()

        startMAVLinkService()
        startWebRTCService()

        return START_STICKY // The service will continue running
    }

    override fun onDestroy() {
        (application as WenuLinkApp).wenuLinkService = null // Clear the reference
        serviceScope.cancel()
        logger.i { "WenuLinkService ended." }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startWebRTC() {
        if (!WebRTCService.isEnabled) {
            logger.i { "Unable to start WebRTC, the service is not enabled." }
            return
        }

        if (!webRTC.canStartClient()) {
            logger.e { "WebRTC client not ready, check if is enabled and a camera is present." }
            return
        }

        webRTC.startClient(serviceScope, applicationContext)
        webRTC.runProcess(true) // autostart
        logger.i { "WebRTC service started" }
    }

    fun startWebRTCService() {
        serviceScope.launch { startWebRTC() }
    }

    fun isWebRTCReady() = ::webRTC.isInitialized

    fun stopWebRTCService() {
        if (!isWebRTCReady()) return
        serviceScope.launch { webRTC.runProcess(false) }
        logger.i { "WebRTC service stop." }
    }

    fun getWebRTCState(): StateFlow<Boolean>? = if (isWebRTCReady()) webRTC.isRunning else null

    fun isWebRTCUp(): Boolean = if (isWebRTCReady()) webRTC.isServiceUp else false

    fun isMAVLinkReady() = ::mavlink.isInitialized

    fun startMAVLinkService(): Job? {
        if (!MAVLinkService.isEnabled) {
            logger.i { "Unable to start MAVLink, the service is not enabled." }
            return null
        }

        if (mavlink.clientIsRunning()) return null

        logger.d { "Start MAVLinkService protocol." }
        return serviceScope.launch {
            mavlink.createClient(serviceScope)
            mavlink.runProcess(true)
            AsyncUtils.waitReady(isReady = mavlink::isServiceRunning)
            logger.i { "MAVLinkService started: ${mavlink.isServiceRunning()}" }
        }
    }

    fun stopMAVLinkService (): Job? {
        if (!isMAVLinkReady()) return null

        logger.d { "Stop MAVLinkService protocol." }
        return serviceScope.launch {
            mavlink.runProcess(false)
            AsyncUtils.waitReady(isReady = mavlink::isServiceStop)
            mavlink.destroyClient()
            logger.i { "MAVLinkService stop: ${mavlink.isServiceStop()}" }
        }
    }

    fun getMAVLinkState(): StateFlow<Boolean>? = if (isMAVLinkReady()) mavlink.isRunning else null

    fun isRunning(): Boolean = (isMAVLinkReady() && mavlink.isServiceRunning()) || isWebRTCUp()

    fun isReady(): Boolean = isMAVLinkReady() || isWebRTCReady()

}
