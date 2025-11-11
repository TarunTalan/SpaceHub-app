package com.example.myapplication.ui.voice

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.R
import com.example.myapplication.data.user.UserDataManager
import kotlinx.coroutines.launch

class VoiceRoomFragment : Fragment(R.layout.fragment_voice_room) {
    private val vm: VoiceRoomViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Use the existing image button as the create action in this layout
        val createBtn = view.findViewById<ImageButton>(R.id.iv_end_call)

        // Prefer the server-side `roomId` nav-arg for voice APIs. Fall back to older arg names for compatibility.
        val serverRoomIdArg = arguments?.getString("roomId")
        val chatRoomIdArg = arguments?.getString("chatRoomId")
        val chatRoomCodeArg = arguments?.getString("roomCode")
        val chatRoomId = serverRoomIdArg ?: chatRoomIdArg ?: chatRoomCodeArg ?: ""

        // Observe create state from VM to update UI accordingly
        lifecycleScope.launch {
            vm.createState.collect { state ->
                when (state) {
                    is VoiceRoomViewModel.CreateState.Loading -> {
                        createBtn?.isEnabled = false
                    }
                    is VoiceRoomViewModel.CreateState.Success -> {
                        createBtn?.isEnabled = true
                        Toast.makeText(requireContext(), "Voice room created", Toast.LENGTH_SHORT).show()
                        try { requireActivity().onBackPressedDispatcher.onBackPressed() } catch (_: Exception) {}
                    }
                    is VoiceRoomViewModel.CreateState.Error -> {
                        createBtn?.isEnabled = true
                        Toast.makeText(requireContext(), state.msg, Toast.LENGTH_SHORT).show()
                    }
                    is VoiceRoomViewModel.CreateState.Idle -> {
                        createBtn?.isEnabled = true
                    }
                }
            }
        }

        createBtn?.setOnClickListener {
            // Prompt the user for a room name using a simple AlertDialog with an EditText
            val input = EditText(requireContext())
            input.hint = "Room name"
            AlertDialog.Builder(requireContext())
                .setTitle("Create voice room")
                .setView(input)
                .setPositiveButton(android.R.string.ok) { dlg, _ ->
                    val name = input.text?.toString()?.trim().orEmpty()
                    if (name.isBlank()) {
                        Toast.makeText(requireContext(), "Name is required", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    // Obtain creator email from UserDataManager (suspend) and call ViewModel.
                    lifecycleScope.launch {
                        val createdByEmail = try {
                            UserDataManager.getInstance(requireContext()).getEmail() ?: ""
                        } catch (_: Exception) {
                            ""
                        }

                        vm.createVoiceRoom(chatRoomId = chatRoomId, roomName = name, createdBy = createdByEmail)
                    }
                    try { dlg.dismiss() } catch (_: Exception) {}
                }
                .setNegativeButton(android.R.string.cancel) { dlg, _ -> try { dlg.dismiss() } catch (_: Exception) {} }
                .show()
        }
    }
}
