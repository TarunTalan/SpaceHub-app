package com.example.myapplication.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.data.chat.websocket.ChatWebSocketService
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.data.voice.VoicePeerManager
import com.google.gson.Gson
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class VoiceRoomFragment : Fragment(R.layout.fragment_voice_room) {
    private var socketService: ChatWebSocketService? = null
    private var muted = false
    private var peerManager: VoicePeerManager? = null
    private val gson = Gson()
    private val AUDIO_PERM_REQUEST = 101
    private var webrtcAvailable = true

    // members list & adapter at class level so detector can update it
    private val members = mutableListOf<VoiceMember>()
    private lateinit var membersAdapter: VoiceMemberAdapter
    private var audioDetector: com.example.myapplication.data.voice.LocalAudioLevelDetector? = null

    // status view for quick debugging
    private var statusView: TextView? = null

    private val TAG = "VoiceRoomFragment"

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Find status view
        statusView = view.findViewById<TextView>(R.id.tv_voice_status)
        updateStatus("init")

        // Runtime check: ensure org.webrtc classes are available. If not, show instructions
        // and continue — we won't create PeerConnection components but the UI and socket will still work.
        webrtcAvailable = true
        try {
            Class.forName("org.webrtc.PeerConnectionFactory")
            updateStatus("WebRTC classes present")
            Log.d(TAG, "WebRTC classes found")
        } catch (_: ClassNotFoundException) {
            webrtcAvailable = false
            updateStatus("WebRTC missing - voice disabled")
            Log.w(TAG, "WebRTC classes missing; voice disabled")
            Toast.makeText(requireContext(), "WebRTC classes not found. Voice calls will be disabled. Put google-webrtc-1.0.32006.aar into app/libs or enable network Gradle access.", Toast.LENGTH_LONG).show()
        }

        // Buttons in layout
        val btnMute = view.findViewById<ImageButton>(R.id.iv_mute_toggle)
        val btnEnd = view.findViewById<ImageButton>(R.id.iv_end_call)
        val rvMembers = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.rv_voice_members)
        membersAdapter = VoiceMemberAdapter(onClick = {
            // Optionally show member details
        })
        rvMembers?.adapter = membersAdapter
        rvMembers?.layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)

        // Resolve room args
        val serverRoomIdArg = arguments?.getString("roomId")
        val chatRoomIdArg = arguments?.getString("chatRoomId")
        val chatRoomCodeArg = arguments?.getString("roomCode")
        val roomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""
        updateStatus("roomId=$roomId")
        Log.d(TAG, "Resolved roomId=$roomId")

        // Read Janus connection details returned by the join API (RoomFragment passes these as nav args)
        val sessionIdArg = arguments?.getString("sessionId") ?: ""
        val handleIdArg = arguments?.getString("handleId") ?: ""
        val janusRoomIdArg = try { arguments?.getInt("janusRoomId") } catch (_: Exception) { null }

        // Initialize WebSocket service and connect (STOMP)
        socketService = ChatWebSocketService.getInstance(requireContext())
        try {
            socketService?.connect(); updateStatus("socket connecting")
            Log.d(TAG, "socketService.connect() invoked")
        } catch (e: Exception) { updateStatus("socket connect failed: ${e.message}"); Log.e(TAG, "socket connect failed", e) }

        // CONNECTION WATCHDOG: wait briefly for CONNECTED and retry a few times if necessary
        lifecycleScope.launch(Dispatchers.Main) {
            val maxAttempts = 3
            var attempt = 1
            while (attempt <= maxAttempts) {
                updateStatus("waiting for socket (attempt $attempt/$maxAttempts)")
                Log.d(TAG, "Waiting for socket to connect (attempt $attempt)")
                // wait up to 8s for connection
                val ok = withTimeoutOrNull(8000L) {
                    socketService?.connectionState?.first { it is com.example.myapplication.data.chat.websocket.ChatWebSocketService.ConnectionState.CONNECTED }
                    true
                } ?: false

                if (ok) {
                    updateStatus("socket connected (watchdog)")
                    Log.d(TAG, "Socket confirmed connected by watchdog")
                    break
                } else {
                    updateStatus("socket not connected (attempt $attempt)")
                    Log.w(TAG, "Socket not connected after timeout on attempt $attempt")
                    attempt++
                    if (attempt <= maxAttempts) {
                        updateStatus("retrying socket connect (#$attempt)")
                        Log.d(TAG, "Retrying socket connect (#$attempt)")
                        try { socketService?.connect() } catch (e: Exception) { Log.e(TAG, "reconnect failed", e) }
                        delay(1000L)
                    } else {
                        updateStatus("socket failed to connect after $maxAttempts attempts")
                        Log.e(TAG, "Socket failed to connect after $maxAttempts attempts")
                    }
                }
            }
        }

        // Register user in room by sending /app/register over STOMP once socket is CONNECTED.
        // This avoids sending frames before the SockJS/STOMP connection is established (which would be lost).
