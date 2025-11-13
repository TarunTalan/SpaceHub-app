package com.example.myapplication.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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

class VoiceRoomFragment : Fragment(R.layout.fragment_voice_room) {
    private var _binding: FragmentVoiceRoomBinding? = null
    private val binding get() = _binding!!
    private val vm: VoiceRoomViewModel by viewModels()
    private val TAG = "VoiceRoomFragment"

    private lateinit var membersAdapter: VoiceMemberAdapter

    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                binding.tvVoiceStatus.text = "Status: audio permission granted"
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
                binding.tvVoiceStatus.text = "Status: permission denied"
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
        lifecycleScope.launchWhenStarted {
            viewLifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                launch {
                    vm.joinState.collect { state ->
                        when (state) {
                            is VoiceRoomViewModel.JoinState.Success -> {
                                val resp = state.resp
                                Log.d(TAG, "Joined janus room: session=${resp.sessionId} handle=${resp.handleId}")
                                // start socket/register using returned session/handle and displayName
                                vm.startSocketAndRegister(roomId, resp.sessionId, resp.handleId, displayNameForJoin)
                                // ensure peer setup and offer
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
                    vm.status.collect { st ->
                        binding.tvVoiceStatus.text = "Status: $st"
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

        // debug button kept (optional)
        try {
            val debugBtn = android.widget.Button(requireContext()).apply {
                text = "DEBUG: Setup Peer"
                setBackgroundColor(android.graphics.Color.parseColor("#CC0000"))
                setTextColor(android.graphics.Color.WHITE)
                setOnClickListener {
                    try {
                        vm.setupPeer(roomId, sessionIdArg, handleIdArg)
                        vm.createAndSendOffer(roomId, sessionIdArg, handleIdArg)
                        Toast.makeText(requireContext(), "Debug: setupPeer invoked", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(requireContext(), "Debug failed: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            (binding.root as? android.view.ViewGroup)?.addView(debugBtn)
        } catch (_: Exception) {}
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
