package com.example.myapplication.ui.dashboard

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.scale
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.example.myapplication.data.network.NetworkModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import com.google.android.material.snackbar.Snackbar
import kotlin.math.min

class CommunityNamePicFragment : BaseFragment(R.layout.fragment_community_name_pic) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Wire basic navigation
        try {
            val backArrow = view.findViewById<ImageView>(R.id.back_arrow)
            backArrow.setOnClickListener { try { findNavController().navigateUp() } catch (_: Exception) {} }
        } catch (_: Exception) {}

        // UI elements for image picking
        val commPic = view.findViewById<ImageView>(R.id.comm_pic)
        val commPicIcon = view.findViewById<ImageView>(R.id.comm_pic_icon)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val commPicFrame = view.findViewById<View>(R.id.comm_pic_frame)

        // If image already selected in sharedVm, hide the center icon
        try {
            val initialUri = sharedVm.selectedContentUri.value
            if (initialUri != null) {
                try { Glide.with(this).load(initialUri).circleCrop().into(commPic); commPicIcon?.visibility = View.GONE } catch (_: Exception) {}
            }
        } catch (_: Exception) {}

        // Target pixel size for final image (reuse a reasonable size)
        val targetSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)

        // helper: center-crop a bitmap to square and scale to target size
        fun centerCropBitmap(src: Bitmap, targetSize: Int): Bitmap {
            val side = min(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val cropped = Bitmap.createBitmap(src, x, y, side, side)
            return cropped.scale(targetSize, targetSize)
        }

        // Convert a bitmap to a content Uri by inserting into MediaStore (returns null on failure)
        fun saveBitmapToMediaStore(bitmap: Bitmap, displayName: String = "community_${System.currentTimeMillis()}.jpg"): Uri? {
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SpaceHub")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                }

                val resolver = requireContext().contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    resolver.openOutputStream(uri)?.use { out ->
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                        out.flush()
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        values.clear()
                        values.put(MediaStore.Images.Media.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    }
                }
                uri
            } catch (_: Exception) { null }
        }

        // show a simple crop preview dialog (center square) and let user Use or Retake
        fun showCropDialog(original: Bitmap, onUse: (Bitmap) -> Unit, onRetake: () -> Unit) {
            val preview = centerCropBitmap(original, targetSize)
            val previewIv = ImageView(requireContext()).apply {
                setImageBitmap(preview)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            val dlg = com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = getString(R.string.create_your_community), customView = previewIv, positiveText = getString(android.R.string.ok), negativeText = getString(R.string.skip), onPositive = { onUse(preview) }, onNegative = { onRetake() })
            try { dlg.show() } catch (_: Exception) {}
        }

        // apply cropped bitmap to the preview and set selectedContentUri in sharedVm
        fun applyCroppedBitmapAndSetUri(bm: Bitmap) {
            try {
                val uri = saveBitmapToMediaStore(bm)
                if (uri != null) {
                    sharedVm.setSelectedContentUri(uri)
                    try {
                        Glide.with(this).clear(commPic)
                        Glide.with(this).load(uri).signature(ObjectKey(uri.toString())).circleCrop().into(commPic)
                        commPicIcon?.visibility = View.GONE
                    } catch (_: Exception) {}
                } else {
                    // fallback: apply bitmap directly to preview (no persisted uri)
                    commPic.setImageBitmap(bm)
                    commPicIcon?.visibility = View.GONE
                }
            } catch (_: Exception) {}
        }

        // ActivityResult launchers
        lateinit var cameraPreviewLauncher: ActivityResultLauncher<Void?>
        lateinit var galleryLauncher: ActivityResultLauncher<String>

        cameraPreviewLauncher = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
            if (bmp != null) {
                try {
                    showCropDialog(bmp, onUse = { cropped -> applyCroppedBitmapAndSetUri(cropped) }, onRetake = { cameraPreviewLauncher.launch(null) })
                } catch (_: Exception) {}
            }
        }

        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                try {
                    // Try to decode a bitmap to allow cropping preview; if decoding fails, just use the Uri directly
                    val stream = requireContext().contentResolver.openInputStream(uri)
                    var decoded: Bitmap? = null
                    stream?.use { decoded = BitmapFactory.decodeStream(it) }
                    if (decoded != null) {
                        showCropDialog(decoded, onUse = { cropped -> applyCroppedBitmapAndSetUri(cropped) }, onRetake = { galleryLauncher.launch("image/*") })
                        return@registerForActivityResult
                    }

                    // No decoding/cropping: use Uri directly
                    sharedVm.setSelectedContentUri(uri)
                    try { Glide.with(this).clear(commPic); commPic.setImageDrawable(null); Glide.with(this).load(uri).circleCrop().into(commPic); commPicIcon?.visibility = View.GONE } catch (_: Exception) {}
                } catch (_: Exception) {
                    try { Glide.with(this).clear(commPic); commPic.setImageDrawable(null); Glide.with(this).load(uri).circleCrop().into(commPic); commPicIcon?.visibility = View.GONE } catch (_: Exception) {}
                }
            }
        }

        // wire clicks to open chooser
        val openPicker = {
            val items = arrayOf("Take Photo", "Choose from Gallery")
            com.example.myapplication.ui.common.AppDialogHelper.showItemsDialog(requireContext(), title = getString(R.string.create_your_community), items = items, onSelected = { which ->
                when (which) {
                    0 -> cameraPreviewLauncher.launch(null)
                    1 -> galleryLauncher.launch("image/*")
                }
            }, negativeText = getString(android.R.string.cancel))
        }

        commPicFrame?.setOnClickListener { openPicker() }
        addIcon?.setOnClickListener { openPicker() }

        // Next button: just store data and navigate (no API call)
        try {
            val createBtn = view.findViewById<View>(R.id.btn_create_comm)
            val etCommName = view.findViewById<android.widget.EditText>(R.id.etCommName)
            createBtn.setOnClickListener {
                val commName = etCommName?.text?.toString()?.trim() ?: ""
                if (commName.isBlank()) {
                    android.widget.Toast.makeText(requireContext(), "Community name is required", android.widget.Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                // Validate name using API before proceeding
                viewLifecycleOwner.lifecycleScope.launch {
                    try { setLoaderVisible(true) } catch (_: Exception) {}
                    try {
                        val api = NetworkModule.createApiService(requireContext())
                        val checkRes = try { withContext(Dispatchers.IO) { api.checkCommunityNameExists(commName) } } catch (_: Throwable) { null }
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
                            // Unexpected response structure or non-success status in body
                            Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                            return@launch
                        }
                        val exists = body.data.exists
                        if (exists) {
                            Snackbar.make(view, "Name already taken. Choose another name.", Snackbar.LENGTH_LONG).show()
                            return@launch
                        }

                        // OK: persist and navigate
                        sharedVm.setCommunityName(commName)
                        try { navigateWithDelay(R.id.action_communityNamePicFragment_to_communityDescriptionFragment) } catch (_: Exception) {}
                    } catch (_: Exception) {
                        Snackbar.make(view, "Failed to validate name. Please try again.", Snackbar.LENGTH_LONG).show()
                    } finally {
                        try { setLoaderVisible(false) } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}
    }
}