//        lifecycleScope.launch {
//            var registered = false
//            try {
//                socketService?.connectionState?.collect { state ->
//                    updateStatus("socketState=${state}")
//                    Log.d(TAG, "socketState changed: $state")
//                    if (!registered && state is com.example.myapplication.data.chat.websocket.ChatWebSocketService.ConnectionState.CONNECTED) {
//                        registered = true
//                        updateStatus("socket connected - registering")
//                        Log.d(TAG, "Socket CONNECTED - sending register frame")
//                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                        val userId = email
//                        val payloadMap = mutableMapOf<String, Any>(
//                            "userId" to userId,
//                            "roomId" to roomId
//                        )
//                        if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                        if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                        if (janusRoomIdArg != null) payloadMap["janusRoomId"] = janusRoomIdArg
//
//                        val payload = gson.toJson(payloadMap)
//                        try {
//                            socketService?.sendToDestination("/app/register", payload)
//                            updateStatus("registered to server")
//                            Log.d(TAG, "Registered to server: $payload")
//                        } catch (e: Exception) {
//                            updateStatus("socket register failed: ${e.message}")
//                            Log.e(TAG, "socket register failed", e)
//                            Toast.makeText(requireContext(), "Socket register failed: ${e.message}", Toast.LENGTH_SHORT).show()
//                        }
//
//                        // After registration we can safely setup the peer (if permissions already granted and WebRTC available)
//                        if (webrtcAvailable && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
//                            setupPeer(roomId)
//                        }
//                    }
//                }
//            } catch (_: Exception) { /* ignore collector errors */ }
//        }

        // Ensure audio permission; only setup peer if WebRTC is available
//        if (webrtcAvailable) {
//            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
//                ActivityCompat.requestPermissions(requireActivity(), arrayOf(Manifest.permission.RECORD_AUDIO), AUDIO_PERM_REQUEST)
//                updateStatus("requesting audio permission")
//                Log.d(TAG, "Requesting RECORD_AUDIO permission")
//            } else {
//                updateStatus("audio permission granted - creating peer")
//                Log.d(TAG, "Audio permission already granted - creating peer")
//                setupPeer(roomId)
//            }
//        }

        // Mute toggle: sends /app/mute {userId, roomId, action} and mutes local audio
