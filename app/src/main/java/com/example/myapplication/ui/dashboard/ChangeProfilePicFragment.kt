package com.example.myapplication.ui.dashboard

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.common.ImagePickerHelper
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import java.io.File
import com.example.myapplication.data.dashboard.DashboardRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.core.graphics.createBitmap

class ChangeProfilePicFragment : BaseFragment(R.layout.fragment_change_profile_pic) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private var selectedAvatarView: ImageView? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val saveBtn = view.findViewById<androidx.appcompat.widget.AppCompatButton>(R.id.btn_save)
        val backBtn = view.findViewById<ImageView>(R.id.back)

        // Wire back button
        try {
            backBtn?.setOnClickListener {
                try { findNavController().popBackStack() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // ensure initial visual state reflects enabled flag
        try { saveBtn?.alpha = if (saveBtn.isEnabled) 1.0f else 0.6f } catch (_: Exception) {}

        // Load any previously saved image (uploaded URL preferred)
        try {
            val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            val uploadedUrl = prefs.getString("profile_image_url", null) ?: prefs.getString("uploaded_profile_url", null)
            if (!uploadedUrl.isNullOrBlank()) {
                ProfileImageHelper.loadProfileImageIntoView(requireContext(), imgLogo, uploadedUrl)
            } else {
                // fall back to local cached file or drawable
                val profilePath = prefs.getString("profile_image_path", null)
                val profileRes = prefs.getInt("profile_image_res", 0)
                if (!profilePath.isNullOrBlank()) {
                    val f = File(profilePath)
                    if (f.exists()) {
                        Glide.with(this).load(f).signature(ObjectKey(f.absolutePath + "-" + f.lastModified())).circleCrop().into(imgLogo)
                        // enable save since there is a valid image
                        try { saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f } catch (_: Exception) {}
                    }
                } else if (profileRes != 0) {
                    try { Glide.with(this).load(profileRes).circleCrop().into(imgLogo); saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // Instantiate ImagePickerHelper to centralize picking/upload
        val targetSizePx = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
        lateinit var picker: ImagePickerHelper
        picker = ImagePickerHelper(
            fragment = this,
            filename = "profile_pic.png",
            targetSizePx = targetSizePx,
            onBitmapCropped = { bmp: Bitmap ->
                // write cropped bitmap to cache and update shared VM + UI
                try {
                    val path = picker.writeBitmapToCache(bmp)
                    if (!path.isNullOrBlank()) {
                        sharedVm.setImagePath(path)
                        val f = File(path)
                        Glide.with(this).load(f).signature(ObjectKey(f.absolutePath + "-" + f.lastModified())).circleCrop().into(imgLogo)
                        // enable save button when an image is ready
                        try { saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f } catch (_: Exception) {}
                        // clear avatar selection
                        clearAvatarSelection()
                    }
                } catch (_: Exception) {}
            },
            onFileReady = { filePath: String?, contentUri: Uri? ->
                try {
                    if (contentUri != null) sharedVm.setSelectedContentUri(contentUri)
                    if (!filePath.isNullOrBlank()) {
                        sharedVm.setImagePath(filePath)
                        val f = File(filePath)
                        if (f.exists()) {
                            Glide.with(this).load(f).signature(ObjectKey(f.absolutePath + "-" + f.lastModified())).circleCrop().into(imgLogo)
                            // enable save button when a file is selected
                            try { saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f } catch (_: Exception) {}
                            // clear avatar selection
                            clearAvatarSelection()
                        }
                    }
                } catch (_: Exception) {}
            }
        )

        // Wire add icon to picker
        addIcon?.setOnClickListener { picker.pickImageChooser() }

        // Also allow tapping the preview image (imgLogo) to change
        try { imgLogo?.setOnClickListener { picker.pickImageChooser() } } catch (_: Exception) {}

        // Wire preset avatar clicks so choosing a drawable enables Save
        try {
            val avatarIds = listOf(
                R.id.avatar_1, R.id.avatar_2, R.id.avatar_3, R.id.avatar_4,
                R.id.avatar_5, R.id.avatar_6, R.id.avatar_7, R.id.avatar_8
            )
            for (id in avatarIds) {
                val av = view.findViewById<ImageView>(id)
                av?.setOnClickListener {
                    try {
                        // Clear previous selection
                        clearAvatarSelection()

                        // Mark this avatar as selected
                        selectedAvatarView = av
                        av.background = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.circle_stroke)
                        av.setPadding(4, 4, 4, 4)

                        // update preview and shared viewmodel with drawable resource
                        Glide.with(this).load(idToDrawable(id)).circleCrop().into(imgLogo)
                        sharedVm.setDrawableRes(idToDrawable(id))
                        // clear any cached file selection
                        try { sharedVm.setImagePath(null); sharedVm.setSelectedContentUri(null) } catch (_: Exception) {}
                        // enable save
                        try { saveBtn?.isEnabled = true; saveBtn?.alpha = 1.0f } catch (_: Exception) {}
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}

        // Wire Save/Upload action with Snackbar + Retry on failure
        saveBtn?.setOnClickListener {
            lifecycleScope.launch {
                setLoaderVisible(true)
                try {
                    // Use repository method that calls profile/avatar endpoint
                    val repo = DashboardRepository(requireContext())
                    val imgPath = try { sharedVm.selectedImagePath.value } catch (_: Exception) { null }
                    val contentUri = try { sharedVm.selectedContentUri.value } catch (_: Exception) { null }
                    val drawableRes = try { sharedVm.selectedDrawableRes.value } catch (_: Exception) { null }

                    // If user selected a drawable avatar, convert it to a file first
                    val finalPath = if (imgPath.isNullOrBlank() && drawableRes != null && drawableRes != 0) {
                        try {
                            // Convert drawable to bitmap and save to cache
                            val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), drawableRes)
                            val bitmap = try {
                                if (drawable is android.graphics.drawable.BitmapDrawable) {
                                    drawable.bitmap
                                } else {
                                    val w = (drawable?.intrinsicWidth ?: targetSizePx).takeIf { it > 0 } ?: targetSizePx
                                    val h = (drawable?.intrinsicHeight ?: targetSizePx).takeIf { it > 0 } ?: targetSizePx
                                    val bmp = createBitmap(w, h)
                                    val canvas = android.graphics.Canvas(bmp)
                                    drawable?.setBounds(0, 0, canvas.width, canvas.height)
                                    drawable?.draw(canvas)
                                    bmp
                                }
                            } catch (_: Exception) { null }
                            if (bitmap != null) {
                                val cacheFile = File(requireContext().cacheDir, "selected_avatar.png")
                                java.io.FileOutputStream(cacheFile).use { fos ->
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                                    fos.flush()
                                }
                                cacheFile.absolutePath
                            } else imgPath
                        } catch (_: Exception) { imgPath }
                    } else imgPath

                    val result = withContext(Dispatchers.IO) { repo.updateProfilePic(finalPath, contentUri) }
                    if (result.success) {
                        // UserDataManager already updated via repository, UI will auto-refresh via LiveData observers
                        try { findNavController().popBackStack() } catch (_: Exception) {}
                        try { Snackbar.make(requireView(), getString(R.string.saved), Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                    } else {
                        try {
                            val snack = Snackbar.make(requireView(), "Upload failed", Snackbar.LENGTH_INDEFINITE)
                            snack.setAction("Retry") {
                                lifecycleScope.launch {
                                    setLoaderVisible(true)
                                    try {
                                        val retry = withContext(Dispatchers.IO) { repo.updateProfilePic(finalPath, contentUri) }
                                        if (retry.success) {
                                            // UserDataManager already updated, UI auto-refreshes
                                            try { snack.dismiss() } catch (_: Exception) {}
                                            try { findNavController().popBackStack() } catch (_: Exception) {}
                                            try { Snackbar.make(requireView(), getString(R.string.saved), Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                                        } else {
                                            try { Snackbar.make(requireView(), "Upload failed", Snackbar.LENGTH_SHORT).show() } catch (_: Exception) {}
                                        }
                                    } finally { setLoaderVisible(false) }
                                }
                            }
                            snack.show()
                        } catch (_: Exception) {}
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

    private fun idToDrawable(id: Int): Int {
        return when (id) {
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

    // keep existing uploadProfile if needed by other flows (not used after refactor)
}
