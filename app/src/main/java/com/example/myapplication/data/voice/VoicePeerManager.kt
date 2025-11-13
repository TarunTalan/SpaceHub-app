package com.example.myapplication.data.voice

import android.content.Context
import android.util.Log
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
    private val TAG = "VoicePeerManager"

    private var factory: PeerConnectionFactory? = null
    var peerConnection: PeerConnection? = null
    private var localAudioTrack: AudioTrack? = null
    private var localAudioSource: AudioSource? = null
    private var audioConstraints: MediaConstraints? = null

    private val _iceCandidates = MutableSharedFlow<IceCandidate>(extraBufferCapacity = 10)
    val iceCandidates = _iceCandidates.asSharedFlow()

    private val _remoteSdp = MutableSharedFlow<SessionDescription>(extraBufferCapacity = 2)
    val remoteSdp = _remoteSdp.asSharedFlow()

    // Expose readable connection state for debug/UX
    private val _pcState = MutableStateFlow("NEW")
    val pcState: StateFlow<String> = _pcState

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        if (factory != null) return
        val initializationOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initializationOptions)

        val options = PeerConnectionFactory.Options()
        val egl = EglBase.create()
        val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
        factory = PeerConnectionFactory.builder()
            .setOptions(options)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        // Audio setup
        audioConstraints = MediaConstraints()
        Log.d(TAG, "PeerConnectionFactory initialized")
    }

    fun createLocalAudioTrack(trackId: String = "ARDAMSa0") : AudioTrack? {
        if (factory == null) initializeFactory()
        try {
            localAudioSource = factory?.createAudioSource(audioConstraints)
            localAudioTrack = factory?.createAudioTrack(trackId, localAudioSource)
            localAudioTrack?.setEnabled(true)
            Log.d(TAG, "Local audio track created: $trackId")
            return localAudioTrack
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create local audio track: ${e.message}")
            return null
        }
    }

    fun createPeerConnection(iceServers: List<PeerConnection.IceServer> = emptyList(), observer: PeerConnection.Observer? = null) {
        val pcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            // Typical settings
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
            keyType = PeerConnection.KeyType.ECDSA
        }

        val obs = observer ?: object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState) {
                Log.d(TAG, "SignalingChange: $newState")
                _pcState.value = "SIGNALING:$newState"
            }
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "IceConnectionChange: $newState")
                _pcState.value = "ICE:$newState"
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "IceConnectionReceivingChange: $receiving")
            }
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState) {
                Log.d(TAG, "IceGatheringChange: $newState")
                _pcState.value = "GATHERING:$newState"
            }
            override fun onIceCandidate(candidate: IceCandidate) {
                Log.d(TAG, "Local ICE candidate: sdpMid=${candidate.sdpMid} index=${candidate.sdpMLineIndex} candidate=${candidate.sdp}")
                _iceCandidates.tryEmit(candidate)
            }
            override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {
                Log.d(TAG, "ICE candidates removed: ${candidates.size}")
            }
            override fun onAddStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream added: ${stream.id}")
            }
            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "Remote stream removed: ${stream.id}")
            }
            override fun onDataChannel(dc: DataChannel) {
                Log.d(TAG, "DataChannel opened: ${dc.label()}")
            }
            override fun onRenegotiationNeeded() {
                Log.d(TAG, "Renegotiation needed")
            }
            override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) {
                try {
                    Log.d(TAG, "Track added: ${receiver.id()} to ${streams.size} streams")
                    val track = receiver.track()
                    if (track is AudioTrack) {
                        Log.d(TAG, "Remote audio track detected, enabling playback")
                        track.setEnabled(true)
                        // On Android, remote audio should play automatically via the native audio pipeline
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to attach remote track: ${e.message}")
                }
            }
        }

        peerConnection = factory?.createPeerConnection(pcConfig, obs)
        Log.d(TAG, "PeerConnection created with config: iceServers=${iceServers.size}")
        // Add audio track to pc
        val track = localAudioTrack ?: createLocalAudioTrack()
        val mediaStream = factory?.createLocalMediaStream("ARDAMS")
        if (track != null && mediaStream != null) {
            mediaStream.addTrack(track)
            peerConnection?.addStream(mediaStream)
            Log.d(TAG, "Local audio track added to PeerConnection and stream ARDAMS created")
        }
    }

    fun createOffer(constraints: MediaConstraints = MediaConstraints(), onSdpReady: (SessionDescription) -> Unit) {
        Log.d(TAG, "Creating SDP offer...")
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "createOffer onCreateSuccess: type=${sdp.type} len=${sdp.description?.length ?: 0}")
                Log.d(TAG, "Offer created, setting local description")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "setLocalDescription success")
                        Log.d(TAG, "Local description set (offer)")
                        onSdpReady(sdp)
                    }
                    override fun onCreateFailure(p0: String?) { Log.e(TAG, "setLocalDescription create failure: $p0") }
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "setLocalDescription set failure: $p0") }
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { Log.e(TAG, "Offer creation failed: $error") }
            override fun onSetFailure(error: String) { Log.e(TAG, "Offer setLocal failed: $error") }
        }, constraints)
    }

    fun createAnswer(onSdpReady: (SessionDescription) -> Unit) {
        Log.d(TAG, "Creating SDP answer...")
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription) {
                Log.d(TAG, "Answer created, setting local description")
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onCreateSuccess(sdp: SessionDescription) {}
                    override fun onSetSuccess() {
                        Log.d(TAG, "Local description set (answer)")
                        onSdpReady(sdp)
                    }
                    override fun onCreateFailure(p0: String?) { Log.e(TAG, "createAnswer create failure: $p0") }
                    override fun onSetFailure(p0: String?) { Log.e(TAG, "createAnswer set failure: $p0") }
                }, sdp)
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String) { Log.e(TAG, "Answer creation failed: $error") }
            override fun onSetFailure(error: String) { Log.e(TAG, "Answer setLocal failed: $error") }
        }, MediaConstraints())
    }

    fun setRemoteDescription(sdp: SessionDescription, onDone: (() -> Unit)? = null) {
        Log.d(TAG, "Setting remote description: type=${sdp.type}")
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                _remoteSdp.tryEmit(sdp)
                onDone?.invoke()
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) { Log.e(TAG, "setRemoteDescription failed: $p0") }
        }, sdp)
    }

    fun addRemoteIce(candidate: IceCandidate) {
        Log.d(TAG, "Adding remote ICE candidate: ${candidate.sdp}")
        peerConnection?.addIceCandidate(candidate)
    }

    fun toggleMute(enabled: Boolean) {
        Log.d(TAG, "Toggle mute localAudioTrack enabled=$enabled")
        localAudioTrack?.setEnabled(enabled)
    }

    fun dispose() {
        try {
            Log.d(TAG, "Disposing peer manager and resources")
            localAudioTrack?.dispose(); localAudioTrack = null
            localAudioSource?.dispose(); localAudioSource = null
            peerConnection?.dispose(); peerConnection = null
            factory?.dispose(); factory = null
        } catch (_: Exception) {}
    }
}
