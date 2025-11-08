package com.example.myapplication.ui.group

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
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.example.myapplication.R
import com.example.myapplication.ui.group.viewmodel.EditGroupViewModel
import com.example.myapplication.ui.group.viewmodel.GroupDetailViewModel
import com.example.myapplication.ui.common.ImagePickerHelper
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.ui.common.ProfileImageHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class EditGroupFragment : Fragment(R.layout.fragment_edit_group) {
    private val vm: EditGroupViewModel by viewModels()
    private val sharedVm: GroupDetailViewModel by activityViewModels()
    private val picSharedVm: ProfileSharedViewModel by activityViewModels()
    private var imagePicker: ImagePickerHelper? = null

    // Hold last attempted update params to support Retry from Snackbar
    private var lastGroupId: String? = null
    private var lastEmail: String? = null
    private var lastName: String? = null
    private var lastImagePart: MultipartBody.Part? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val groupId = arguments?.getString("communityId") ?: arguments?.getString("groupId")
        if (groupId.isNullOrBlank()) {
            Toast.makeText(requireContext(), "Missing group id", Toast.LENGTH_SHORT).show()
            findNavController().popBackStack()
            return
        }

        val etName = view.findViewById<EditText>(R.id.etGrpName)
        val etDesc = view.findViewById<EditText>(R.id.etGrpDescription)
        val btnSave = view.findViewById<Button>(R.id.btnSave)
        val progress = view.findViewById<ProgressBar>(R.id.progress)
        val grpPic = view.findViewById<ImageView>(R.id.grp_pic)
        val grpPicIcon = view.findViewById<ImageView>(R.id.grp_pic_icon)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)

        // Prefill from shared GroupDetailViewModel if available
        sharedVm.group.observe(viewLifecycleOwner) { data ->
            data?.let {
                if (etName.text.isNullOrBlank()) etName.setText(it.name)
                if (etDesc.text.isNullOrBlank()) etDesc.setText(it.description)
            }
        }

        // Render preview from shared pic vm
        fun renderPreview() {
            try {
                val bmp = picSharedVm.selectedBitmap.value
                val contentUri = picSharedVm.selectedContentUri.value
                val imgPath = picSharedVm.selectedImagePath.value
                when {
                    bmp != null -> { grpPicIcon?.visibility = View.GONE; grpPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), grpPic, bmp) }
                    contentUri != null -> { grpPicIcon?.visibility = View.GONE; grpPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), grpPic, contentUri) }
                    !imgPath.isNullOrBlank() -> { grpPicIcon?.visibility = View.GONE; grpPic?.visibility = View.VISIBLE; ProfileImageHelper.loadProfileImageIntoView(requireContext(), grpPic, imgPath) }
                    else -> { grpPicIcon?.visibility = View.VISIBLE; grpPic?.visibility = View.INVISIBLE }
                }
            } catch (_: Exception) {}
        }

        renderPreview()
        try { picSharedVm.selectedBitmap.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}
        try { picSharedVm.selectedContentUri.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}
        try { picSharedVm.selectedImagePath.observe(viewLifecycleOwner) { renderPreview() } } catch (_: Exception) {}

        // Initialize image picker
        val targetSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
        imagePicker = ImagePickerHelper(this, targetSize,
            onBitmapCropped = { bmp: Bitmap -> try { picSharedVm.setSelectedBitmap(bmp); renderPreview() } catch (_: Exception) {} },
            onFileReady = { filePath: String?, contentUri -> try { if (contentUri != null) picSharedVm.setSelectedContentUri(contentUri); else picSharedVm.setImagePath(filePath); renderPreview() } catch (_: Exception) {} }
        )

        val openPicker = { imagePicker?.pickImageChooser() }
        grpPic?.setOnClickListener { openPicker() }
        addIcon?.setOnClickListener { openPicker() }
        grpPicIcon?.setOnClickListener { openPicker() }

        fun performUpdate(groupIdParam: String, emailParam: String, nameParam: String, imagePartParam: MultipartBody.Part?) {
            // store last params for retry
            lastGroupId = groupIdParam
            lastEmail = emailParam
            lastName = nameParam
            lastImagePart = imagePartParam
            // Call ViewModel update (signature: groupId, requesterEmail, name, imagePart?)
            vm.update(groupIdParam, emailParam, nameParam, imagePartParam)
        }

        btnSave.setOnClickListener {
            // getEmail is suspend; call inside coroutine
            lifecycleScope.launch {
                val email = com.example.myapplication.data.user.UserDataManager.getInstance(requireContext()).getEmail()
                if (email.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "User email missing", Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // Build image part if selected
                var imagePart: MultipartBody.Part? = null
                try {
                    val bmp = picSharedVm.selectedBitmap.value
                    val contentUri = picSharedVm.selectedContentUri.value
                    if (bmp != null) {
                        val baos = ByteArrayOutputStream()
                        bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                        val bytes = baos.toByteArray()
                        val reqBody = bytes.toRequestBody("image/jpeg".toMediaTypeOrNull())
                        imagePart = MultipartBody.Part.createFormData("imageFile", "group_pic.jpg", reqBody)
                    } else if (contentUri != null) {
                        val resolver = requireContext().contentResolver
                        val mime = resolver.getType(contentUri) ?: "image/jpeg"
                        val input: InputStream? = resolver.openInputStream(contentUri)
                        input?.use { stream ->
                            val bytes = stream.readBytes()
                            val filename = contentUri.lastPathSegment ?: "group_pic.jpg"
                            val reqBody = bytes.toRequestBody(mime.toMediaTypeOrNull())
                            imagePart = MultipartBody.Part.createFormData("imageFile", filename, reqBody)
                        }
                    }
                } catch (_: Exception) { imagePart = null }

                performUpdate(groupId, email, etName.text?.toString().orEmpty(), imagePart)
            }
        }

        vm.loading.observe(viewLifecycleOwner) { show ->
            progress.visibility = if (show) View.VISIBLE else View.GONE
            try { btnSave.isEnabled = !show } catch (_: Exception) {}
        }

        vm.toast.observe(viewLifecycleOwner) { msg ->
            if (!msg.isNullOrBlank()) {
                // Success path
                if (msg.contains("updated", ignoreCase = true)) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    // Ask shared VM to refresh and pop back
                    sharedVm.refreshDetails()
                    // clear shared image selection
                    try { picSharedVm.clear() } catch (_: Exception) {}
                    findNavController().popBackStack()
                    return@observe
                }

                // Failure: show Snackbar with Retry action using stored params
                try {
                    val parent = view.findViewById<View>(android.R.id.content) ?: view
                    Snackbar.make(parent, msg, Snackbar.LENGTH_LONG)
                        .setAction("Retry") {
                            // Re-run last stored update request
                            val gId = lastGroupId
                            val em = lastEmail
                            val nm = lastName
                            if (!gId.isNullOrBlank() && !em.isNullOrBlank() && !nm.isNullOrBlank()) {
                                performUpdate(gId, em, nm, lastImagePart)
                            }
                        }
                        .show()
                 } catch (_: Exception) {
                     Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                 }
             }
         }
     }
 }
