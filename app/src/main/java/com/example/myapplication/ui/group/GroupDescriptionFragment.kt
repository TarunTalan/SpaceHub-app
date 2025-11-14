package com.example.myapplication.ui.group

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavOptions
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.groups.model.DataXX
import com.example.myapplication.data.groups.repository.LocalGroupRepository
import com.example.myapplication.data.voice.VoiceRoomRepository
import com.example.myapplication.data.user.UserDataManager
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.InputStream
import android.graphics.Bitmap
import com.example.myapplication.data.network.NetworkModule

class GroupDescriptionFragment : BaseFragment(R.layout.fragment_group_description) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        view.findViewById<ImageView>(R.id.back_arrow)?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        val grpPic = view.findViewById<ImageView>(R.id.grp_pic)
        val grpPicIcon = view.findViewById<ImageView>(R.id.grp_pic_icon)

        fun renderPreview() {
            try {
                val bmp = sharedVm.selectedBitmap.value
                val contentUri = sharedVm.selectedContentUri.value
                val imgPath = sharedVm.selectedImagePath.value
                val drawableRes = sharedVm.selectedDrawableRes.value
                when {
                    bmp != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic.visibility = View.VISIBLE
                        try { Glide.with(this).load(bmp).centerCrop().into(grpPic) } catch (_: Exception) { grpPic.setImageResource(R.drawable.default_comm_icon) }
                    }
                    contentUri != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic.visibility = View.VISIBLE
                        Glide.with(this).load(contentUri).centerCrop().into(grpPic)
                    }
                    !imgPath.isNullOrBlank() -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic.visibility = View.VISIBLE
                        try { Glide.with(this).load(imgPath).centerCrop().into(grpPic) } catch (_: Exception) { grpPic.setImageResource(R.drawable.default_comm_icon) }
                    }
                    drawableRes != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic.visibility = View.VISIBLE
                        grpPic.setImageResource(drawableRes)
                    }
                    else -> {
                        grpPicIcon?.visibility = View.VISIBLE
                        grpPic.visibility = View.INVISIBLE
                        grpPic.setImageResource(R.drawable.default_comm_icon)
                    }
                }
            } catch (_: Exception) {}
        }

        renderPreview()
        try {
            sharedVm.selectedBitmap.observe(viewLifecycleOwner) { renderPreview() }
            sharedVm.selectedContentUri.observe(viewLifecycleOwner) { renderPreview() }
            sharedVm.selectedImagePath.observe(viewLifecycleOwner) { renderPreview() }
            sharedVm.selectedDrawableRes.observe(viewLifecycleOwner) { renderPreview() }
        } catch (_: Exception) {}

        val etGrpDescription = view.findViewById<EditText>(R.id.etGrpDescription)
        val tvCounter = view.findViewById<TextView>(R.id.tvFirstNameCounter)
        etGrpDescription?.addTextChangedListener { text -> val len = text?.length ?: 0; tvCounter?.text = getString(R.string.char_count_slash, len, 150) }
        tvCounter?.text = getString(R.string.char_count_slash, 0, 150)

        view.findViewById<AppCompatButton>(R.id.btn_create_grp)?.setOnClickListener {
            val description = etGrpDescription?.text?.toString()?.trim() ?: ""
            val groupName = sharedVm.communityName.value
            if (groupName.isNullOrBlank()) {
                Snackbar.make(view, "Group name is required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (description.isBlank()) {
                Snackbar.make(view, "Group description is required", Snackbar.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch {
                try { setLoaderVisible(true) } catch (_: Exception) {}

                // Validate name availability (check both communities and groups share same namespace server-side)
                try {
                    val api = NetworkModule.createApiService(requireContext())
                    val checkRes = try { withContext(Dispatchers.IO) { api.checkLocalGroupNameExists(groupName) } } catch (t: Throwable) { null }
                    if (checkRes == null) {
                        try { setLoaderVisible(false) } catch (_: Exception) {}
                        Snackbar.make(view, "Failed to validate name. Please check your connection.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    if (!checkRes.isSuccessful) {
                        try { setLoaderVisible(false) } catch (_: Exception) {}
                        Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    val exists = checkRes.body()?.data?.exists ?: false
                    if (exists) {
                        try { setLoaderVisible(false) } catch (_: Exception) {}
                        Snackbar.make(view, "Name already taken. Choose another name.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                } catch (t: Throwable) {
                    try { setLoaderVisible(false) } catch (_: Exception) {}
                    Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                    return@launch
                }

                val result = createLocalGroup(groupName, description)

                try { setLoaderVisible(false) } catch (_: Exception) {}

                if (result != null) {
                    // result is Result<CreateLocalGroupResponse>
                    if (result.isSuccess) {
                        val created = result.getOrNull()?.data
                        // Best-effort: create a default voice room for this new local group (non-blocking)
                        try {
                            val gid = created?.id
                            if (!gid.isNullOrBlank()) {
                                try {
                                    val appCtx = requireContext().applicationContext
                                    // Schedule a WorkManager job to perform best-effort creation; WorkManager survives process death and supports retries.
                                    val work = androidx.work.OneTimeWorkRequestBuilder<com.example.myapplication.ui.group.GroupDefaultRoomsWorker>()
                                        .setInputData(androidx.work.workDataOf(com.example.myapplication.ui.group.GroupDefaultRoomsWorker.INPUT_GROUP_ID to gid))
                                        .setBackoffCriteria(androidx.work.BackoffPolicy.EXPONENTIAL, 30_000L, java.util.concurrent.TimeUnit.MILLISECONDS)
                                        .build()
                                    androidx.work.WorkManager.getInstance(appCtx).enqueueUniqueWork("create_default_rooms_$gid", androidx.work.ExistingWorkPolicy.KEEP, work)
                                } catch (_: Exception) {
                                    // If scheduling fails, fall back to best-effort immediate attempt (non-blocking)
                                    kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                                        try {
                                            val appCtx = requireContext().applicationContext
                                            val voiceRepo = VoiceRoomRepository.getInstance(appCtx)
                                            val createdBy = try { UserDataManager.getInstance(appCtx).getEmail() ?: "" } catch (_: Exception) { "" }
                                            val repo = LocalGroupRepository.getInstance(appCtx)
                                            val detailsRes = repo.getLocalGroupDetails(gid)
                                            val details: DataXX? = detailsRes.getOrNull()
                                            val chatRoomId: String = details?.chatRoomCode?.takeIf { it.isNotBlank() } ?: (details?.id ?: gid)
                                            try { repo.createChatRoomInGroup(chatRoomId, "General Chat") } catch (_: Exception) {}
                                            try { voiceRepo.createVoiceRoom(chatRoomId, "General", createdBy) } catch (_: Exception) {}
                                        } catch (_: Exception) { }
                                    }
                                }
                            }
                        } catch (_: Exception) { /* swallow */ }
                        // After successful creation, fetch authoritative list from server so DB is updated
                        try {
                            val repo = LocalGroupRepository.getInstance(requireContext())
                            // Fire-and-forget authoritative refresh to update DB; we don't need to block UI
                            lifecycleScope.launch(Dispatchers.IO) {
                                try { repo.getAllLocalGroups() } catch (_: Exception) {}
                            }
                        } catch (_: Exception) {}
                        // Prepare a small bundle with created group details so dashboard/tab can update immediately
                        try {
                            val nav = findNavController()
                            val preview = sharedVm.selectedContentUri.value?.toString() ?: sharedVm.selectedImagePath.value
                            val bundle = Bundle().apply {
                                putString("id", created?.id)
                                putString("name", created?.name)
                                putString("imageUrl", created?.imageUrl)
                                putString("previewUri", preview)
                                putInt("totalMembers", created?.totalMembers ?: 0)
                            }
                            // Prefer previousBackStackEntry (the screen we'll return to) so it receives the bundle when we pop
                            val previous = nav.previousBackStackEntry
                            if (previous != null) {
                                previous.savedStateHandle.set("local_group_created_item", bundle)
                                previous.savedStateHandle.set("refresh_local_groups", true)
                            } else {
                                // Fallbacks: current and explicit dashboard entry if available
                                nav.currentBackStackEntry?.savedStateHandle?.set("local_group_created_item", bundle)
                                nav.currentBackStackEntry?.savedStateHandle?.set("refresh_local_groups", true)
                                runCatching {
                                    val entry = nav.getBackStackEntry(R.id.dashboardFragment)
                                    entry.savedStateHandle.set("local_group_created_item", bundle)
                                    entry.savedStateHandle.set("refresh_local_groups", true)
                                }
                            }
                        } catch (_: Exception) {}
                     }
                      // success
                      Snackbar.make(view, "Local group created", Snackbar.LENGTH_SHORT).show()
                      sharedVm.clear()

                      // Notify dashboard/local-groups tab to refresh
                     try {
                         val nav = findNavController()
                         val previous = nav.previousBackStackEntry
                         if (previous != null) {
                             previous.savedStateHandle.set("local_group_created", true)
                         } else {
                             nav.currentBackStackEntry?.savedStateHandle?.set("local_group_created", true)
                             runCatching {
                                 val entry = nav.getBackStackEntry(R.id.dashboardFragment)
                                 entry.savedStateHandle.set("local_group_created", true)
                             }
                         }
                     } catch (_: Exception) {}

                    // Navigate back to dashboard; clear backstack similar to community flow
                    runCatching {
                        val popped = findNavController().popBackStack(R.id.dashboardFragment, false)
                        if (!popped) {
                            val navOptions = NavOptions.Builder().setPopUpTo(R.id.auth_nav_graph, true).build()
                            navigateWithDelay(R.id.dashboardFragment, null, navOptions = navOptions)
                        }
                    }
                } else {
                    Snackbar.make(view, "Failed to create local group. Try again.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun createLocalGroup(name: String, description: String): Result<com.example.myapplication.data.groups.model.CreateLocalGroupResponse>? {
        return withContext(Dispatchers.IO) {
            try {
                val repo = LocalGroupRepository.getInstance(requireContext())

                // Build parts
                val nameBody = name.toRequestBody("text/plain".toMediaTypeOrNull())
                val descBody = description.toRequestBody("text/plain".toMediaTypeOrNull())

                // Prepare image file part when available
                var imageFilePart: MultipartBody.Part? = null
                val contentUri = sharedVm.selectedContentUri.value
                val bmp = try { sharedVm.selectedBitmap.value } catch (_: Exception) { null }
                if (bmp != null) {
                    try {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val bytes = baos.toByteArray()
                        val reqBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        imageFilePart = MultipartBody.Part.createFormData("imageFile", "group_pic.jpg", reqBody)
                    } catch (_: Exception) { imageFilePart = null }
                } else if (contentUri != null) {
                    try {
                        val resolver = requireContext().contentResolver
                        val mime = resolver.getType(contentUri) ?: "image/jpeg"
                        val input: InputStream? = resolver.openInputStream(contentUri)
                        input?.use { stream ->
                            val bytes = stream.readBytes()
                            val filename = contentUri.lastPathSegment ?: "group_pic.jpg"
                            val reqBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
                            imageFilePart = MultipartBody.Part.createFormData("imageFile", filename, reqBody)
                        }
                    } catch (_: Exception) { imageFilePart = null }
                }

                // If user didn't choose an image, attach the app default image so server gets a default profile
                if (imageFilePart == null) {
                    try {
                        val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.default_comm_icon)
                        val bmp = when (drawable) {
                            is android.graphics.drawable.BitmapDrawable -> drawable.bitmap
                            else -> {
                                val width = drawable?.intrinsicWidth?.takeIf { it > 0 } ?: 256
                                val height = drawable?.intrinsicHeight?.takeIf { it > 0 } ?: 256
                                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                val canvas = android.graphics.Canvas(bitmap)
                                drawable?.setBounds(0, 0, canvas.width, canvas.height)
                                drawable?.draw(canvas)
                                bitmap
                            }
                        }
                        val baos = java.io.ByteArrayOutputStream()
                        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, baos)
                        val bytes = baos.toByteArray()
                        val reqBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        imageFilePart = MultipartBody.Part.createFormData("imageFile", "default_group_icon.jpg", reqBody)
                    } catch (_: Exception) {
                        imageFilePart = null
                    }
                }

                try {
                    val resp = repo.createLocalGroup(nameBody, descBody, imageFilePart)
                    return@withContext resp
                } catch (_: Throwable) {
                    return@withContext null
                }
            } catch (_: Exception) {
                return@withContext null
            }
        }
    }

}