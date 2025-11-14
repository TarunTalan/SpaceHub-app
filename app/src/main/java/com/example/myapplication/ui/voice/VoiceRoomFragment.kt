package com.example.myapplication.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.databinding.FragmentVoiceRoomBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import android.content.Context
import android.media.AudioManager
import android.widget.ImageButton
import android.graphics.Color
import android.content.res.ColorStateList
import android.media.AudioDeviceInfo
import android.os.Build
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED
import android.util.Log
import android.view.MenuItem
import android.widget.ImageView
import android.widget.TextView

class VoiceRoomFragment : Fragment(R.layout.fragment_voice_room) {
    private var _binding: FragmentVoiceRoomBinding? = null
    private val binding get() = _binding!!
    private val vm: VoiceRoomViewModel by viewModels()
    private val TAG = "VoiceRoomFragment"

    private lateinit var membersAdapter: VoiceMemberAdapter

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                // If fragment had provided session/handle/roomId earlier, ensure peer setup and offer are called
                val serverRoomIdArg = arguments?.getString("roomId")
                val chatRoomIdArg = arguments?.getString("chatRoomId")
                val chatRoomCodeArg = arguments?.getString("roomCode")
                val roomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""
                val sessionIdArg = arguments?.getString("sessionId") ?: ""
                val handleIdArg = arguments?.getString("handleId") ?: ""
                try {
                    vm.setupPeer(roomId, sessionIdArg, handleIdArg)
                    vm.createAndSendOffer(roomId, sessionIdArg, handleIdArg)
                } catch (e: Exception) {
                    Log.w(TAG, "setupPeer/createOffer after permission failed: ${e.message}")
                }
            } else {
                if (!shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                    showMicSettingsDialog()
                } else {
                    Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_LONG).show()
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentVoiceRoomBinding.bind(view)

        membersAdapter = VoiceMemberAdapter()
        binding.rvVoiceMembers.apply {
            adapter = membersAdapter
            layoutManager = androidx.recyclerview.widget.GridLayoutManager(requireContext(), 2)
        }

        // derive args
        val serverRoomIdArg = arguments?.getString("roomId")
        val chatRoomIdArg = arguments?.getString("chatRoomId")
        val chatRoomCodeArg = arguments?.getString("roomCode")
        val roomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""
        val sessionIdArg = arguments?.getString("sessionId") ?: ""
        val handleIdArg = arguments?.getString("handleId") ?: ""
        var displayNameForJoin = arguments?.getString("displayName") ?: ""

        // if displayName blank, try to fetch email async
        if (displayNameForJoin.isBlank()) {
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    displayNameForJoin = UserDataManager.getInstance(requireContext()).getEmail() ?: ""
                } catch (_: Exception) { displayNameForJoin = "" }
                // start socket/register using displayName
                vm.startSocketAndRegister(roomId, sessionIdArg, handleIdArg, displayNameForJoin)
            }
        } else {
            vm.startSocketAndRegister(roomId, sessionIdArg, handleIdArg, displayNameForJoin)
        }

        // If join requires calling joinVoiceRoom (server join via REST), do that and handle joinState
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    vm.joinState.collect { state ->
                        when (state) {
                            is VoiceRoomViewModel.JoinState.Success -> {
                                val resp = state.resp
                                // start socket/register using returned session/handle and displayName
                                vm.startSocketAndRegister(roomId, resp.sessionId, resp.handleId, displayNameForJoin)
                                // ensure peer setup and offer if permission already granted
                                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                                    == PackageManager.PERMISSION_GRANTED
                                ) {
                                    vm.setupPeer(roomId, resp.sessionId, resp.handleId)
                                    vm.createAndSendOffer(roomId, resp.sessionId, resp.handleId)
                                }
                            }
                            is VoiceRoomViewModel.JoinState.Error -> {
                                Toast.makeText(requireContext(), "Failed to join: ${state.msg}", Toast.LENGTH_LONG).show()
                            }
                            else -> {}
                        }
                    }
                }
                launch {
                    vm.members.collect { membersAdapter.submitList(it) }
                }
                launch {
                    vm.speakingUser.collect { membersAdapter.setSpeaking(it) }
                }
                launch {
                    vm.muted.collect { binding.ivMuteToggle.setImageResource(if (it) R.drawable.ic_mic_off else R.drawable.voice_icon) }
                }
            }
        }

        // UI interactions
        binding.ivMuteToggle.setOnClickListener {
            vm.toggleMute(roomId, sessionIdArg, handleIdArg)
        }
        binding.ivEndCall.setOnClickListener {
            vm.endCall(roomId, sessionIdArg, handleIdArg)
            activity?.onBackPressedDispatcher?.onBackPressed()
        }
        binding.backArrow.setOnClickListener { binding.ivEndCall.performClick() }
        binding.ivActions.setOnClickListener {
            checkAndRequestMicPermission(roomId, sessionIdArg, handleIdArg)
        }

        // Auto request microphone permission once on entering the voice room if not already granted
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            checkAndRequestMicPermission(roomId, sessionIdArg, handleIdArg)
        } else {
            // If permission already granted, ensure peer is setup (in case startSocketAndRegister completed earlier)
            try {
                vm.setupPeer(roomId, sessionIdArg, handleIdArg)
            } catch (_: Exception) {}
        }

        // Wire speaker toggle ImageButton from XML (btn_speaker_toggle) and persist preference
        try {
            val speakerBtn = binding.root.findViewById<ImageButton>(R.id.btn_speaker_toggle)
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Load saved preference; if not present, default to the current system speaker state
            val saved = loadSpeakerPref()
            val systemSpeakerState = try { @Suppress("DEPRECATION") am.isSpeakerphoneOn } catch (_: Exception) { false }
            var speakerOn = saved ?: systemSpeakerState

            // Apply saved preference to system (modern API if available)
            applySpeakerPreference(am, speakerOn)

            fun updateSpeakerUi() {
                try {
                    val tint =ColorStateList.valueOf(Color.WHITE)
                    speakerBtn?.imageTintList = tint
                    // Set icon based on state
                    if (vm.muted.value) {
                        speakerBtn?.setImageResource(R.drawable.mute)
                        speakerBtn?.contentDescription = getString(R.string.mute)
                    } else if (speakerOn) {
                        speakerBtn?.setImageResource(R.drawable.voice_speaker)
                        speakerBtn?.contentDescription = getString(R.string.speaker_on)
                    } else {
                        speakerBtn?.setImageResource(R.drawable.phone)
                        speakerBtn?.contentDescription = getString(R.string.speaker_off)
                    }
                } catch (_: Exception) {}
            }

            updateSpeakerUi()
            speakerBtn?.setOnClickListener {
                try {
                    val inflater = layoutInflater
                    val popupView = inflater.inflate(R.layout.layout_speaker_menu_popup, null)
                    val popupWindow = android.widget.PopupWindow(popupView, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, true)
                    popupWindow.isOutsideTouchable = true
                    popupWindow.elevation = 8f

                    // Setup each menu item
                    val itemSpeaker = popupView.findViewById<View>(R.id.item_speaker)
                    val itemPhone = popupView.findViewById<View>(R.id.item_phone)
                    val itemMute = popupView.findViewById<View>(R.id.item_mute)
                    val itemCancel = popupView.findViewById<View>(R.id.item_cancel)

                    fun setupMenuItem(item: View, iconRes: Int, textRes: Int, onClick: () -> Unit) {
                        item.findViewById<ImageView>(R.id.menu_item_icon).setImageResource(iconRes)
                        item.findViewById<TextView>(R.id.menu_item_text).setText(textRes)
                        item.setOnClickListener {
                            onClick()
                            popupWindow.dismiss()
                        }
                    }

                    setupMenuItem(itemSpeaker, R.drawable.voice_speaker, R.string.speaker) {
                        speakerOn = true
                        applySpeakerPreference(am, speakerOn)
                        saveSpeakerPref(speakerOn)
                        speakerBtn.setImageResource(R.drawable.voice_speaker)
                        speakerBtn.contentDescription = getString(R.string.speaker_on)
                    }
                    setupMenuItem(itemPhone, R.drawable.phone, R.string.phone) {
                        speakerOn = false
                        applySpeakerPreference(am, speakerOn)
                        saveSpeakerPref(speakerOn)
                        speakerBtn.setImageResource(R.drawable.phone)
                        speakerBtn.contentDescription = getString(R.string.speaker_off)
                    }
                    setupMenuItem(itemMute, R.drawable.mute, R.string.mute) {
                        try { vm.toggleMute(roomId, sessionIdArg, handleIdArg) } catch (e: Exception) { Log.w(TAG, "Failed to toggle mute from popup: ${e.message}") }
                        speakerBtn.setImageResource(R.drawable.mute)
                        speakerBtn.contentDescription = getString(R.string.mute)
                    }
                    setupMenuItem(itemCancel, R.drawable.cancel, R.string.cancel) {
                        /* just dismiss */
                    }

                    // Show the popup below the speaker button
                    popupWindow.showAsDropDown(speakerBtn)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to show speaker popup: ${e.message}")
                }
            }
        } catch (_: Exception) {
            // safe ignore if view not present
        }
    }

    private fun checkAndRequestMicPermission(roomId: String, sessionId: String, handleId: String) {
        val pm = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
        if (pm == PackageManager.PERMISSION_GRANTED) {
            vm.setupPeer(roomId, sessionId, handleId)
            return
        }
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            showMicRationale { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun showMicRationale(onProceed: () -> Unit) {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Microphone access required")
            .setMessage("SpaceHub voice rooms need access to your microphone. Please allow microphone permission.")
            .setPositiveButton("Allow") { _, _ -> onProceed() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMicSettingsDialog() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Microphone permission blocked")
            .setMessage("Microphone permission has been blocked for this app. To enable, open App Settings.")
            .setPositiveButton("Open Settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", requireContext().packageName, null))
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    // SharedPreferences helpers for speaker preference
    private fun saveSpeakerPref(enabled: Boolean) {
        try {
            val prefs = requireContext().getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("speaker_on", enabled).apply()
        } catch (_: Exception) {}
    }

    private fun loadSpeakerPref(): Boolean? {
        return try {
            val prefs = requireContext().getSharedPreferences("voice_prefs", Context.MODE_PRIVATE)
            if (prefs.contains("speaker_on")) prefs.getBoolean("speaker_on", false) else null
        } catch (_: Exception) { null }
    }


    // Apply speaker preference using modern audio routing when available (API 31+), fallback to legacy setSpeakerphoneOn
    private fun applySpeakerPreference(am: AudioManager, enabled: Boolean) {
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) { // API 31+
                try {
                    val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                    // prefer built-in speaker
                    val speakerDevice = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speakerDevice != null) {
                        // Use reflection to call setCommunicationDevice(AudioDeviceInfo)
                        val method = AudioManager::class.java.getMethod("setCommunicationDevice", AudioDeviceInfo::class.java)
                        if (enabled) {
                            method.invoke(am, speakerDevice)
                            Log.d(TAG, "applySpeakerPreference: routed to built-in speaker (modern API)")
                        } else {
                            // choose earpiece if available
                            val earpiece = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                            if (earpiece != null) {
                                method.invoke(am, earpiece)
                                Log.d(TAG, "applySpeakerPreference: routed to earpiece (modern API)")
                            } else {
                                // fallback: disable speakerphone via legacy API
                                @Suppress("DEPRECATION")
                                am.isSpeakerphoneOn = false
                                Log.d(TAG, "applySpeakerPreference: modern earpiece not found, disabled speakerphone")
                            }
                        }
                        // manage audio focus for audibility
                        if (enabled) requestAudioFocus() else abandonAudioFocus()
                        return
                    }
                } catch (e: Exception) {
                    // reflection failed or method not available; fall back
                    Log.w(TAG, "Modern audio routing failed, falling back: ${e.message}")
                }
            }
            // fallback legacy
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = enabled
            Log.d(TAG, "applySpeakerPreference: legacy setSpeakerphoneOn=$enabled")
            if (enabled) requestAudioFocus() else abandonAudioFocus()
        } catch (e: Exception) {
            Log.w(TAG, "applySpeakerPreference failed: ${e.message}")
        }
    }

    // Request audio focus (API 26+ preferred). Keeps audio audible during call.
    private var audioFocusRequest: AudioFocusRequest? = null
    private fun requestAudioFocus() {
        try {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attr = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(attr)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener { /* no-op */ }
                    .build()
                val res = am.requestAudioFocus(req)
                if (res == AUDIOFOCUS_REQUEST_GRANTED) {
                    audioFocusRequest = req
                    Log.d(TAG, "Audio focus granted")
                } else {
                    Log.w(TAG, "Audio focus request denied: $res")
                }
            } else {
                // legacy request
                val res = am.requestAudioFocus(null, AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                Log.d(TAG, "legacy audio focus request result=$res")
            }
        } catch (e: Exception) {
            Log.w(TAG, "requestAudioFocus failed: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            val am = requireContext().getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { am.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                am.abandonAudioFocus(null)
            }
            Log.d(TAG, "Audio focus abandoned")
        } catch (e: Exception) {
            Log.w(TAG, "abandonAudioFocus failed: ${e.message}")
        }
    }

    override fun onDestroyView() {
        try {
            // Ensure we unregister and cleanup when leaving the fragment
            val serverRoomIdArg = arguments?.getString("roomId")
            val chatRoomIdArg = arguments?.getString("chatRoomId")
            val chatRoomCodeArg = arguments?.getString("roomCode")
            val roomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""
            val sessionIdArg = arguments?.getString("sessionId") ?: ""
            val handleIdArg = arguments?.getString("handleId") ?: ""
            vm.endCall(roomId, sessionIdArg, handleIdArg)
        } catch (_: Exception) {}
        super.onDestroyView()
        _binding = null
    }
}
