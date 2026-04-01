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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.WenuLink.MainActivity
import org.WenuLink.WenuLinkApp
import org.WenuLink.mavlink.MAVLinkService
import org.WenuLink.webrtc.WebRTCService

class WenuLinkService : Service() {
    private val logger by taggedLogger(WenuLinkService::class.java.simpleName)
    private var serviceScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var mavlink: MAVLinkService
    private lateinit var webRTC: WebRTCService
    private lateinit var handler: WenuLinkHandler
    val mavlinkStateFlow: StateFlow<Boolean>?
        get() = if (isMAVLinkReady()) mavlink.isRunning else null
    val webRTCStateFlow: StateFlow<Boolean>?
        get() = if (isWebRTCReady()) webRTC.isRunning else null

    override fun onCreate() {
        super.onCreate()
        (application as WenuLinkApp).wenuLinkService = this // Store the service reference

        // create the aircraft instance
        handler = WenuLinkHandler.getInstance()
        handler.registerScope(serviceScope)

        // create WebRTC instance
        if (WebRTCService.isEnabled && !isWebRTCReady()) {
            webRTC = WebRTCService.getInstance()
        }
        // create MAVLink instance
        if (MAVLinkService.isEnabled && !isMAVLinkReady()) {
            mavlink = MAVLinkService(handler)
        }

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

        val notification: Notification.Builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
        if (::mavlink.isInitialized) {
            contentText = "Sending periodic heartbeats to GCS\n"
        }
        if (::webRTC.isInitialized) {
            contentText += "WebRTC streaming: ${webRTC.mediaOptions.VIDEO_CAMERA_NAME}"
        }
        // .setContentText(webRTC.mediaOptions?.VIDEO_CAMERA_NAME)
        // TODO: update according to each present service
        startForeground(
            1,
            notification
                .setContentTitle("WenuLink service running")
                .setContentText(contentText)
                .setSmallIcon(R.drawable.ic_menu_compass)
                .setContentIntent(pendingIntent) // Open MainActivity when tapped
                .setOngoing(true)
                .build()
        )
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

        // Wait Aircraft to boot
        serviceScope.launch {
            val bootError = handler.bootAircraft()
            if (bootError != null) {
                // No aircraft -> No service
                terminate()
                onDestroy()
            }
        }

        return START_STICKY // The service will continue running
    }

    override fun onDestroy() {
        (application as WenuLinkApp).wenuLinkService = null // Clear the reference
        serviceScope.cancel()
        logger.i { "WenuLinkService ended." }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    fun stopCommands() {
        // mission's logic already stop according to the mission kind
        serviceScope.launch { handler.cancelCommand() }
        // TODO: add RTL/LAND according to settings
    }

    private fun startWebRTC() {
        if (!WebRTCService.isEnabled) {
            logger.i { "Unable to start WebRTC, the service not enabled." }
            return
        }

        if (!webRTC.canStartClient()) {
            logger.e { "WebRTC client not ready, check if enabled and a camera is present." }
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

    fun stopWebRTCService(): Job? {
        if (!isWebRTCReady()) return null

        return serviceScope.launch {
            webRTC.runProcess(false)
            logger.i { "WebRTC service stop." }
        }
    }

    fun isWebRTCUp(): Boolean = if (isWebRTCReady()) webRTC.isServiceUp else false

    fun isMAVLinkReady() = ::mavlink.isInitialized

    fun startMAVLinkService(onResult: (String?) -> Unit): Job? {
        if (!MAVLinkService.isEnabled) {
            logger.i { "Unable to start MAVLink, service not enabled." }
            return null
        }

        if (mavlink.isServiceRunning()) return null

        if (!handler.isTelemetryActive) {
            logger.w { "Unable to start service, no telemetry." }
            return null
        }

        logger.d { "Start MAVLinkService protocol." }
        return serviceScope.launch { mavlink.launchService(onResult) }
    }

    fun stopMAVLinkService(): Job? {
        if (!isMAVLinkReady()) return null

        stopCommands()
        return serviceScope.launch {
            mavlink.stopService()
            logger.i { "MAVLinkService stop: ${mavlink.isServiceStop()}" }
        }
    }

    fun isRunning(): Boolean = (isMAVLinkReady() && mavlink.isServiceRunning()) || isWebRTCUp()

    fun isReady(): Boolean = ::handler.isInitialized && handler.isAircraftPowerOn

    fun isPowerOff(): Boolean = !handler.isAircraftPowerOn

    fun runServices(
        onMAVLinkResult: (String?) -> Unit
//        onWebRTCResult: (String?) -> Unit
    ) {
        // Start services
        startMAVLinkService(onMAVLinkResult)
        startWebRTCService()
    }

    suspend fun terminate() {
        // TODO: perform RTL or LAND before: aircraft.unload()
        handler.manualControl()
        val webRTCStopJob = stopWebRTCService()
        val mavlinkStopJob = stopMAVLinkService()
        webRTCStopJob?.join()
        mavlinkStopJob?.join()
        handler.unload()
        AsyncUtils.waitTimeout(intervalTime = 1000L, timeout = 20000L, isReady = ::isPowerOff)
    }
}
