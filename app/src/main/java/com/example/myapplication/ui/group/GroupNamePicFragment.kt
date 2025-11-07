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
        val grpPicFrame = view.findViewById<View>(R.id.grp_pic_frame)
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
                    }
                    contentUri != null -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic?.visibility = View.VISIBLE
                        try { Glide.with(this).load(contentUri).circleCrop().into(grpPic) } catch (_: Exception) {}
                    }
                    !imgPath.isNullOrBlank() -> {
                        grpPicIcon?.visibility = View.GONE
                        grpPic?.visibility = View.VISIBLE
                        try { Glide.with(this).load(imgPath).circleCrop().into(grpPic) } catch (_: Exception) {}
                    }
                    else -> {
                        grpPicIcon?.visibility = View.VISIBLE
                        grpPic?.visibility = View.INVISIBLE
                        grpPic?.setImageResource(R.drawable.default_profile)
                    }
                }
            } catch (_: Exception) {}
        }

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

        // Next: save name to shared VM and navigate to description fragment
        btnNext?.setOnClickListener {
            val name = etName?.text?.toString()?.trim() ?: ""
            if (name.isBlank()) {
                android.widget.Toast.makeText(requireContext(), "Group name is required", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            sharedVm.setCommunityName(name)
            try { findNavController().navigate(R.id.action_groupNamePicFragment_to_groupDescriptionFragment) } catch (_: Exception) {}
        }
    }
}