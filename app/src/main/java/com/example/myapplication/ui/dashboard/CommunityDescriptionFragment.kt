package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.util.Log
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

    companion object {
        // Guard in-memory set to avoid duplicate createChatRoom requests for same community/parent/name
        private val inFlightChatCreates = mutableSetOf<String>()
    }

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
                        withContext(Dispatchers.IO) {
                            val repo = CommunityRepository.getInstance(requireContext())
                            val userData = UserDataManager.getInstance(requireContext())

                            val defaultChatRoomName = "General"
                            val defaultVoiceRoomName = "General Voice"

                            try {
                                val alreadyChatCreated = runCatching {
                                    userData.isDefaultRoomCreated(data.communityId, defaultChatRoomName, "chat")
                                }.getOrDefault(false)

                                val alreadyVoiceCreated = runCatching {
                                    userData.isDefaultRoomCreated(data.communityId, defaultChatRoomName, "voice")
                                }.getOrDefault(false)

                                if (!alreadyChatCreated || !alreadyVoiceCreated) {
                                    // Ensure parent room exists (best-effort)
                                    runCatching { repo.createRoom(data.communityId, defaultChatRoomName) }

                                    // repo.getAllRooms returns Result<List<DataRoom>> so unwrap safely
                                    val roomsResult = runCatching { repo.getAllRooms(data.communityId) }.getOrNull()
                                    val allRooms = roomsResult?.getOrNull() ?: emptyList()

                                    val parentRoom = allRooms.firstOrNull { it.name.equals(defaultChatRoomName, true) }
                                    val parentId = parentRoom?.id

                                    if (!parentId.isNullOrBlank()) {
                                        // Avoid creating the same chat twice. First, try to find an existing chat under this parent.
                                        val roomCode = parentRoom.roomCode.takeIf { !it.isNullOrBlank() } ?: parentId
                                        var chatId: String? = null

                                        // 1) Try to find existing chat by calling summary
                                        try {
                                            val chatSummaryRes = runCatching { repo.getChatRoomSummary(roomCode) }.getOrNull()
                                            chatId = chatSummaryRes?.getOrNull()?.firstOrNull { it.name.equals(defaultChatRoomName, true) }?.id
                                            if (!chatId.isNullOrBlank()) {
                                                // mark as created so we don't attempt to recreate later
                                                runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                            }
                                        } catch (_: Exception) { }

                                        // 2) If not found and not already created, attempt to create once
                                        if (chatId.isNullOrBlank() && !alreadyChatCreated) {
                                            val createKey = "${data.communityId}:$parentId:$defaultChatRoomName"
                                            var createdChat: com.example.myapplication.data.chat_room.model.DataChatRoom? = null
                                            synchronized(inFlightChatCreates) {
                                                if (!inFlightChatCreates.contains(createKey)) inFlightChatCreates.add(createKey) else createdChat = null
                                            }
                                            if (inFlightChatCreates.contains(createKey)) {
                                                try {
                                                    val createRes = runCatching { repo.createChatRoom(data.communityId, parentId, defaultChatRoomName) }.getOrNull()
                                                    createdChat = createRes?.getOrNull()
                                                    chatId = createdChat?.id
                                                    if (!chatId.isNullOrBlank()) {
                                                        runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                                    }
                                                } finally {
                                                    synchronized(inFlightChatCreates) { inFlightChatCreates.remove(createKey) }
                                                }
                                            }
                                        }

                                        // 3) Create voice room only if we have a chatId and voice room not created yet
                                        // Create voice room keyed by the parent/server room id (not the chat child id)
                                        if (!parentId.isNullOrBlank() && !alreadyVoiceCreated) {
                                            val creatorEmail = runCatching { userData.getEmail() }.getOrNull() ?: ""
                                            val voiceRepo = com.example.myapplication.data.voice.VoiceRoomRepository.getInstance(requireContext())
                                            Log.d("CommunityDesc", "creating voice room for parentId=$parentId")
                                            val voiceRes = runCatching { voiceRepo.createVoiceRoom(chatRoomId = parentId, roomName = defaultVoiceRoomName, createdBy = creatorEmail) }.getOrNull()
                                            if (voiceRes?.isSuccess == true) {
                                                Log.d("CommunityDesc", "voice room created for parentId=$parentId")
                                                runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "voice") }
                                            } else {
                                                Log.w("CommunityDesc", "voice room creation failed for parentId=$parentId: ${voiceRes?.exceptionOrNull()?.message}")
                                            }
                                        }
                                     } else {
                                        // Parent room not visible yet; retry a few times to allow backend eventual consistency.
                                        if (!alreadyChatCreated || !alreadyVoiceCreated) {
                                            try {
                                                var resolvedParentId: String? = null
                                                var resolvedRoomCode: String? = null
                                                repeat(4) {
                                                    val retryRoomsRes = runCatching { repo.getAllRooms(data.communityId) }.getOrNull()
                                                    val retryRooms = retryRoomsRes?.getOrNull() ?: emptyList()
                                                    val retryFound = retryRooms.firstOrNull { it.name.equals(defaultChatRoomName, true) }
                                                    if (retryFound != null) {
                                                        resolvedParentId = retryFound.id
                                                        resolvedRoomCode = retryFound.roomCode.takeIf { it.isNotBlank() } ?: retryFound.id
                                                        return@repeat
                                                    }
                                                    kotlinx.coroutines.delay(300)
                                                }

                                                if (!resolvedParentId.isNullOrBlank()) {
                                                    // Use same single-create logic as above
                                                    var chatId: String? = null
                                                    try {
                                                        val roomCodeToQuery = resolvedRoomCode ?: resolvedParentId
                                                        val chatSummaryRes = runCatching { repo.getChatRoomSummary(roomCodeToQuery) }.getOrNull()
                                                        chatId = chatSummaryRes?.getOrNull()?.firstOrNull { it.name.equals(defaultChatRoomName, true) }?.id
                                                        if (!chatId.isNullOrBlank()) runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                                    } catch (_: Exception) { }

                                                    if (chatId.isNullOrBlank() && !alreadyChatCreated) {
                                                        val createKey = "${data.communityId}:$resolvedParentId:$defaultChatRoomName"
                                                        val acquired = synchronized(inFlightChatCreates) {
                                                            if (!inFlightChatCreates.contains(createKey)) { inFlightChatCreates.add(createKey); true } else false
                                                        }
                                                        if (acquired) {
                                                            try {
                                                                Log.d("CommunityDesc", "creating chat (retry) for $createKey")
                                                                val createdChatRes = runCatching { repo.createChatRoom(data.communityId, resolvedParentId, defaultChatRoomName) }.getOrNull()
                                                                val created = createdChatRes?.getOrNull()
                                                                chatId = created?.id
                                                                if (!chatId.isNullOrBlank()) runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                                            } finally {
                                                                synchronized(inFlightChatCreates) { inFlightChatCreates.remove(createKey) }
                                                            }
                                                        } else {
                                                            Log.d("CommunityDesc", "createChat already in-flight for $createKey (retry), skipping")
                                                        }
                                                    }

                                                    // Create voice room using the resolved parent/server room id
                                                    if (!resolvedParentId.isNullOrBlank() && !alreadyVoiceCreated) {
                                                        val creatorEmail = runCatching { userData.getEmail() }.getOrNull() ?: ""
                                                        val voiceRepo = com.example.myapplication.data.voice.VoiceRoomRepository.getInstance(requireContext())
                                                        Log.d("CommunityDesc", "creating voice room (retry) for parentId=$resolvedParentId")
                                                        val voiceRes = runCatching { voiceRepo.createVoiceRoom(chatRoomId = resolvedParentId, roomName = defaultVoiceRoomName, createdBy = creatorEmail) }.getOrNull()
                                                        if (voiceRes?.isSuccess == true) {
                                                            Log.d("CommunityDesc", "voice room (retry) created for parentId=$resolvedParentId")
                                                            runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "voice") }
                                                        } else {
                                                            Log.w("CommunityDesc", "voice room (retry) failed for parentId=$resolvedParentId: ${voiceRes?.exceptionOrNull()?.message}")
                                                        }
                                                    }
                                                } else {
                                                    // give up after retries and mark as created to avoid repeated attempts
                                                    if (!alreadyChatCreated) runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "chat") }
                                                    if (!alreadyVoiceCreated) runCatching { userData.markDefaultRoomCreatedBlocking(data.communityId, defaultChatRoomName, "voice") }
                                                }
                                            } catch (_: Exception) { /* swallow */ }
                                        }
                                    }
                                }
                            } catch (_: Exception) { /* swallow */ }

                        } // end withContext

                        // UI and navigation
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
