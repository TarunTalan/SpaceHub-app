package com.example.myapplication.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.common.ImagePickerHelper
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import com.example.myapplication.data.dashboard.DashboardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import androidx.core.content.ContextCompat

class ChangeProfilePicFragment : BaseFragment(R.layout.fragment_change_profile_pic) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private var selectedAvatarView: ImageView? = null

    // In-memory selections
    private var selectedBytes: ByteArray? = null
    private var selectedFilename: String? = null
    private var selectedContentUri: Uri? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val saveBtn = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btn_save)
        val backBtn = view.findViewById<ImageView>(R.id.back)

        backBtn?.setOnClickListener { runCatching { findNavController().popBackStack() } }
        saveBtn.alpha = if (saveBtn.isEnabled) 1.0f else 0.6f

        // Load previously saved URL preview (optional)
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val uploadedUrl = prefs.getString("profile_image_url", null) ?: prefs.getString("uploaded_profile_url", null)
            if (!uploadedUrl.isNullOrBlank()) {
                ProfileImageHelper.loadProfileImageIntoView(requireContext(), imgLogo, uploadedUrl)
            }
        } catch (_: Exception) {}

        val targetSizePx = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
        val picker = ImagePickerHelper(
            this,
            targetSizePx,
            onBitmapCropped = { bmp: Bitmap ->
                try {
                    // compress to bytes in-memory (no cache file persistence)
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    selectedBytes = baos.toByteArray()
                    selectedFilename = "profile.png"
                    selectedContentUri = null
                    Glide.with(this).load(bmp).circleCrop().into(imgLogo)
                    saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f
                    clearAvatarSelection()
                } catch (_: Exception) {}
            },
            onFileReady = { filePath: String?, contentUri: Uri? ->
                try {
                    // Prefer contentUri for upload to avoid path persistence
                    selectedContentUri = contentUri
                    selectedBytes = null
                    selectedFilename = null
                    val previewSource: Any? = contentUri ?: filePath
                    if (previewSource != null) {
                        Glide.with(this).load(previewSource).circleCrop().into(imgLogo)
                        saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f
                        clearAvatarSelection()
                    }
                } catch (_: Exception) {}
            }
        )

        addIcon?.setOnClickListener { picker.pickImageChooser() }
        imgLogo?.setOnClickListener { picker.pickImageChooser() }

        // Avatar grid selection
        val avatarIds = listOf(
            R.id.avatar_1, R.id.avatar_2, R.id.avatar_3, R.id.avatar_4,
            R.id.avatar_5, R.id.avatar_6, R.id.avatar_7, R.id.avatar_8
        )
        for (id in avatarIds) {
            val av = view.findViewById<ImageView>(id)
            av?.setOnClickListener {
                try {
                    clearAvatarSelection()
                    selectedAvatarView = av
                    av.background = ContextCompat.getDrawable(requireContext(), R.drawable.circle_stroke)
                    av.setPadding(4, 4, 4, 4)

                    val drawableRes = idToDrawable(id)
                    val drawable = ContextCompat.getDrawable(requireContext(), drawableRes)
                    val w = (drawable?.intrinsicWidth ?: targetSizePx).takeIf { it > 0 } ?: targetSizePx
                    val h = (drawable?.intrinsicHeight ?: targetSizePx).takeIf { it > 0 } ?: targetSizePx
                    val bmp = createBitmap(w, h)
                    val canvas = Canvas(bmp)
                    drawable?.setBounds(0, 0, canvas.width, canvas.height)
                    drawable?.draw(canvas)

                    // compress to bytes
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                    selectedBytes = baos.toByteArray()
                    selectedFilename = "selected_avatar.png"
                    selectedContentUri = null

                    Glide.with(this).load(bmp).circleCrop().into(imgLogo)
                    saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f
                } catch (_: Exception) {}
            }
        }

        saveBtn?.setOnClickListener {
            lifecycleScope.launch {
                setLoaderVisible(true)
                try {
                    val repo = DashboardRepository(requireContext())
                    val result = withContext(Dispatchers.IO) {
                        repo.updateProfilePic(
                            imgPath = null,
                            contentUri = selectedContentUri,
                            bytes = selectedBytes,
                            filename = selectedFilename
                        )
                    }
                    if (result.success) {
                        // Clear selections
                        selectedContentUri = null
                        selectedBytes = null
                        selectedFilename = null
                        try { sharedVm.clear() } catch (_: Exception) {}

                        // Ensure DataStore has latest profile by fetching profile from server
                        try { kotlinx.coroutines.withContext(Dispatchers.IO) { repo.getProfile() } } catch (_: Exception) {}
                        // Persist uploaded url into SharedPreferences as fallback for any UI still reading prefs
                        try {
                            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                            prefs.edit().putString("uploaded_profile_url", result.downloadUrl).apply()
                        } catch (_: Exception) {}

                        // Navigate back; UI updates via DataStore observers
                        withContext(Dispatchers.Main) {
                            findNavController().popBackStack()
                            Snackbar.make(requireActivity().findViewById(android.R.id.content), getString(R.string.saved), Snackbar.LENGTH_SHORT).show()
                        }
                    } else {
                        Snackbar.make(requireView(), "Upload failed", Snackbar.LENGTH_SHORT).show()
                    }
                } finally {
                    setLoaderVisible(false)
                }
            }
        }
    }

    private fun clearAvatarSelection() {
        try {
            selectedAvatarView?.background = null
            selectedAvatarView?.setPadding(0, 0, 0, 0)
            selectedAvatarView = null
        } catch (_: Exception) {}
    }

    private fun idToDrawable(id: Int): Int = when (id) {
        R.id.avatar_1 -> R.drawable.avatar_1
        R.id.avatar_2 -> R.drawable.avatar_2
        R.id.avatar_3 -> R.drawable.avatar_3
        R.id.avatar_4 -> R.drawable.avatar_4
        R.id.avatar_5 -> R.drawable.avatar_5
        R.id.avatar_6 -> R.drawable.avatar_6
        R.id.avatar_7 -> R.drawable.avatar_7
        R.id.avatar_8 -> R.drawable.avatar_8
        else -> R.drawable.default_profile
    }
}
