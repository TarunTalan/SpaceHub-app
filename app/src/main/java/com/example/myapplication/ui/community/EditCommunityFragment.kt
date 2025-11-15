package com.example.myapplication.ui.community

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.Toast
import android.widget.ImageView
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.InputStream
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.community.viewmodel.EditCommunityViewModel
import com.example.myapplication.ui.common.ImagePickerHelper
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

class EditCommunityFragment : Fragment(R.layout.fragment_edit_community) {

    private val vm: EditCommunityViewModel by viewModels()
    private val picSharedVm: ProfileSharedViewModel by activityViewModels()
    private var imagePicker: ImagePickerHelper? = null

    // hold last attempt for retry
    private var lastCommunityId: String? = null
    private var lastName: String? = null
    private var lastDesc: String? = null
    private var lastImagePart: MultipartBody.Part? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val communityId = arguments?.getString("communityId")
        if (communityId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing communityId", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val etName = view.findViewById<EditText>(R.id.etCommunityName)
        val etDesc = view.findViewById<EditText>(R.id.etCommunityDescription)
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val commPic = view.findViewById<ImageView>(R.id.comm_pic)
        val commPicIcon = view.findViewById<ImageView>(R.id.comm_pic_icon)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val backArrow = view.findViewById<ImageView>(R.id.back_arrow)
        backArrow?.setOnClickListener {
            try { findNavController().popBackStack() } catch (_: Exception) { }
        }

        // Render preview
        fun renderPreview() {
            try {
                val bmp = picSharedVm.selectedBitmap.value
                val contentUri = picSharedVm.selectedContentUri.value
                val imgPath = picSharedVm.selectedImagePath.value
                when {
                    bmp != null -> { commPicIcon?.visibility = View.GONE; commPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), commPic, bmp) }
                    contentUri != null -> { commPicIcon?.visibility = View.GONE; commPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), commPic, contentUri) }
                    !imgPath.isNullOrBlank() -> { commPicIcon?.visibility = View.GONE; commPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), commPic, imgPath) }
                    else -> { commPicIcon?.visibility = View.VISIBLE; commPic?.visibility = View.INVISIBLE }
                }
            } catch (_: Exception) {}
        }

        renderPreview()
        try { picSharedVm.selectedBitmap.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}
        try { picSharedVm.selectedContentUri.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}
        try { picSharedVm.selectedImagePath.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}

        // Setup picker
        val targetSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
        imagePicker = ImagePickerHelper(this, targetSize,
            onBitmapCropped = { bmp: Bitmap -> try { picSharedVm.setSelectedBitmap(bmp); renderPreview() } catch (_: Exception) {} },
            onFileReady = { filePath: String?, contentUri -> try { if (contentUri != null) picSharedVm.setSelectedContentUri(contentUri); else picSharedVm.setImagePath(filePath); renderPreview() } catch (_: Exception) {} }
        )

        val openPicker = { imagePicker?.pickImageChooser() }
        commPic?.setOnClickListener { openPicker() }
        addIcon?.setOnClickListener { openPicker() }
        commPicIcon?.setOnClickListener { openPicker() }

        fun performUpdate(cid: String, nameVal: String, descVal: String, imagePart: MultipartBody.Part?) {
            lastCommunityId = cid
            lastName = nameVal
            lastDesc = descVal
            lastImagePart = imagePart
            vm.update(cid, nameVal, descVal, imagePart)
        }

        btnSave.setOnClickListener {
            lifecycleScope.launch {
                // build image part
                var imagePart: MultipartBody.Part? = null
                try {
                    val bmp = picSharedVm.selectedBitmap.value
                    val contentUri = picSharedVm.selectedContentUri.value
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val bytes = baos.toByteArray()
                        val reqBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        imagePart = MultipartBody.Part.createFormData("file", "banner.jpg", reqBody)
                    } else if (contentUri != null) {
                        val resolver = requireContext().contentResolver
                        val mime = resolver.getType(contentUri) ?: "image/jpeg"
                        val input: InputStream? = resolver.openInputStream(contentUri)
                        input?.use { stream ->
                            val bytes = stream.readBytes()
                            val filename = contentUri.lastPathSegment ?: "banner.jpg"
                            val reqBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
                            imagePart = MultipartBody.Part.createFormData("file", filename, reqBody)
                        }
                    }
                } catch (_: Exception) { imagePart = null }

                // If still null, attach default community drawable
                if (imagePart == null) {
                    try {
                        val drawable = androidx.core.content.ContextCompat.getDrawable(requireContext(), R.drawable.default_comm_icon)
                        val bmp2 = when (drawable) {
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
                        val baos = ByteArrayOutputStream()
                        bmp2.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val bytes = baos.toByteArray()
                        val reqBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        imagePart = MultipartBody.Part.createFormData("file", "default_comm_banner.jpg", reqBody)
                    } catch (_: Exception) { imagePart = null }
                }

                performUpdate(communityId, etName.text?.toString().orEmpty(), etDesc.text?.toString().orEmpty(), imagePart)
            }
        }

        vm.loading.observe(viewLifecycleOwner) { show ->
            progress.visibility = if (show) View.VISIBLE else View.GONE
            try { btnSave.isEnabled = !show } catch (_: Exception) {}
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                if (msg.contains("updated", ignoreCase = true)) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    // clear selection
                    try { picSharedVm.clear() } catch (_: Exception) {}
                    findNavController().popBackStack()
                    return@observe
                }
                try {
                    val parent = view.findViewById<View>(android.R.id.content) ?: view
                    Snackbar.make(parent, msg, Snackbar.LENGTH_LONG)
                        .setAction("Retry") {
                            val cid = lastCommunityId
                            val nm = lastName
                            val ds = lastDesc
                            if (!cid.isNullOrBlank() && nm != null && ds != null) performUpdate(cid, nm, ds, lastImagePart)
                        }
                        .show()
                } catch (_: Exception) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}
