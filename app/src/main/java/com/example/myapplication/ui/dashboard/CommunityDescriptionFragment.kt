package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.data.community.model.Community
import com.example.myapplication.data.dashboard.model.CreateCommunityResponse
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.data.community.repository.CommunityRepository
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.community.viewmodel.CommunityViewModel
import com.bumptech.glide.Glide
import com.example.myapplication.data.user.UserDataManager
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.InputStream

class CommunityDescriptionFragment : BaseFragment(R.layout.fragment_comm_description) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private val communityVm: CommunityViewModel by viewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back navigation
        view.findViewById<ImageView>(R.id.back_arrow)?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        val commPic = view.findViewById<ImageView>(R.id.comm_pic)
        val commPicIcon = view.findViewById<ImageView>(R.id.comm_pic_icon)

        fun renderPreview() {
            try {
                val contentUri = sharedVm.selectedContentUri.value
                if (contentUri != null) {
                    // show image, hide icon
                    commPicIcon?.visibility = View.GONE
                    commPic.visibility = View.VISIBLE
                    Glide.with(this)
                        .load(contentUri)
                        .centerCrop()
                        .into(commPic)
                } else {
                    // no selection: show icon, hide image view
                    commPicIcon?.visibility = View.VISIBLE
                    commPic.visibility = View.INVISIBLE
                    commPic.setImageResource(R.drawable.default_comm_icon)
                }
            } catch (_: Exception) { }
        }

        // Initial render
        renderPreview()

        // React to selection changes
        try { sharedVm.selectedContentUri.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) { }

        val etCommDescription = view.findViewById<EditText>(R.id.etCommDescription)
        val tvCounter = view.findViewById<TextView>(R.id.tvFirstNameCounter)

        etCommDescription?.addTextChangedListener { text ->
            val len = text?.length ?: 0
            tvCounter?.text = getString(R.string.char_count_slash, len, 150)
        }
        tvCounter?.text = getString(R.string.char_count_slash, 0, 150)

        view.findViewById<AppCompatButton>(R.id.btn_create_comm)?.setOnClickListener {
            val description = etCommDescription?.text?.toString()?.trim() ?: ""
            val communityName = sharedVm.communityName.value
            if (communityName.isNullOrBlank()) {
                Snackbar.make(view, "Community name is required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (description.isBlank()) {
                Snackbar.make(view, "Community description is required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            sharedVm.setCommunityDescription(description)

            lifecycleScope.launch {
                try { setLoaderVisible(true) } catch (_: Exception) {}
                val response = createCommunity(communityName, description)
                try { setLoaderVisible(false) } catch (_: Exception) {}

                if (response != null && (response.isSuccessful || response.code() == 201)) {
                    val body = response.body()
                    val data = body?.data
                    if (data != null) {
                        // Persist into Room so Dashboard updates immediately
                        val entity = Community(
                            communityId = data.communityId,
                            name = data.name,
                            description = description,
                            profilePicUrl = data.imageUrl,
                            profilePicLocalPath = null,
                            coverPhotoUrl = null,
                            coverPhotoLocalPath = null,
                            category = null,
                            isPrivate = false,
                            creatorId = null,
                            creatorName = null,
                            isOwner = true,
                            isMember = true,
                            memberCount = 1
                        )
                        communityVm.saveCommunity(entity)

                        // Create default room "General" (non-blocking UI; failures are tolerated)
                        try {
                            withContext(Dispatchers.IO) {
                                val repo = CommunityRepository.getInstance(requireContext())
                                val userData = UserDataManager.getInstance(requireContext())

                                // Use a stable name as roomId placeholder for DataStore marker (we don't have server id here)
                                val defaultChatRoomName = "General"
                                val defaultVoiceRoomName = "General Voice"

                                // Create chat parent room + child chat room + voice room, but only once per community
                                try {
                                    val alreadyCreated = runCatching {
                                        userData.isDefaultRoomCreated(data.communityId, defaultChatRoomName, "chat")
                                    }.getOrDefault(false)

                                    if (!alreadyCreated) {
                                        // Create parent room (server-side). This API call may succeed even if room already exists.
                                        runCatching { repo.createRoom(data.communityId, defaultChatRoomName) }

                                        // Fetch server rooms to find the newly created parent room id
                                        val allRoomsRes = runCatching { repo.getAllRooms(data.communityId) }.getOrNull()
                                        val parentRoomId = allRoomsRes?.getOrNull()?.firstOrNull { it.name.equals(defaultChatRoomName, true) }?.id

                                        if (!parentRoomId.isNullOrBlank()) {
                                            // Create a chat room under the parent room (returns DataChatRoom with id)
                                            val chatRoomRes = runCatching { repo.createChatRoom(data.communityId, parentRoomId, defaultChatRoomName) }.getOrNull()
                                            val createdChat = chatRoomRes?.getOrNull()

                                            // If chat room created successfully, attempt to create a voice room tied to that chat room id
                                            if (createdChat != null) {
                                                try {
                                                    val creatorEmail = runCatching { userData.getEmail() }.getOrNull() ?: ""
                                                    // Best-effort create voice room
                                                    val voiceRepo = com.example.myapplication.data.voice.VoiceRoomRepository.getInstance(requireContext())
                                                    runCatching { voiceRepo.createVoiceRoom(chatRoomId = createdChat.id, roomName = defaultVoiceRoomName, createdBy = creatorEmail) }
                                                    // mark voice default created
                                                    runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "voice") }
                                                } catch (_: Exception) { }
                                            }

                                            // Mark chat default created
                                            runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                        } else {
                                            // Could not resolve parent room; still mark chat as created to avoid repeated attempts
                                            runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                        }
                                    }
                                } catch (_: Exception) { }
                            }
                        } catch (_: Exception) { }

                        Snackbar.make(view, "Community created successfully!", Snackbar.LENGTH_SHORT).show()
                        sharedVm.clear()
                        // Navigate back to dashboard (pop if present, otherwise navigate)
                        runCatching {
                            val popped = findNavController().popBackStack(R.id.dashboardFragment, false)
                            if (!popped) {
                                // ensure we land on dashboard and clear intermediate screens
                                val navOptions = NavOptions.Builder()
                                    .setPopUpTo(R.id.auth_nav_graph, true)
                                    .build()
                                findNavController().navigate(R.id.dashboardFragment, null, navOptions)
                            }
                        }
                    } else {
                        Snackbar.make(view, "Community created but response missing data.", Snackbar.LENGTH_LONG).show()
                    }
                } else {
                    Snackbar.make(view, "Failed to create community. Please try again.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun createCommunity(name: String, description: String): Response<CreateCommunityResponse>? {
        return withContext(Dispatchers.IO) {
            try {
                val api = NetworkModule.createApiService(requireContext())

                // Prefer DataStore email (authoritative). Fallback to prefs if missing.
                val userData = UserDataManager.getInstance(requireContext())
                val emailDs = userData.getEmail()
                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val emailPref = prefs.getString("email", null)
                val email = when {
                    !emailDs.isNullOrBlank() -> emailDs
                    !emailPref.isNullOrBlank() -> emailPref
                    else -> null
                }
                if (email.isNullOrBlank()) return@withContext null

                val nameBody = name.toRequestBody("text/plain".toMediaTypeOrNull())
                val descriptionBody = description.toRequestBody("text/plain".toMediaTypeOrNull())
                val emailBody = email.toRequestBody("text/plain".toMediaTypeOrNull())

                // Build image file part if an image content Uri is selected
                val contentUri = sharedVm.selectedContentUri.value
                var imageFilePart: MultipartBody.Part? = null
                var imageUriText = ""

                if (contentUri != null) {
                    try {
                        val resolver = requireContext().contentResolver
                        val mime = resolver.getType(contentUri) ?: "image/jpeg"
                        val input: InputStream? = resolver.openInputStream(contentUri)
                        input?.use { stream ->
                            val bytes = stream.readBytes()
                            val filename = contentUri.lastPathSegment ?: "community_pic.jpg"
                            val reqBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
                            imageFilePart = MultipartBody.Part.createFormData("imageFile", filename, reqBody)
                            imageUriText = filename
                        }
                    } catch (_: Exception) { imageFilePart = null }
                }

                val imageUriBody = imageUriText.toRequestBody("text/plain".toMediaTypeOrNull())

                return@withContext try {
                    api.createCommunity(
                        name = nameBody,
                        description = descriptionBody,
                        createdByEmail = emailBody,
                        imageUri = imageUriBody,
                        imageFile = imageFilePart
                    )
                } catch (_: Exception) { null }
            } catch (_: Exception) {
                return@withContext null
            }
        }
    }
}