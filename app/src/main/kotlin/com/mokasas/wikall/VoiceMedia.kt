package com.mokasas.wikall

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import livekit.org.webrtc.AudioSource
import livekit.org.webrtc.AudioTrack
import livekit.org.webrtc.DataChannel
import livekit.org.webrtc.IceCandidate
import livekit.org.webrtc.MediaConstraints
import livekit.org.webrtc.MediaStream
import livekit.org.webrtc.PeerConnection
import livekit.org.webrtc.PeerConnectionFactory
import livekit.org.webrtc.SdpObserver
import livekit.org.webrtc.SessionDescription
import livekit.org.webrtc.audio.JavaAudioDeviceModule

internal class VoiceMedia(
    private val context: Context,
    private val send: (SignalType, String) -> Unit,
    private val onState: (CallState) -> Unit,
    private val onFailure: () -> Unit,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private var oldMode: Int? = null
    private var factory: PeerConnectionFactory? = null
    private var peer: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private var closed = false
    private var remoteReady = false
    private var localReady = false
    private var settingRemote = false
    private val remoteIce = ArrayDeque<IceCandidate>()
    private val localIce = ArrayDeque<String>()

    fun start(outgoing: Boolean) {
        check(peer == null)
        oldMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context).createInitializationOptions()
        )
        val device = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val engine = try {
            PeerConnectionFactory.builder().setAudioDeviceModule(device).createPeerConnectionFactory()
        } finally {
            device.release()
        }
        factory = engine
        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        }
        val connection = checkNotNull(engine.createPeerConnection(config, observer))
        peer = connection
        val voiceSource = engine.createAudioSource(MediaConstraints()).also { source = it }
        val microphone = engine.createAudioTrack("wi-call-microphone", voiceSource).also { track = it }
        connection.addTrack(microphone, listOf("wi-call"))
        if (outgoing) connection.createOffer(createSdp(SignalType.OFFER), MediaConstraints())
    }

    fun receive(type: SignalType, payload: String) {
        val connection = peer ?: return
        when (type) {
            SignalType.OFFER, SignalType.ANSWER -> {
                if (settingRemote || remoteReady || !audioOnly(payload)) return
                settingRemote = true
                val sdpType = if (type == SignalType.OFFER) SessionDescription.Type.OFFER else SessionDescription.Type.ANSWER
                connection.setRemoteDescription(setSdp {
                    settingRemote = false
                    remoteReady = true
                    while (remoteIce.isNotEmpty()) connection.addIceCandidate(remoteIce.removeFirst())
                    if (type == SignalType.OFFER) connection.createAnswer(createSdp(SignalType.ANSWER), MediaConstraints())
                }, SessionDescription(sdpType, payload))
            }
            SignalType.ICE -> {
                val candidate = runCatching { decodeIce(payload) }.getOrNull() ?: return
                if (remoteReady) connection.addIceCandidate(candidate)
                else if (remoteIce.size < ICE_LIMIT) remoteIce.add(candidate)
            }
            else -> Unit
        }
    }

    fun mute(muted: Boolean) {
        track?.setEnabled(!muted)
    }

    override fun close() {
        if (closed) return
        closed = true
        track?.setEnabled(false)
        peer?.dispose()
        track?.dispose()
        source?.dispose()
        factory?.dispose()
        oldMode?.let { audio.mode = it }
    }

    private fun active(action: () -> Unit) {
        main.post { if (!closed) action() }
    }

    private fun createSdp(type: SignalType) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = active {
            val sanitized = description.description.lineSequence()
                .filterNot { it.startsWith("a=candidate:") && !lanIce(it.removePrefix("a=")) }
                .joinToString("\r\n")
            if (!audioOnly(sanitized)) {
                onFailure()
                return@active
            }
            peer?.setLocalDescription(setSdp {
                send(type, sanitized)
                localReady = true
                while (localIce.isNotEmpty()) send(SignalType.ICE, localIce.removeFirst())
            }, description)
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(reason: String?) = active(onFailure)
        override fun onSetFailure(reason: String?) = active(onFailure)
    }

    private fun setSdp(success: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription?) = Unit
        override fun onSetSuccess() = active(success)
        override fun onCreateFailure(reason: String?) = active(onFailure)
        override fun onSetFailure(reason: String?) = active(onFailure)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) = active {
            when (state) {
                PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED ->
                    onState(CallState.CONNECTED)
                PeerConnection.IceConnectionState.DISCONNECTED -> onState(CallState.RECONNECTING)
                PeerConnection.IceConnectionState.FAILED -> onFailure()
                else -> Unit
            }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) = Unit
        override fun onIceCandidate(candidate: IceCandidate) = active {
            if (!lanIce(candidate.sdp)) return@active
            val payload = buildJsonObject {
                put("mid", candidate.sdpMid)
                put("index", candidate.sdpMLineIndex)
                put("sdp", candidate.sdp)
            }.toString()
            if (localReady) send(SignalType.ICE, payload)
            else if (localIce.size < ICE_LIMIT) localIce.add(payload)
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
        override fun onAddStream(stream: MediaStream?) = Unit
        override fun onRemoveStream(stream: MediaStream?) = Unit
        override fun onDataChannel(channel: DataChannel?) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    private fun decodeIce(payload: String): IceCandidate {
        require(payload.length <= 1200)
        val objectValue = Json.parseToJsonElement(payload).jsonObject
        val mid = objectValue.getValue("mid").jsonPrimitive.content
        val index = objectValue.getValue("index").jsonPrimitive.int
        val sdp = objectValue.getValue("sdp").jsonPrimitive.content
        require(index == 0 && mid.length in 1..16 && lanIce(sdp))
        return IceCandidate(mid, index, sdp)
    }

    private companion object {
        const val ICE_LIMIT = 32
    }
}

internal fun lanIce(candidate: String): Boolean {
    if (candidate.length > 800 || '\r' in candidate || '\n' in candidate || !candidate.startsWith("candidate:")) return false
    val fields = candidate.split(' ')
    return fields.size >= 8 && fields[1] == "1" && fields[2].equals("udp", ignoreCase = true) &&
        privateIpv4(fields[4]) && (fields[5].toIntOrNull() ?: 0) in 1..65535 &&
        fields[6] == "typ" && fields[7] == "host"
}

internal fun audioOnly(sdp: String): Boolean {
    if (sdp.length > 11_000) return false
    val lines = sdp.lineSequence().map(String::trimEnd).toList()
    val media = lines.filter { it.startsWith("m=") }
    return media.size == 1 && media.single().startsWith("m=audio ") &&
        lines.filter { it.startsWith("a=candidate:") }.all { lanIce(it.removePrefix("a=")) }
}
