package org.WenuLink.webrtc

import android.content.Context
import io.getstream.log.taggedLogger
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.WenuLink.adapters.camera.CameraCapturer
import org.WenuLink.webrtc.peer.StreamPeerConnection
import org.WenuLink.webrtc.peer.StreamPeerConnectionFactory
import org.WenuLink.webrtc.peer.StreamPeerType
import org.WenuLink.webrtc.utils.stringify
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRTCService {
    companion object {
        private var mInstance: WebRTCService? = null
        var isEnabled: Boolean = true
            private set

        fun getInstance(): WebRTCService {
            if (mInstance == null) {
                mInstance = WebRTCService()
            }
            return mInstance!!
        }
    }

    // logger an coroutine scope
    private val logger by taggedLogger(WebRTCService::class.java.simpleName)
    private lateinit var serviceScope: CoroutineScope // (SupervisorJob() + Dispatchers.Main)
    private var runningJob: Job? = null

    // element required for WebRTC logics
    private lateinit var videoSource: VideoSource
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var webRTCClient: WebRTCClient
    private lateinit var peerConnectionFactory: StreamPeerConnectionFactory
    private lateinit var surfaceTextureHelper: SurfaceTextureHelper

    // Signaling configuration
    private var signalingServer = "ws://192.168.1.220:8090"
    var isServiceUp: Boolean = false
        private set
    var isStreaming: Boolean = false
        private set

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    fun runProcess(isRunning: Boolean) {
        _isRunning.value = isRunning
    }

    // used to send local video track to the fragment
    private val _localVideoTrackFlow = MutableSharedFlow<VideoTrack>()
    private val localVideoTrackFlow: SharedFlow<VideoTrack> = _localVideoTrackFlow

    // declaring video constraints and setting OfferToReceiveVideo to true
    // this step is mandatory to create valid offer and answer
    private val mediaConstraints = MediaConstraints().apply {
        mandatory.addAll(
            listOf(
                MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false")
            )
        )
    }

    // getting front camera
    private val videoCapturer: VideoCapturer by lazy { CameraCapturer() }
    lateinit var mediaOptions: CameraCapturer.MediaMetadata
    private var offer: String? = null

    fun updateServerAddress(serverAddress: String) {
        signalingServer = serverAddress
    }

    private val peerConnection: StreamPeerConnection by lazy {
        peerConnectionFactory.makePeerConnection(
            coroutineScope = serviceScope,
            configuration = peerConnectionFactory.rtcConfig,
            type = StreamPeerType.PUBLISHER,
            mediaConstraints = mediaConstraints,
            onIceCandidateRequest = { iceCandidate, _ ->
                webRTCClient.sendCandidate(iceCandidate)
            }
        )
    }

    fun canStartClient(): Boolean = CameraCapturer.hasCameraPresent() && isEnabled

    fun startClient(serviceScope: CoroutineScope, context: Context) {
        if (!isEnabled) {
            logger.i { "Unable to start client, WebRTC not enabled." }
            return
        }

        mediaOptions = CameraCapturer.MediaMetadata.fromCameraManager() ?: run {
            logger.e { "CameraManager not ready, cannot start WebRTC" }
            return
        }

        this.serviceScope = serviceScope

        logger.i { "Connecting WebRTC client to $signalingServer" }
        if (::webRTCClient.isInitialized) return

        webRTCClient = WebRTCClient(signalingServer)
        peerConnectionFactory = StreamPeerConnectionFactory(context)
        surfaceTextureHelper = SurfaceTextureHelper.create(
            "SurfaceTextureHelperThread",
            peerConnectionFactory.eglBaseContext
        )

        isRunning.distinctUntilChangedBy { it }
            .onEach {
                if (it) {
                    run(context)
                } else {
                    disconnect()
                }
                logger.d { "isRunning: $it" }
            }
            .launchIn(this.serviceScope)
    }

    fun run(context: Context) {
        if (isServiceUp) {
            return
        }
        runningJob = serviceScope.launch {
            webRTCClient.signalingCommandFlow.collect { (command, value) ->
                handleSignalingCommand(command, value, context)
            }
        }
    }

    private suspend fun handleSignalingCommand(
        command: WebRTCClient.CommandType,
        value: String,
        context: Context
    ) {
        logger.d { "signalingCommandFlow $command" }
        when (command) {
            WebRTCClient.CommandType.OFFER -> handleOffer(value, context)
            WebRTCClient.CommandType.ANSWER -> handleAnswer(value)
            WebRTCClient.CommandType.ICE -> handleIce(value)
            else -> Unit
        }
    }

    fun createVideoTrack(context: Context) {
        logger.d { "mediaOptions: $mediaOptions" }
        videoSource =
            peerConnectionFactory.makeVideoSource(videoCapturer.isScreencast).apply {
                videoCapturer.initialize(surfaceTextureHelper, context, this.capturerObserver)
                videoCapturer.startCapture(
                    mediaOptions.videoResolutionWidth,
                    mediaOptions.videoResolutionHeight,
                    mediaOptions.fps
                )
                isStreaming = true
            }

        localVideoTrack =
            peerConnectionFactory.makeVideoTrack(
                source = videoSource,
                trackId = "Video${UUID.randomUUID()}"
            )
    }

    fun onAnswerReady() {
        peerConnection.connection.addTrack(localVideoTrack, listOf(mediaOptions.mediaStreamId))
        serviceScope.launch {
            // sending local video track to show local video from start
            _localVideoTrackFlow.emit(localVideoTrack)

            if (offer != null) {
                sendAnswer()
            }
        }
    }

    fun enableCamera(enabled: Boolean) {
        if (enabled && !isStreaming) {
            isStreaming = true
            videoCapturer.startCapture(
                mediaOptions.videoResolutionWidth,
                mediaOptions.videoResolutionHeight,
                mediaOptions.fps
            )
        } else {
            isStreaming = false
            videoCapturer.stopCapture()
        }
    }

    fun disconnect() {
        if (!isServiceUp) {
            return
        }

        try {
            runningJob?.cancel()
            runningJob = null
            // dispose video tracks
            localVideoTrackFlow.replayCache.forEach { it.dispose() }
            if (::localVideoTrack.isInitialized) {
                localVideoTrack.dispose()
            }

            // stop capturer
            try {
                videoCapturer.stopCapture()
            } catch (e: Exception) {
                logger.e { "Error stopping capture $e" }
            }
            videoCapturer.dispose()

            // release surfaceTextureHelper
            if (::surfaceTextureHelper.isInitialized) {
                surfaceTextureHelper.dispose()
            }

            // close peer connection
            peerConnection.connection.close()

            // stop signaling client
            webRTCClient.dispose()
        } finally {
            isStreaming = false
            isServiceUp = false
        }
    }

    private suspend fun sendAnswer() {
        peerConnection.setRemoteDescription(
            SessionDescription(SessionDescription.Type.OFFER, offer)
        )
        val answer = peerConnection.createAnswer().getOrThrow()
        val result = peerConnection.setLocalDescription(answer)
        result.onSuccess {
            webRTCClient.sendAnswer(answer.description)
        }
        logger.d { "[SDP] send answer: ${answer.stringify()}" }
    }

    private fun handleOffer(sdp: String, context: Context) {
        logger.d { "[SDP] handle offer: $sdp" }
        createVideoTrack(context)
        offer = webRTCClient.valueFromKey(sdp, "sdp")!!
        onAnswerReady()
    }

    private suspend fun handleAnswer(sdp: String) {
        logger.d { "[SDP] handle answer: $sdp" }
        peerConnection.setRemoteDescription(
            SessionDescription(
                SessionDescription.Type.ANSWER,
                webRTCClient.valueFromKey(sdp, "sdp")!!
            )
        )
    }

    private suspend fun handleIce(iceMessage: String) {
        logger.d { "[ICE] handle candidate: $iceMessage" }
        peerConnection.addIceCandidate(
            IceCandidate(
                webRTCClient.valueFromKey(iceMessage, "id")!!,
                webRTCClient.valueFromKey(iceMessage, "label")!!.toInt(),
                webRTCClient.valueFromKey(iceMessage, "candidate")!!
            )
        )
    }
}
