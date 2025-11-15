package com.example.myapplication.ui.group

import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.myapplication.ui.common.ImagePickerHelper
import com.bumptech.glide.Glide
import com.example.myapplication.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class GroupNamePicFragment : BaseFragment(R.layout.fragment_group_name_pic) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private var imagePicker: ImagePickerHelper? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val back = view.findViewById<ImageView>(R.id.back_arrow)
        back?.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }

        val grpPic = view.findViewById<ImageView>(R.id.grp_pic)
        val grpPicIcon = view.findViewById<ImageView>(R.id.grp_pic_icon)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val grpPicFrame = view.findViewById<FrameLayout>(R.id.grp_pic_frame)
        val etName = view.findViewById<android.widget.EditText>(R.id.etGrpName)
        val btnNext = view.findViewById<View>(R.id.btn_create_grp)

        // Ensure clickable so touches are received
        grpPicFrame?.isClickable = true
        grpPicFrame?.isFocusable = true
        addIcon?.isClickable = true
        grpPicIcon?.isClickable = true

        // Render preview if a URI or cached image path is already present in shared VM
        fun renderPreview() {
            try {
                val bmp = sharedVm.selectedBitmap.value
                val contentUri = sharedVm.selectedContentUri.value
                val imgPath = sharedVm.selectedImagePath.value
                when {
                    bmp != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic?.visibility = View.VISIBLE
                        try { Glide.with(this).load(bmp).circleCrop().into(grpPic) } catch (_: Exception) {}
                        // Set solid outline when image selected (use foreground so it draws above image)
                        try { grpPicFrame?.foreground = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_solid_white) } catch (_: Exception) {}
                    }
                    contentUri != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic?.visibility = View.VISIBLE
                        try { Glide.with(this).load(contentUri).circleCrop().into(grpPic) } catch (_: Exception) {}
                        try { grpPicFrame?.foreground = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_solid_white) } catch (_: Exception) {}
                    }
                    !imgPath.isNullOrBlank() -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic?.visibility = View.VISIBLE
                        try { Glide.with(this).load(imgPath).circleCrop().into(grpPic) } catch (_: Exception) {}
                        try { grpPicFrame?.foreground = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_solid_white) } catch (_: Exception) {}
                    }
                    else -> {
                        grpPicIcon?.visibility = View.VISIBLE
                        grpPic?.visibility = View.INVISIBLE
                        grpPic?.setImageResource(R.drawable.default_profile)
                        try { grpPicFrame?.foreground = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_dashed_white) } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // Clear any previous selection (for example user's profile picture) so creating a group
        // starts with a neutral placeholder rather than a prefilled profile image.
        try {
            sharedVm.setSelectedBitmap(null)
            sharedVm.setSelectedContentUri(null)
            sharedVm.setImagePath(null)
            sharedVm.setDrawableRes(null)
        } catch (_: Exception) {}

        renderPreview()
        sharedVm.selectedBitmap.observe(viewLifecycleOwner) { renderPreview() }
        sharedVm.selectedContentUri.observe(viewLifecycleOwner) { renderPreview() }
        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { renderPreview() }

        // Initialize and store ImagePickerHelper as a field
        val targetSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
        imagePicker = ImagePickerHelper(
            this,
            targetSize,
            onBitmapCropped = { bmp: Bitmap ->
                try {
                    // Do not write to cache; keep bitmap in-memory in shared VM
                    sharedVm.setSelectedBitmap(bmp)
                    try { Glide.with(this).load(bmp).circleCrop().into(grpPic); grpPicIcon?.visibility = View.GONE } catch (_: Exception) {}
                } catch (_: Exception) {}
            },
            onFileReady = { filePath: String?, contentUri ->
                try {
                    // For gallery selection we just set content uri (no caching)
                    if (contentUri != null) sharedVm.setSelectedContentUri(contentUri)
                    try { Glide.with(this).load(contentUri ?: filePath).circleCrop().into(grpPic); grpPicIcon?.visibility = View.GONE } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
        )

        val openPicker = {
            imagePicker?.pickImageChooser()
        }

        grpPicFrame?.setOnClickListener { openPicker() }
        addIcon?.setOnClickListener { openPicker() }
        grpPicIcon?.setOnClickListener { openPicker() }

        // Next: validate name via server then save and navigate
        btnNext?.setOnClickListener {
            val name = etName?.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Group name is required", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Validate name before proceeding
            viewLifecycleOwner.lifecycleScope.launch {
                try { setLoaderVisible(true) } catch (_: Exception) {}
                try {
                    val api = NetworkModule.createApiService(requireContext())
                    val checkRes = try { withContext(Dispatchers.IO) { api.checkLocalGroupNameExists(name) } } catch (_: Throwable) { null }
                    if (checkRes == null) {
                        Snackbar.make(view, "Failed to validate name. Check connection.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    if (!checkRes.isSuccessful) {
                        Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    val body = checkRes.body()
                    if (body == null || body.status != 200 || body.data == null) {
                        Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }
                    val exists = body.data.exists
                    if (exists) {
                        Snackbar.make(view, "Name already taken. Choose another name.", Snackbar.LENGTH_LONG).show()
                        return@launch
                    }

                    // OK: persist and navigate
                    sharedVm.setCommunityName(name)
                    try { navigateWithDelay(R.id.action_groupNamePicFragment_to_groupDescriptionFragment) } catch (_: Exception) {}
                } catch (_: Exception) {
                    Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                } finally {
                    try { setLoaderVisible(false) } catch (_: Exception) {}
                }
            }
        }

    }
}