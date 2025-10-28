/*
 * Taken and adapted from https://github.com/GetStream/webrtc-in-jetpack-compose
 * Copyright 2023 Stream.IO, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.WenuLink.webrtc

import io.getstream.log.taggedLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.IceCandidate

class WebRTCClient(
    serverAddress: String,
) {

    enum class SessionState {
        Active,     // Offer and Answer messages has been sent
        Creating,   // Creating session, offer has been sent
        Ready,      // Both clients available and ready to initiate session
        Impossible, // We have less than two clients connected to the server
        Offline     // unable to connect signaling server
    }

    enum class CommandType {
        STATE,  // Command for WebRTCSessionState
        OFFER,  // to send or receive offer
        ANSWER, // to send or receive answer
        ICE     // to send and receive ice candidates
    }

    private val logger by taggedLogger("SignalingClient")
    private val signalingScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val client = OkHttpClient()
    private val request = Request
        .Builder()
        .url(serverAddress)
        .build()

    // opening web socket with signaling server
    private val ws = client.newWebSocket(request, SignalingWebSocketListener())
    private var peerSocketID: String? = null

    // session flow to send information about the session state to the subscribers
    private val _sessionStateFlow = MutableStateFlow(SessionState.Offline)
    val sessionStateFlow: StateFlow<SessionState> = _sessionStateFlow

    // signaling commands to send commands to value pairs to the subscribers
    private val _signalingCommandFlow = MutableSharedFlow<Pair<CommandType, String>>()
    val signalingCommandFlow: SharedFlow<Pair<CommandType, String>> = _signalingCommandFlow

    fun sendCommand(message: String) {
        try {
            val jsonMessage = JSONObject().apply {
                put("event", "webrtc_msg")
                put(
                    "data",
                    JSONObject(message).apply {
                        put("socketID", peerSocketID)
                    })
            }

            logger.d { "[sendCommand] $jsonMessage" }
            ws.send(jsonMessage.toString())
        } catch (e: JSONException) {
            logger.e { "JSONException: ${e.message}" }
        }
    }

    fun sendAnswer(sdp: String) {
        try {
            val jsonData = JSONObject().apply {
                put("type", "answer")
                put("sdp", sdp)
            }
            sendCommand(jsonData.toString())
        } catch (e: JSONException) {
            logger.e { "JSONException: ${e.message}" }
        }
    }

    fun sendCandidate(candidate: IceCandidate) {
        try {
            val jsonData = JSONObject().apply {
                put("type", "candidate")
                put("label", candidate.sdpMLineIndex)
                put("id", candidate.sdpMid)
                put("candidate", candidate.sdp)
            }
            sendCommand(jsonData.toString())
        } catch (e: JSONException) {
            logger.e { "JSONException: ${e.message}" }
        }
    }

    fun message2eventData(textMessage: String): Pair<String, String> {
        var event = ""
        var dataString = ""
        try {
            val jsonData = JSONObject(textMessage)
            event = jsonData.getString("event")
            dataString = jsonData.getString("data")

        } catch (e: JSONException) {
            logger.e { "JSONException: ${e.message}" }
        }
        return Pair(event, dataString)
    }

    fun valueFromKey(dataString: String, dataKey: String): String? {
        var dataValue: String? = null
        try {
            val jsonData = JSONObject(dataString)
            dataValue = jsonData.getString(dataKey)
        } catch (e: JSONException) {
            logger.e { "JSONException: ${e.message}" }
        }
        return dataValue
    }

    private inner class SignalingWebSocketListener : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            logger.d { "onMessage $text" }
            val (event, rawData) = message2eventData(text)

            if (event == "client_id") emitReadyState()

            if (event != "webrtc_msg") return

            val type = valueFromKey(rawData, "type")

            when (type) {
                "offer" -> {
                    peerSocketID = valueFromKey(rawData, "socketID")
                    handleSignalingCommand(CommandType.OFFER, rawData)
                }

                "answer" -> handleSignalingCommand(CommandType.ANSWER, rawData)
                "candidate" -> handleSignalingCommand(CommandType.ICE, rawData)
            }
        }
    }

    private fun emitReadyState() {
        logger.d { "handleStateMessage" }
        _sessionStateFlow.value = SessionState.Ready
    }

    private fun handleSignalingCommand(command: CommandType, text: String) {
        logger.d { "received signaling: $command $text" }
        signalingScope.launch {
            _signalingCommandFlow.emit(command to text)
        }
    }

    fun dispose() {
        _sessionStateFlow.value = SessionState.Offline
        signalingScope.cancel()
        ws.cancel()
    }
}