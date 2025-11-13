package com.example.myapplication.ui.voice

import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.voice.LocalAudioLevelDetector
import com.example.myapplication.data.voice.VoicePeerManager
import com.example.myapplication.data.voice.VoiceRoomRepository
import com.example.myapplication.data.voice.model.JoinVoiceRoomResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import ua.naiksoftware.stomp.Stomp
import ua.naiksoftware.stomp.StompClient
import ua.naiksoftware.stomp.dto.LifecycleEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Ready-to-replace VoiceRoomViewModel.kt
 *
 * - Uses Stomp over OkHttp to connect to SockJS-backed endpoint at /ws/websocket
 * - Owns STOMP lifecycle (doesn't dispose PeerConnection on transient STOMP close)
 * - Handles Janus audiobridge / jsep / candidate messages forwarded by backend
 * - Ensures offers are not sent concurrently, marks send-success only on STOMP success
 * - Cancels resend timers when ICE connects and avoids re-offering once connected
 *
 * NOTE: This ViewModel expects the project to have:
 * - VoicePeerManager with APIs used below (createLocalAudioTrack(), createPeerConnection(), createOffer(onSdpReady), setRemoteDescription(...), addRemoteIce(...), dispose(), peerConnection property)
 * - VoiceRoomRepository.joinVoiceRoom(...) returning Result<JoinVoiceRoomResponse>
 * - UserDataManager.getInstance(...).getEmail() available
 * - LocalAudioLevelDetector available (optional)
 *
 * Replace your existing file with this and ensure the other classes provide the methods the VM calls.
 */
class VoiceRoomViewModel(application: Application) : AndroidViewModel(application) {

    private val TAG = "VoiceRoomVM"

    // repo / deps
    private val repo: VoiceRoomRepository = VoiceRoomRepository.getInstance(application)

    // STOMP
    private var stompClient: StompClient? = null
    private val BASE_URL = "https://codewithketan.me"
    private val WS_URL = "$BASE_URL/ws/websocket" // SockJS websocket fallback

    // Peer manager & audio
    private var peerManager: VoicePeerManager? = null
    private var audioDetector: LocalAudioLevelDetector? = null

    // State flows for UI
    private val _status = MutableStateFlow("idle")
    val status: StateFlow<String> = _status

    private val _members = MutableStateFlow<List<VoiceMember>>(emptyList())
    val members: StateFlow<List<VoiceMember>> = _members

    private val _speakingUser = MutableStateFlow<String?>(null)
    val speakingUser: StateFlow<String?> = _speakingUser

    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted

    // Join state
    private val _joinState = MutableStateFlow<JoinState>(JoinState.Idle)
    val joinState: StateFlow<JoinState> = _joinState

    sealed class JoinState {
        object Idle : JoinState()
        object Loading : JoinState()
        data class Success(val resp: JoinVoiceRoomResponse) : JoinState()
        data class Error(val msg: String) : JoinState()
    }

    // Create state observed by RoomFragment when creating a voice room
    private val _createState = MutableStateFlow<CreateState>(CreateState.Idle)
    val createState: StateFlow<CreateState> = _createState

    sealed class CreateState {
        object Idle : CreateState()
        object Loading : CreateState()
        data class Success(val resp: com.example.myapplication.data.voice.model.CreateVoiceRoomResponse) : CreateState()
        data class Error(val msg: String) : CreateState()
    }

    // List state observed by RoomFragment when loading voice rooms
    private val _listState = MutableStateFlow<ListState>(ListState.Idle)
    val listState: StateFlow<ListState> = _listState

    sealed class ListState {
        object Idle : ListState()
        object Loading : ListState()
        data class Success(val resp: com.example.myapplication.data.voice.model.GetAllVoiceRoomsResponse) : ListState()
        data class Error(val msg: String?) : ListState()
    }

    // User id / display
    private val currentUserIdRef = AtomicReference<String>("")

    // Offer / resend / dedupe
    private val isCreatingOffer = AtomicBoolean(false)
    private var pendingOfferRoomId: String? = null
    private var pendingOfferSessionId: String? = null
    private var pendingOfferHandleId: String? = null
    private var pendingOfferSent = false
    private var lastOfferSentAt: Long = 0L
    private var offerResendJob: Job? = null
    private val OFFER_DEDUP_MS = 3000L

    // ICE connected guard
    private val iceConnected = AtomicBoolean(false)

    // queued remote JSEP if we receive it too early
    private var queuedRemoteJsep: SessionDescription? = null

    init {
        try {
            Class.forName("org.webrtc.PeerConnectionFactory")
            _status.value = "webrtc_available"
        } catch (t: Throwable) {
            _status.value = "webrtc_missing"
        }
    }

    // ---------------- Repository wrappers ----------------
    fun createVoiceRoom(chatRoomId: String, roomName: String, createdBy: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _createState.value = CreateState.Loading
                val res = repo.createVoiceRoom(chatRoomId, roomName, createdBy)
                if (res.isSuccess) {
                    _createState.value = CreateState.Success(res.getOrThrow())
                } else {
                    _createState.value = CreateState.Error(res.exceptionOrNull()?.message ?: "Create failed")
                }
            } catch (e: Exception) {
                Log.w(TAG, "createVoiceRoom failed: ${e.message}")
                _createState.value = CreateState.Error(e.message ?: "Create failed")
            }
        }
    }

    fun getVoiceRooms(roomId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _listState.value = ListState.Loading
                val res = repo.getVoiceRooms(roomId)
                if (res.isSuccess) {
                    _listState.value = ListState.Success(res.getOrThrow())
                } else {
                    _listState.value = ListState.Error(res.exceptionOrNull()?.message)
                }
            } catch (e: Exception) {
                Log.w(TAG, "getVoiceRooms failed: ${e.message}")
                _listState.value = ListState.Error(e.message)
            }
        }
    }

    fun joinVoiceRoom(janusRoomId: Int, displayName: String) {
        viewModelScope.launch {
            _joinState.value = JoinState.Loading
            try {
                val res = repo.joinVoiceRoom(janusRoomId, displayName)
                if (res.isSuccess) {
                    _joinState.value = JoinState.Success(res.getOrThrow())
                } else {
                    _joinState.value = JoinState.Error(res.exceptionOrNull()?.message ?: "Join failed")
                }
            } catch (e: Exception) {
                _joinState.value = JoinState.Error(e.message ?: "Join error")
            }
        }
    }

    // ---------------- STOMP lifecycle & register ----------------
    /**
     * Start STOMP and register with the server.
     * roomId: chat room uuid (string)
     * sessionId/handleId: janus session/handle if already returned
     * userId: display name / email to use as topic suffix
     */
    fun startSocketAndRegister(roomId: String, sessionId: String = "", handleId: String = "", userId: String = "") {
        // Resolve user email asynchronously (getEmail is suspend). Then start STOMP on main thread.
        viewModelScope.launch(Dispatchers.Main) {
            val resolvedUser = if (userId.isNotBlank()) {
                userId
            } else {
                try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
            }
            currentUserIdRef.set(resolvedUser)

            // if stomp already exists, just try to send register if connected
            if (stompClient != null) {
                sendRegisterIfConnected(roomId, sessionId, handleId)
                return@launch
            }

            try {
                stompClient = Stomp.over(Stomp.ConnectionProvider.OKHTTP, WS_URL)
                stompClient?.withClientHeartbeat(10000)?.withServerHeartbeat(0)
                stompClient?.lifecycle()?.subscribe { life ->
                    when (life.type) {
                        LifecycleEvent.Type.OPENED -> {
                            Log.d(TAG, "STOMP opened -> sending register")
                            _status.value = "socket_connected"
                            sendRegisterIfConnected(roomId, sessionId, handleId)
                        }
                        LifecycleEvent.Type.ERROR -> {
                            Log.e(TAG, "STOMP error: ${life.exception?.message}")
                            _status.value = "socket_error:${life.exception?.message}"
                        }
                        LifecycleEvent.Type.CLOSED -> {
                            // Important: do NOT dispose peer connection here. STOMP can transiently close.
                            Log.d(TAG, "STOMP closed — will keep PeerConnection alive. Consider reconnecting.")
                            _status.value = "socket_closed"
                            // Optional: implement reconnect logic instead of tearing down
                        }
                        else -> {}
                    }
                }
                stompClient?.connect()
                _status.value = "socket_connecting"
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start STOMP: ${e.message}", e)
                _status.value = "socket_connect_failed:${e.message}"
                stompClient = null
            }
        }
    }

    /**
     * Subscribe to topics and send /app/register
     */
    private fun sendRegisterIfConnected(roomId: String, sessionId: String, handleId: String) {
        // run async to allow suspend getEmail() usage in fallback
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uid = currentUserIdRef.get().ifBlank {
                    try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                }

                // subscribe first
                subscribeToTopics(roomId, uid)

                val registerObj = JSONObject().apply {
                    put("userId", uid)
                    put("roomId", roomId)
                    if (sessionId.isNotBlank()) put("sessionId", sessionId)
                    if (handleId.isNotBlank()) put("handleId", handleId)
                }.toString()

                stompClient?.send("/app/register", registerObj)?.subscribe({
                    Log.d(TAG, "Register sent payload=$registerObj")
                    _status.value = "registered"
                    try {
                        setupPeer(roomId, sessionId, handleId)
                        createAndSendOffer(roomId, sessionId, handleId)
                        scheduleOfferResend(roomId, sessionId, handleId)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to setup/send offer after register: ${e.message}")
                    }
                }, { err ->
                    Log.e(TAG, "Register send failed: ${err?.message}")
                    _status.value = "register_failed:${err?.message}"
                })
            } catch (e: Exception) {
                Log.e(TAG, "sendRegisterIfConnected error: ${e.message}", e)
            }
        }
    }

    private fun subscribeToTopics(roomId: String, uid: String) {
        try {
            val eventsTopic = "/topic/room/$roomId/events"
            stompClient?.topic(eventsTopic)?.subscribe { msg ->
                handleRoomEventStomp(msg.payload)
            }
            if (uid.isNotBlank()) {
                val answerTopic = "/topic/room/$roomId/answer/$uid"
                stompClient?.topic(answerTopic)?.subscribe { msg ->
                    handleJanusPayload(msg.payload)
                }
            }
            // Subscribe to email fallback if different
            viewModelScope.launch {
                val email = try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                if (email.isNotBlank() && email != uid) {
                    val answerTopic2 = "/topic/room/$roomId/answer/$email"
                    stompClient?.topic(answerTopic2)?.subscribe { msg ->
                        handleJanusPayload(msg.payload)
                    }
                }
            }
            Log.d(TAG, "Subscribed to topics for room=$roomId user=$uid")
        } catch (e: Exception) {
            Log.w(TAG, "subscribeToTopics failure: ${e.message}")
        }
    }

    // ---------------- Room event handlers ----------------
    private fun handleRoomEventStomp(payload: String) {
        try {
            val obj = JSONObject(payload)

            // Handle Janus audiobridge event payloads and normalize to UI events
            if (obj.has("audiobridge")) {
                val bridge = obj.optString("audiobridge")
                if (bridge == "joined") {
                    if (obj.has("participants")) {
                        val parts = obj.getJSONArray("participants")
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            val display = p.optString("display", "")
                            if (display.isNotBlank()) onRemoteJoined(display, display, null)
                        }
                        // Flush offer if our participant present
                        flushOfferIfMyParticipantPresent(obj.getJSONArray("participants"))
                    }
                    return
                }
                if (bridge == "event") {
                    // event may contain participants with muted state
                    if (obj.has("participants")) {
                        val parts = obj.getJSONArray("participants")
                        for (i in 0 until parts.length()) {
                            val p = parts.getJSONObject(i)
                            val display = p.optString("display", "")
                            val muted = p.optBoolean("muted", false)
                            if (display.isNotBlank()) {
                                if (muted) onRemoteMuted(display) else onRemoteUnmuted(display)
                            }
                        }
                    }
                    return
                }
            }

            // fallback to small event shapes like { "type": "joined", "userId": "..." }
            val type = obj.optString("type")
            when (type) {
                "joined" -> {
                    val uid = obj.optString("userId")
                    val name = obj.optString("name", uid)
                    val image = obj.optString("imageUrl").takeIf { it.isNotBlank() }
                    onRemoteJoined(uid, name, image)
                    val curUid = currentUserIdRef.get()
                    if (!pendingOfferSent && curUid.isNotBlank() && uid == curUid) {
                        Log.d(TAG, "Flushing pending offer after registered/joined for user=$uid")
                        createAndSendOffer(pendingOfferRoomId ?: return, pendingOfferSessionId ?: "", pendingOfferHandleId ?: "")
                        pendingOfferSent = true
                    }
                }
                "left" -> onRemoteLeft(obj.optString("userId"))
                "muted" -> onRemoteMuted(obj.optString("userId"))
                "unmuted" -> onRemoteUnmuted(obj.optString("userId"))
                else -> Log.d(TAG, "Unhandled room event: $payload")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleRoomEventStomp parse failed: ${e.message}")
        }
    }

    private fun onRemoteMuted(userId: String) {
        // update UI or member state
        Log.d(TAG, "Remote muted: $userId")
    }

    private fun onRemoteUnmuted(userId: String) {
        Log.d(TAG, "Remote unmuted: $userId")
    }

    // ---------------- Janus message handling ----------------
    private fun handleJanusPayload(payload: String) {
        try {
            val tok = org.json.JSONTokener(payload).nextValue()
            if (tok is JSONArray) {
                for (i in 0 until tok.length()) {
                    processJanusObject(tok.getJSONObject(i))
                }
            } else if (tok is JSONObject) {
                processJanusObject(tok)
            } else {
                Log.w(TAG, "Unknown Janus payload type")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleJanusPayload parse error: ${e.message}")
        }
    }

    private fun processJanusObject(obj: JSONObject) {
        try {
            // audiobridge plugin events
            if (obj.has("audiobridge")) {
                val bridge = obj.optString("audiobridge")
                Log.d(TAG, "Received audiobridge event: $bridge -> $obj")
                if (bridge == "joined" || bridge == "event") {
                    // participants array may be present directly or under 'result'
                    if (obj.has("participants")) {
                        flushOfferIfMyParticipantPresent(obj.getJSONArray("participants"))
                    } else {
                        val res = obj.optJSONObject("result")
                        if (res != null && res.has("participants")) {
                            flushOfferIfMyParticipantPresent(res.getJSONArray("participants"))
                        }
                    }
                }
                // continue to parse jsep/candidate/plugindata if present
            }

            // jsep (answer)
            val jsep = obj.optJSONObject("jsep")
            if (jsep != null) {
                val type = jsep.optString("type")
                val sdp = jsep.optString("sdp")
                if (type.isNotBlank() && sdp.isNotBlank()) {
                    Log.d(TAG, "Applying remote jsep type=$type len=${sdp.length}")
                    applyRemoteJsep(type, sdp)
                    _status.value = "peer_answered"
                }
            }

            // candidate
            if (obj.has("candidate")) {
                val candAny = obj.get("candidate")
                when (candAny) {
                    is JSONObject -> {
                        val candidate = candAny.optString("candidate")
                        val sdpMid = candAny.optString("sdpMid", candAny.optString("sdpmid"))
                        val sdpIdx = candAny.optInt("sdpMLineIndex", candAny.optInt("sdpMLineindex", 0))
                        if (candidate.isNotBlank()) {
                            Log.d(TAG, "Received remote ICE candidate sdpMid=$sdpMid idx=$sdpIdx")
                            addRemoteCandidate(if (sdpMid.isBlank()) null else sdpMid, sdpIdx, candidate)
                        } else if (candAny.optBoolean("completed", false)) {
                            Log.d(TAG, "Received ICE gathering complete signal")
                        }
                    }
                    is String -> {
                        try {
                            val parsed = JSONObject(candAny)
                            val candidate = parsed.optString("candidate")
                            val sdpMid = parsed.optString("sdpMid", parsed.optString("sdpmid"))
                            val sdpIdx = parsed.optInt("sdpMLineIndex", parsed.optInt("sdpMLineindex", 0))
                            if (candidate.isNotBlank()) addRemoteCandidate(if (sdpMid.isBlank()) null else sdpMid, sdpIdx, candidate)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not parse candidate string: ${e.message}")
                        }
                    }
                    else -> Log.w(TAG, "Unknown candidate payload type: ${candAny::class.java}")
                }
            }

            // plugindata may contain simpler room events -> forward to handler
            if (obj.has("plugindata")) {
                val plug = obj.getJSONObject("plugindata")
                val data = plug.optJSONObject("data")
                if (data != null) handleRoomEventStomp(data.toString())
            }

            // also handle small {type:..., userId:...} shapes
            if (obj.has("type")) {
                handleRoomEventStomp(obj.toString())
            }

        } catch (e: Exception) {
            Log.w(TAG, "processJanusObject error: ${e.message}")
        }
    }

    /**
     * If the participants list contains our registered displayName, flush the pending offer.
     */
    private fun flushOfferIfMyParticipantPresent(participants: JSONArray) {
        try {
            val myId = currentUserIdRef.get()
            for (i in 0 until participants.length()) {
                val p = participants.getJSONObject(i)
                val display = p.optString("display", p.optString("id", ""))
                if (display == myId) {
                    Log.d(TAG, "Found our participant in Janus participants list: $display. Attempting to flush offer.")
                    viewModelScope.launch {
                        val shouldForceSend = !pendingOfferSent || (System.currentTimeMillis() - lastOfferSentAt) > 8000L
                        if (shouldForceSend) {
                            pendingOfferSent = false
                            createAndSendOffer(pendingOfferRoomId ?: return@launch, pendingOfferSessionId ?: "", pendingOfferHandleId ?: "")
                        } else {
                            Log.d(TAG, "Pending offer already sent recently; skipping flush.")
                        }
                    }
                    return
                }
            }
            Log.d(TAG, "My participant ($myId) not present in participants list yet.")
        } catch (e: Exception) {
            Log.w(TAG, "flushOfferIfMyParticipantPresent error: ${e.message}")
        }
    }

    // ---------------- Peer setup ----------------
    fun setupPeer(roomId: String, sessionId: String = "", handleId: String = "") {
        if (_status.value == "webrtc_missing") return
        try {
            peerManager?.dispose()
            peerManager = VoicePeerManager(getApplication())

            // Collect peer manager pcState (string) if available to detect ICE connected/completed.
            try {
                val pcStateFlow = peerManager?.pcState
                if (pcStateFlow != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            pcStateFlow.collect { stateStr ->
                                try {
                                    // stateStr is like "ICE:CONNECTED" or "ICE:COMPLETED"
                                    if (stateStr.contains("CONNECTED") || stateStr.contains("COMPLETED")) {
                                        if (!iceConnected.get()) {
                                            iceConnected.set(true)
                                            offerResendJob?.cancel()
                                            offerResendJob = null
                                            Log.d(TAG, "ICE connected/completed — cancelled offer resends (pcState=$stateStr)")
                                        }
                                    } else if (stateStr.contains("CLOSED") || stateStr.contains("FAILED")) {
                                        iceConnected.set(false)
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "pcState handler error: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "pcState collector ended: ${e.message}")
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            // Audio configuration
            try {
                val am = getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                am.isSpeakerphoneOn = true
                _status.value = "audio_configured"
            } catch (e: Exception) {
                Log.w(TAG, "AudioManager config failed: ${e.message}")
            }

            // create local audio and peer connection
            peerManager?.createLocalAudioTrack()
            val stunServers = listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
            peerManager?.createPeerConnection(stunServers)

            // collect ICE candidates produced by peerManager and forward via STOMP
            // peerManager should expose a Flow/Callback for local ICE - if not, it must be added in that class
            try {
                val flow = peerManager?.iceCandidates
                if (flow != null) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            flow.collect { candidate ->
                                try {
                                    val uid = currentUserIdRef.get().ifBlank {
                                        try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                                    }
                                    val candObj = JSONObject().apply {
                                        put("candidate", candidate.sdp)
                                        put("sdpMid", candidate.sdpMid)
                                        put("sdpMLineIndex", candidate.sdpMLineIndex)
                                    }
                                    val payload = JSONObject().apply {
                                        put("userId", uid)
                                        put("candidate", candObj)
                                        pendingOfferRoomId?.let { put("roomId", it) }
                                        pendingOfferSessionId?.let { put("sessionId", it) }
                                        pendingOfferHandleId?.let { put("handleId", it) }
                                    }.toString()
                                    stompClient?.send("/app/ice", payload)?.subscribe({}, { e -> Log.w(TAG, "send ice failed: ${e?.message}" ) })
                                } catch (e: Exception) {
                                    Log.w(TAG, "forward local ICE failed: ${e.message}")
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "ice candidate collector ended: ${e.message}")
                        }
                    }
                }
            } catch (_: Throwable) {
            }

            // audio detector for speaking events (optional)
            audioDetector?.stop()
            audioDetector = LocalAudioLevelDetector()
            audioDetector?.start { level ->
                val speaking = level > 1000f
                viewModelScope.launch(Dispatchers.IO) {
                    val uid = currentUserIdRef.get().ifBlank {
                        try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                    }
                    if (speaking) {
                        _speakingUser.value = uid
                        val payload = JSONObject().apply {
                            put("type", "speaking")
                            put("userId", uid)
                            put("roomId", roomId)
                            if (sessionId.isNotBlank()) put("sessionId", sessionId)
                            if (handleId.isNotBlank()) put("handleId", handleId)
                        }.toString()
                        stompClient?.send("/app/speaking", payload)?.subscribe({}, { e -> Log.w(TAG, "speaking send failed: ${e?.message}" ) })
                    } else {
                        _speakingUser.value = null
                        val payload = JSONObject().apply {
                            put("type", "stopped_speaking")
                            put("userId", uid)
                            put("roomId", roomId)
                        }.toString()
                        stompClient?.send("/app/stopped_speaking", payload)?.subscribe({}, { e -> Log.w(TAG, "stopped_speaking failed: ${e?.message}" ) })
                    }
                }
            }

            _status.value = "peer_created"
        } catch (e: Exception) {
            _status.value = "peer_setup_failed:${e.message}"
            Log.e(TAG, "setupPeer failed", e)
        }
    }

    // ---------------- Offer creation & send ----------------
    fun createAndSendOffer(roomId: String, sessionId: String = "", handleId: String = "") {
        // do not create offers if ICE already connected
        if (iceConnected.get()) {
            Log.d(TAG, "ICE already connected — skipping createAndSendOffer for room=$roomId")
            return
        }

        // dedupe rapid repeats
        if (System.currentTimeMillis() - lastOfferSentAt < OFFER_DEDUP_MS) {
            Log.d(TAG, "Skipping offer creation — lastOfferSent ${System.currentTimeMillis() - lastOfferSentAt}ms ago")
            return
        }

        // prevent concurrent creation
        if (!isCreatingOffer.compareAndSet(false, true)) {
            Log.d(TAG, "Offer creation already in progress; skipping duplicate request")
            return
        }

        // ensure peer ready
        if (peerManager == null) {
            Log.d(TAG, "PeerManager not ready; queueing offer for room=$roomId")
            pendingOfferRoomId = roomId
            pendingOfferSessionId = sessionId.takeIf { it.isNotBlank() }
            pendingOfferHandleId = handleId.takeIf { it.isNotBlank() }
            pendingOfferSent = false
            isCreatingOffer.set(false)
            return
        }

        peerManager?.createOffer(onSdpReady = { sdp ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    Log.d(TAG, "Local SDP created, length=${sdp.description.length}")
                    val uid = currentUserIdRef.get().ifBlank {
                        try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                    }
                    val payloadObj = JSONObject().apply {
                        put("userId", uid)
                        put("roomId", roomId)
                        put("sdp", sdp.description)
                        if (sessionId.isNotBlank()) put("sessionId", sessionId)
                        if (handleId.isNotBlank()) put("handleId", handleId)
                    }
                    val payloadStr = payloadObj.toString()
                    pendingOfferSent = false

                    Log.d(TAG, "Attempting to send offer via /app/offer payload len=${payloadStr.length}")
                    val sendDisposable = stompClient?.send("/app/offer", payloadStr)
                    if (sendDisposable == null) {
                        Log.w(TAG, "stompClient.send returned null (not connected). Queueing and resetting guard.")
                        pendingOfferRoomId = roomId
                        pendingOfferSessionId = sessionId.takeIf { it.isNotBlank() }
                        pendingOfferHandleId = handleId.takeIf { it.isNotBlank() }
                        pendingOfferSent = false
                        isCreatingOffer.set(false)
                    } else {
                        sendDisposable.subscribe({
                            Log.d(TAG, "✅ Offer sent successfully for room=$roomId")
                            pendingOfferSent = true
                            lastOfferSentAt = System.currentTimeMillis()
                            offerResendJob?.cancel()
                            offerResendJob = null
                            pendingOfferRoomId = null
                            pendingOfferSessionId = null
                            pendingOfferHandleId = null
                            _status.value = "offer_sent"
                            isCreatingOffer.set(false)
                        }, { err ->
                            Log.e(TAG, "❌ Offer send failed: ${err?.message}")
                            pendingOfferSent = false
                            _status.value = "offer_send_failed:${err?.message}"
                            isCreatingOffer.set(false)
                        })
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "createAndSendOffer error: ${e.message}", e)
                    pendingOfferSent = false
                    isCreatingOffer.set(false)
                }
            }
        })
    }

    private fun scheduleOfferResend(roomId: String, sessionId: String, handleId: String) {
        offerResendJob?.cancel()
        if (iceConnected.get()) {
            Log.d(TAG, "ICE already connected — no need to schedule offer resend")
            return
        }
        offerResendJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(8000)
                if (!pendingOfferSent && !iceConnected.get()) {
                    Log.d(TAG, "Resending offer fallback for room=$roomId")
                    createAndSendOffer(roomId, sessionId, handleId)
                } else {
                    Log.d(TAG, "Skipping resend: pendingOfferSent=$pendingOfferSent iceConnected=${iceConnected.get()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "offerResendJob error: ${e.message}")
            }
        }
    }

    // ---------------- Incoming JSEP / ICE helpers ----------------
    fun applyRemoteJsep(type: String, sdpStr: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sdp = SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdpStr)
                val localPc = peerManager?.peerConnection
                if (localPc == null) {
                    Log.w(TAG, "PeerConnection missing when applying remote JSEP — queuing")
                    queuedRemoteJsep = sdp
                    return@launch
                }

                val remoteDesc = localPc.remoteDescription
                if (remoteDesc != null && remoteDesc.description.isNotEmpty()) {
                    // cheap duplicate check by length
                    if (remoteDesc.type == SessionDescription.Type.ANSWER && remoteDesc.description.length == sdp.description.length) {
                        Log.d(TAG, "Remote answer already applied (length match), ignoring duplicate")
                        _status.value = "already_had_answer"
                        return@launch
                    }
                    Log.d(TAG, "Remote description exists (type=${remoteDesc.type}). Current signalingState=${localPc.signalingState()}")
                }

                val signalingState = localPc.signalingState()
                // Only set remote description when PC is in a state that can accept an answer
                if (signalingState == PeerConnection.SignalingState.HAVE_LOCAL_OFFER ||
                    signalingState == PeerConnection.SignalingState.STABLE) {
                    Log.d(TAG, "Setting remote description: type=$type length=${sdp.description.length} signalingState=$signalingState")
                    peerManager?.setRemoteDescription(sdp)
                    _status.value = "peer_answered"
                } else {
                    Log.w(TAG, "PC not in expected state to set remote desc ($signalingState). Queuing remote JSEP for short retry.")
                    queuedRemoteJsep = sdp
                    viewModelScope.launch(Dispatchers.IO) {
                        delay(300)
                        try {
                            val s = peerManager?.peerConnection
                            if (s != null && (s.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER || s.signalingState() == PeerConnection.SignalingState.STABLE)) {
                                peerManager?.setRemoteDescription(queuedRemoteJsep!!)
                                queuedRemoteJsep = null
                                _status.value = "peer_answered"
                            } else {
                                Log.w(TAG, "Retry to setRemoteDescription still not in correct state: ${s?.signalingState()}")
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Retry setRemoteDescription failed: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "applyRemoteJsep failed: ${e.message}", e)
            }
        }
    }

    fun addRemoteCandidate(sdpMid: String?, sdpMLineIndex: Int, candidateStr: String) {
        try {
            val ice = IceCandidate(sdpMid, sdpMLineIndex, candidateStr)
            peerManager?.addRemoteIce(ice)
        } catch (e: Exception) {
            Log.e(TAG, "addRemoteCandidate failed: ${e.message}", e)
        }
    }

    // ---------------- Members / UI helpers ----------------
    fun onRemoteJoined(userId: String, name: String?, imageUrl: String?) {
        val m = VoiceMember(userId, name ?: userId, imageUrl)
        val list = _members.value.toMutableList()
        list.removeAll { it.userId == userId }
        list.add(m)
        _members.value = list.toList()
    }

    fun onRemoteLeft(userId: String) {
        val list = _members.value.toMutableList()
        list.removeAll { it.userId == userId }
        _members.value = list.toList()
    }

    fun onRemoteSpeaking(userId: String) {
        _speakingUser.value = userId
    }

    // ---------------- Mute / End call ----------------
    fun toggleMute(roomId: String, sessionId: String = "", handleId: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uid = currentUserIdRef.get().ifBlank {
                    try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                }
                val action = if (_muted.value) "unmute" else "mute"
                val payload = JSONObject().apply {
                    put("userId", uid)
                    put("roomId", roomId)
                    put("action", action)
                    if (sessionId.isNotBlank()) put("sessionId", sessionId)
                    if (handleId.isNotBlank()) put("handleId", handleId)
                }.toString()
                stompClient?.send("/app/mute", payload)?.subscribe()
                _muted.value = !_muted.value
                peerManager?.toggleMute(!_muted.value)
                _status.value = "mute=${_muted.value}"
            } catch (e: Exception) {
                Log.e(TAG, "toggleMute error: ${e.message}")
            }
        }
    }

    fun endCall(roomId: String, sessionId: String = "", handleId: String = "") {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val uid = currentUserIdRef.get().ifBlank {
                    try { UserDataManager.getInstance(getApplication()).getEmail() ?: "" } catch (_: Exception) { "" }
                }
                val payload = JSONObject().apply {
                    put("userId", uid)
                    if (sessionId.isNotBlank()) put("sessionId", sessionId)
                    if (handleId.isNotBlank()) put("handleId", handleId)
                }.toString()
                stompClient?.send("/app/unregister", payload)?.subscribe()
                _status.value = "unregistered"
            } catch (e: Exception) {
                Log.w(TAG, "unregister send failed: ${e.message}")
            } finally {
                // cleanup: only when user explicitly ends call
                try { stompClient?.disconnect() } catch (_: Exception) {}
                stompClient = null
                try { peerManager?.dispose(); peerManager = null } catch (_: Exception) {}
                try { audioDetector?.stop(); audioDetector = null } catch (_: Exception) {}
                offerResendJob?.cancel()
                offerResendJob = null
                pendingOfferRoomId = null
                pendingOfferSessionId = null
                pendingOfferHandleId = null
                pendingOfferSent = false
                iceConnected.set(false)
                _members.value = emptyList()
                _speakingUser.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try { offerResendJob?.cancel() } catch (_: Exception) {}
        try { stompClient?.disconnect() } catch (_: Exception) {}
        stompClient = null
        try { peerManager?.dispose(); peerManager = null } catch (_: Exception) {}
    }
}