//        btnMute?.setOnClickListener {
//            lifecycleScope.launch {
//                try {
//                    val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                    val action = if (muted) "unmute" else "mute"
//                    val payloadMap = mutableMapOf<String, Any>(
//                        "userId" to email,
//                        "roomId" to roomId,
//                        "action" to action
//                    )
//                    if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                    if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                    val payload = gson.toJson(payloadMap)
//                    socketService?.sendToDestination("/app/mute", payload)
//                    muted = !muted
//                    // update UI icon - using simple tint flip
//                    try { btnMute.setImageResource(if (muted) R.drawable.ic_mic_off else R.drawable.voice_icon) } catch (_: Exception) {}
//                    // Toggle local audio track (no-op if peer not created)
//                    try { peerManager?.toggleMute(!muted) } catch (_: Exception) {}
//                    updateStatus("mute=${muted}")
//                    Log.d(TAG, "Mute toggled: $muted")
//                    Toast.makeText(requireContext(), if (muted) "Muted" else "Unmuted", Toast.LENGTH_SHORT).show()
//                } catch (e: Exception) {
//                    updateStatus("failed mute: ${e.message}")
//                    Log.e(TAG, "failed to toggle mute", e)
//                    Toast.makeText(requireContext(), "Failed to toggle mute: ${e.message}", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }

        // End call: send /app/unregister and navigate back
//        btnEnd?.setOnClickListener {
//            lifecycleScope.launch {
//                try {
//                    val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                    val payloadMap = mutableMapOf<String, Any>("userId" to email)
//                    if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                    if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                    val payload = gson.toJson(payloadMap)
//                    socketService?.sendToDestination("/app/unregister", payload)
//                    updateStatus("unregistered")
//                    Log.d(TAG, "Sent unregister payload: $payload")
//                } catch (_: Exception) {
//                    // ignore send failures
//                    updateStatus("unregister failed")
//                    Log.w(TAG, "unregister send failed")
//                } finally {
//                    // Disconnect socket and return
//                    try { socketService?.disconnect(); updateStatus("socket disconnected") } catch (_: Exception) {}
//                    Log.d(TAG, "Socket disconnected via end call")
//                    try { requireActivity().onBackPressedDispatcher.onBackPressed() } catch (_: Exception) {}
//                }
//            }
//        }

        // Back arrow behavior
        view.findViewById<View>(R.id.back_arrow)?.setOnClickListener {
            // same as end call
            btnEnd?.performClick()
        }

        // Listen for incoming STOMP bodies and handle answers / ice and speaking events
//        lifecycleScope.launch {
//            socketService?.incomingStomp?.collect { body: String ->
//                try {
//                    Log.d(TAG, "incomingStomp raw body: $body")
//                    val node = gson.fromJson(body, com.google.gson.JsonObject::class.java)
//                    updateStatus("incoming: ${node.get("type") ?: "jsep/ice"}")
//
//                    // speaking event emitted by server: { type: "speaking", userId: "..." }
//                    if (node.has("type")) {
//                        val t = node.get("type").asString
//                        when (t) {
//                            "speaking" -> {
//                                val uid = node.get("userId").asString
//                                membersAdapter.setSpeaking(uid)
//                            }
//                            "stopped_speaking" -> {
//                                membersAdapter.setSpeaking(null)
//                            }
//                            "joined" -> {
//                                // add user to members list
//                                val uid = node.get("userId")?.asString ?: return@collect
//                                val name = node.get("name")?.asString ?: uid
//                                val img = node.get("imageUrl")?.asString
//                                val m = VoiceMember(uid, name, img)
//                                members.removeAll { it.userId == uid }
//                                members.add(m)
//                                membersAdapter.submitList(members.toList())
//                            }
//                            "left" -> {
//                                val uid = node.get("userId")?.asString ?: return@collect
//                                members.removeAll { it.userId == uid }
//                                membersAdapter.submitList(members.toList())
//                            }
//                        }
//                    }
//
//                    if (node.has("jsep") || node.has("sdp")) {
//                        // server answer
//                        val jsep = if (node.has("jsep")) node.getAsJsonObject("jsep") else node
//                        val type = jsep.get("type").asString
//                        val sdpStr = jsep.get("sdp").asString
//                        val sdp = org.webrtc.SessionDescription(org.webrtc.SessionDescription.Type.fromCanonicalForm(type), sdpStr)
//                        peerManager?.setRemoteDescription(sdp)
//                        updateStatus("remote sdp set")
//                        Log.d(TAG, "Remote SDP set: type=$type")
//                    }
//                    if (node.has("candidate")) {
//                        val c = node.getAsJsonObject("candidate")
//                        val sdpMid = c.get("sdpMid").asString
//                        val sdpMLineIndex = c.get("sdpMLineIndex").asInt
//                        val candidate = c.get("candidate").asString
//                        val ice = org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate)
//                        peerManager?.addRemoteIce(ice)
//                        updateStatus("remote ice added")
//                        Log.d(TAG, "Remote ICE added: $candidate")
//                    }
//                } catch (_: Exception) {}
//            }
//        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Ensure user is unregistered and socket disconnected when leaving
//        lifecycleScope.launch {
//            try {
//                val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                val payloadMap = mutableMapOf<String, Any>("userId" to email)
//                val sessionIdArg = arguments?.getString("sessionId") ?: ""
//                val handleIdArg = arguments?.getString("handleId") ?: ""
//                if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                socketService?.sendToDestination("/app/unregister", gson.toJson(payloadMap))
//                updateStatus("onDestroyView: unregistered")
//                Log.d(TAG, "onDestroyView: sent unregister payload")
//            } catch (_: Exception) {}
//            try { socketService?.disconnect(); updateStatus("socket disconnected") } catch (_: Exception) {}
//            try { peerManager?.dispose(); updateStatus("peer disposed") } catch (_: Exception) {}
//            try { audioDetector?.stop(); audioDetector = null } catch (_: Exception) {}
//        }
    }

