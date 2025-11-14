package com.example.myapplication.data.voice

import android.content.Context
import org.webrtc.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Lightweight WebRTC peer manager for audio-only peer connections.
 * It creates a PeerConnectionFactory, local AudioTrack, and manages a single PeerConnection.
 * Signalling (offer/answer/ice) must be handled externally via ChatWebSocketService/VoiceRoomRepository.
 */
class VoicePeerManager(private val context: Context) {
    private var factory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var audioConstraints: MediaConstraints? = null

    private val _iceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 10)
    val iceCandidates = _iceCandidates.asSharedFlow()

    private val _remoteSdp = MutableSharedFlow<SessionDescription>(extraBufferCapacity = 2)
    @Suppress("unused")
    val remoteSdp = _remoteSdp.asSharedFlow()

    // Expose readable connection state for debug/UX
    private val _pcState = MutableStateFlow("NEW")
    val pcState: StateFlow<String> = _pcState

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        if (factory != null) return
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)

        val options = PeerConnectionFactory.Options()
        val egl = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        audioConstraints = MediaConstraints()
    }

    fun createLocalAudioTrack(trackId: String = "ARDAMSa0"): AudioTrack? {
        if (factory == null) initializeFactory()
        return try {
            localAudioSource = factory?.createAudioSource(audioConstraints)
            localAudioTrack = factory?.createAudioTrack(trackId, localAudioSource)
            localAudioTrack?.setEnabled(true)
            localAudioTrack
        } catch (_: Exception) {
            null
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = emptyList(), observer: PeerConnection.Observer? = null) {
        val pcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
        }

        val obs = observer ?: object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                _pcState.value = "SIGNALING:$newState"
            }

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                _pcState.value = "ICE:$newState"
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {}

            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                _pcState.value = "GATHERING:$newState"
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                _iceCandidates.tryEmit(candidate)
            }

            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}

            override fun onAddStream(stream: MediaStream) {}

            override fun onRemoveStream(stream: MediaStream) {}

            override fun onDataChannel(dc: DataChannel) {}

            override fun onRenegotiationNeeded() {}

            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                try {
                    val track = receiver.track()
                    if (track is AudioTrack) {
                        track.setEnabled(true)
                        // remote audio playback is handled by the WebRTC native pipeline
                    }
                } catch (_: Exception) {
                    // ignore attach errors
                }
            }
        }

        peerConnection = factory?.createPeerConnection(pcConfig, obs)

        val track = localAudioTrack ?: createLocalAudioTrack()
        val mediaStream = factory?.createLocalMediaStream("ARDAMS")
        if (track != null && mediaStream != null) {
            mediaStream.addTrack(track)
            peerConnection?.addStream(mediaStream)
        }
    }

    fun createOffer(constraints: MediaConstraints = MediaConstraints(), onSdpReady: (SessionDescription) -> Unit) {
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { onSdpReady(sdp) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
        }, constraints)
    }

    @Suppress("unused")
    fun createAnswer(onSdpReady: (SessionDescription) -> Unit) {
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetSuccess() { onSdpReady(sdp) }
                    override fun onCreateFailure(p0: String?) {}
                    override fun onSetFailure(p0: String?) {}
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) {}
            override fun onSetFailure(error: String) {}
        }, MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: (() -> Unit)? = null) {
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() { _remoteSdp.tryEmit(sdp); onDone?.invoke() }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {}
        }, sdp)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        peerConnection?.addIceCandidate(candidate)
    }

    fun toggleMute(enabled: Boolean) { localAudioTrack?.setEnabled(enabled) }

    fun dispose() {
        try {
            localAudioTrack?.dispose(); localAudioTrack = null
            localAudioSource?.dispose(); localAudioSource = null
            peerConnection?.dispose(); peerConnection = null
            factory?.dispose(); factory = null
        } catch (_: Exception) {}
    }
}
