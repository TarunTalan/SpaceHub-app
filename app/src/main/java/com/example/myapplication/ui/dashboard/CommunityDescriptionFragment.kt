package com.example.myapplication.ui.dashboard

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.AppCompatButton
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import android.webkit.MimeTypeMap
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class CommunityDescriptionFragment : BaseFragment(R.layout.fragment_comm_description) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Back navigation
        view.findViewById<ImageView>(R.id.back_arrow)?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        // Load preview image if available
        try {
            val commPic = view.findViewById<ImageView>(R.id.comm_pic)
            // Prefer the original content Uri from the picker (keeps the selected image, not cache)
            val contentUri = sharedVm.selectedContentUri.value
            if (contentUri != null) {
                Glide.with(this).load(contentUri).centerCrop().into(commPic)
            } else {
                val imagePath = sharedVm.selectedImagePath.value
                if (!imagePath.isNullOrBlank()) {
                    val file = File(imagePath)
                    if (file.exists()) Glide.with(this).load(file).centerCrop().into(commPic) else Glide.with(this)
                        .load(R.drawable.default_comm_icon).into(commPic)
                } else {
                    // if nothing selected, ensure default
                }
            }
        } catch (_: Exception) {}

        val etCommDescription = view.findViewById<EditText>(R.id.etCommDescription)
        val tvCounter = view.findViewById<TextView>(R.id.tvFirstNameCounter)

        etCommDescription?.addTextChangedListener { text -> tvCounter?.text = "${text?.length ?: 0} / 150" }
        tvCounter?.text = "0 / 150"

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
                val success = createCommunity(communityName, description)
                try { setLoaderVisible(false) } catch (_: Exception) {}

                if (success) {
                    Snackbar.make(view, "Community created successfully!", Snackbar.LENGTH_SHORT).show()
                    sharedVm.clear()
                    try { findNavController().popBackStack(R.id.dashboardFragment, false) } catch (_: Exception) { try { findNavController().navigateUp() } catch (_: Exception) {} }
                } else {
                    Snackbar.make(view, "Failed to create community. Please try again.", Snackbar.LENGTH_LONG).show()
                }
            }
        }
    }

    private suspend fun createCommunity(name: String, description: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val api = NetworkModule.createApiService(requireContext())

                val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
                val email = prefs.getString("email", "") ?: ""

                val nameBody = name.toRequestBody("text/plain".toMediaTypeOrNull())
                val descriptionBody = description.toRequestBody("text/plain".toMediaTypeOrNull())
                val emailBody = email.toRequestBody("text/plain".toMediaTypeOrNull())

                // Build image file part if an image was selected
                val imagePath = sharedVm.selectedImagePath.value
                var imageFilePart: MultipartBody.Part? = null
                var imageUriText = ""

                if (!imagePath.isNullOrBlank()) {
                    val imgFile = File(imagePath)
                    if (imgFile.exists()) {
                        val contentUri = sharedVm.selectedContentUri.value
                        val mimeFromResolver = try { contentUri?.let { requireContext().contentResolver.getType(it) } } catch (_: Exception) { null }
                        val ext = imgFile.extension.takeIf { it.isNotBlank() } ?: MimeTypeMap.getFileExtensionFromUrl(imgFile.absolutePath)
                        val mimeFromExt = ext?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                        val mime = mimeFromResolver ?: mimeFromExt ?: "image/jpeg"

                        val reqFile = imgFile.asRequestBody(mime.toMediaTypeOrNull())
                        imageFilePart = MultipartBody.Part.createFormData("imageFile", imgFile.name, reqFile)
                        // send filename as image_uri (server may expect a URL; this preserves previous behavior)
                        imageUriText = imgFile.name
                    }
                }

                val imageUriBody = imageUriText.toRequestBody("text/plain".toMediaTypeOrNull())

                val response = try {
                    api.createCommunity(
                        name = nameBody,
                        description = descriptionBody,
                        createdByEmail = emailBody,
                        imageUri = imageUriBody,
                        imageFile = imageFilePart
                    )
                } catch (_: Exception) { null }

                return@withContext (response != null && response.isSuccessful)
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }
}