//    private fun setupPeer(roomId: String) {
//        try {
//            updateStatus("creating peer")
//            peerManager = VoicePeerManager(requireContext())
//            peerManager?.createLocalAudioTrack()
//            peerManager?.createPeerConnection()
//            // Start collecting peer debug state: pcState, ICE candidates and remote SDP events
//            lifecycleScope.launch {
//                try {
//                    peerManager?.pcState?.collect { state ->
//                        updateStatus("pcState=$state")
//                        Log.d(TAG, "PeerConnection state -> $state")
//                    }
//                } catch (_: Exception) {}
//            }
//            lifecycleScope.launch {
//                try {
//                    peerManager?.iceCandidates?.collect { c ->
//                        updateStatus("local ice queued")
//                        Log.d(TAG, "PeerManager emitted local ICE: ${c.sdp}")
//                    }
//                } catch (_: Exception) {}
//            }
//            lifecycleScope.launch {
//                try {
//                    peerManager?.remoteSdp?.collect { sdp ->
//                        updateStatus("remote sdp event: ${sdp.type}")
//                        Log.d(TAG, "PeerManager remote SDP emitted: type=${sdp.type}")
//                    }
//                } catch (_: Exception) {}
//            }
//            updateStatus("peer created")
//            // Start local audio level detector after local audio track exists
//            audioDetector?.stop()
//            audioDetector = com.example.myapplication.data.voice.LocalAudioLevelDetector()
//            var speaking = false
//            audioDetector?.start { level ->
//                val isNowSpeaking = level > 1000f
//                if (isNowSpeaking && !speaking) {
//                    speaking = true
//                    lifecycleScope.launch {
//                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                        membersAdapter.setSpeaking(email)
//                        val payloadMap = mutableMapOf<String, Any>("type" to "speaking", "userId" to email, "roomId" to roomId)
//                        val sessionIdArg = arguments?.getString("sessionId") ?: ""
//                        val handleIdArg = arguments?.getString("handleId") ?: ""
//                        if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                        if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                        socketService?.sendToDestination("/app/speaking", gson.toJson(payloadMap))
//                        updateStatus("speaking")
//                    }
//                } else if (!isNowSpeaking && speaking) {
//                    speaking = false
//                    lifecycleScope.launch {
//                        membersAdapter.setSpeaking(null)
//                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                        val payloadMap = mutableMapOf<String, Any>("type" to "stopped_speaking", "userId" to email, "roomId" to roomId)
//                        val sessionIdArg = arguments?.getString("sessionId") ?: ""
//                        val handleIdArg = arguments?.getString("handleId") ?: ""
//                        if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                        if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                        socketService?.sendToDestination("/app/stopped_speaking", gson.toJson(payloadMap))
//                        updateStatus("stopped_speaking")
//                    }
//                }
//
//            }
//
//            // If webrtc is not available we skip offer/ICE logic; server-side rooms and speaking indicators can still be tested.
//            if (!this@VoiceRoomFragment.webrtcAvailable) return
//
//            // Create offer and send over STOMP to /app/offer with payload { userId, sdp, roomId }
//            lifecycleScope.launch {
//                 val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                 val sessionIdArg = arguments?.getString("sessionId") ?: ""
//                 val handleIdArg = arguments?.getString("handleId") ?: ""
//                 peerManager?.createOffer(onSdpReady = { sdp ->
//                    val sdpMap: Map<String, String> = mapOf("type" to sdp.type.canonicalForm(), "sdp" to sdp.description)
//                    val payloadMap: MutableMap<String, Any> = mutableMapOf("userId" to email, "sdp" to sdpMap, "roomId" to roomId)
//                    if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                    if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                    val json = gson.toJson(payloadMap)
//                    socketService?.sendToDestination("/app/offer", json)
//                    updateStatus("offer sent")
//                    Log.d(TAG, "Offer sent: $json")
//                })
//            }
//
//            // Send local ICE candidates to server
//            lifecycleScope.launch {
//                val sessionIdArg = arguments?.getString("sessionId") ?: ""
//                val handleIdArg = arguments?.getString("handleId") ?: ""
//                peerManager?.iceCandidates?.collect { candidate: org.webrtc.IceCandidate ->
//                     try {
//                        val email = try { UserDataManager.getInstance(requireContext()).getEmail() ?: "" } catch (_: Exception) { "" }
//                        val candObj: Map<String, Any?> = mapOf(
//                            "sdpMid" to candidate.sdpMid,
//                            "sdpMLineIndex" to candidate.sdpMLineIndex,
//                            "candidate" to candidate.sdp
//                        )
//                        val payloadMap: MutableMap<String, Any> = mutableMapOf("userId" to email, "candidate" to candObj, "roomId" to roomId)
//                        if (sessionIdArg.isNotBlank()) payloadMap["sessionId"] = sessionIdArg
//                        if (handleIdArg.isNotBlank()) payloadMap["handleId"] = handleIdArg
//                        socketService?.sendToDestination("/app/ice", gson.toJson(payloadMap))
//                        updateStatus("local ice sent")
//                        Log.d(TAG, "Local ICE sent: $candidate")
//                     } catch (_: Exception) {}
//                 }
//             }
//        } catch (e: Exception) {
//            updateStatus("Failed to setup peer: ${e.message}")
//            Toast.makeText(requireContext(), "Failed to setup peer: ${e.message}", Toast.LENGTH_LONG).show()
//        }
//    }

    @Suppress("DEPRECATION")
//    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
//        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
//        if (requestCode == AUDIO_PERM_REQUEST) {
//            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
//                // user granted audio permission - setup peer
//                val serverRoomIdArg = arguments?.getString("roomId")
//                val chatRoomIdArg = arguments?.getString("chatRoomId")
//                val chatRoomCodeArg = arguments?.getString("roomCode")
//                val roomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""
//                updateStatus("permission granted - creating peer")
//                Log.d(TAG, "Audio permission granted - setting up peer")
//                setupPeer(roomId)
//            } else {
//                updateStatus("permission denied")
//                Toast.makeText(requireContext(), "Microphone permission required for voice", Toast.LENGTH_LONG).show()
//                Log.w(TAG, "Microphone permission denied")
//            }
//        }
//    }

    private fun updateStatus(text: String) {
        try {
            lifecycleScope.launch {
                statusView?.text = "Status: $text"
            }
            Log.d(TAG, "Status -> $text")
        } catch (_: Exception) {}
    }
}
