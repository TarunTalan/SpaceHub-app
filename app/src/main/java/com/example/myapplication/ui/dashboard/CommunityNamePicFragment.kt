package com.example.myapplication.ui.dashboard

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.min
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider

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
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val commPicFrame = view.findViewById<View>(R.id.comm_pic_frame)

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

        // Write a bitmap to cache and return the absolute path. Overwrites previous cache file.
        fun writeBitmapToCache(bitmap: Bitmap): String? {
            val cacheDir = requireContext().cacheDir
            val filename = "community_pic.png"
            val outFile = File(cacheDir, filename)
            try {
                try { sharedVm.selectedImagePath.value?.let { oldPath -> if (oldPath != outFile.absolutePath) File(oldPath).takeIf { it.exists() }?.delete() } } catch (_: Exception) {}
                try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}
                FileOutputStream(outFile).use { fos -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos); fos.flush() }
                try { outFile.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
                return outFile.absolutePath
            } catch (_: IOException) {
            }
            return null
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
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.create_your_community)
                .setView(previewIv)
                .setPositiveButton(android.R.string.ok) { _, _ -> onUse(preview) }
                .setNegativeButton(R.string.skip) { _, _ -> onRetake() }
                .setCancelable(true)
                .show()
        }

        // apply cropped bitmap to the preview
        fun applyCroppedBitmap(bm: Bitmap) {
            try {
                val path = writeBitmapToCache(bm)
                if (path != null) {
                    sharedVm.setImagePath(path)
                    try {
                        val file = File(path)
                        Glide.with(this).clear(commPic)
                        commPic.setImageDrawable(null)
                        Glide.with(this)
                            .load(file)
                            .signature(ObjectKey(file.absolutePath + "-" + file.lastModified()))
                            .circleCrop()
                            .into(commPic)
                    } catch (_: Exception) {
                        commPic.setImageBitmap(bm)
                    }
                } else {
                    commPic.setImageBitmap(bm)
                }
            } catch (_: Exception) {}
        }

        // Helper: copy a content Uri to the app cache and return the path (overwrites fixed filename)
        fun writeUriToCache(uri: Uri): String? {
            val cacheDir = requireContext().cacheDir
            val filename = "community_pic.png"
            val outFile = File(cacheDir, filename)
            try {
                try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}
                requireContext().contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output); output.flush() } }
                if (outFile.exists()) return outFile.absolutePath
            } catch (_: Exception) {}
            return null
        }

        // ActivityResult launchers
        lateinit var cameraLauncher: ActivityResultLauncher<Uri>
        lateinit var galleryLauncher: ActivityResultLauncher<String>

        fun createImageContentUri(): Uri? {
            return try {
                val cacheDir = requireContext().cacheDir
                val filename = "community_pic.png"
                val file = File(cacheDir, filename)
                file.parentFile?.mkdirs()
                try { if (file.exists()) file.delete() } catch (_: Exception) {}
                val authority = requireContext().packageName + ".provider"
                FileProvider.getUriForFile(requireContext(), authority, file).also { uri ->
                    try { requireContext().grantUriPermission(requireContext().packageName, uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
                }
            } catch (_: Exception) { null }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
            if (success) {
                val cacheDir = requireContext().cacheDir
                val file = File(cacheDir, "community_pic.png")
                if (file.exists()) {
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            val maxDim = 512
                            val scaled = bitmap.scale(maxDim, maxDim)
                            FileOutputStream(file, false).use { fos -> scaled.compress(Bitmap.CompressFormat.JPEG, 60, fos); fos.flush() }
                        }
                    } catch (_: Exception) {}
                    val contentUri = try { val authority = requireContext().packageName + ".provider"; FileProvider.getUriForFile(requireContext(), authority, file) } catch (_: Exception) { null }
                    if (contentUri != null) {
                        sharedVm.setSelectedContentUri(contentUri)
                        sharedVm.setImagePath(file.absolutePath)
                        try {
                            Glide.with(this).clear(commPic)
                            Glide.with(this).load(file).signature(ObjectKey(file.absolutePath + "-" + file.lastModified())).circleCrop().into(commPic)
                        } catch (_: Exception) {}
                    }
                }
            }
        }

        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                sharedVm.setSelectedContentUri(uri)
                try {
                    val stream = requireContext().contentResolver.openInputStream(uri)
                    var decoded: Bitmap? = null
                    stream?.use { decoded = BitmapFactory.decodeStream(it) }
                    if (decoded != null) {
                        showCropDialog(decoded, onUse = { cropped -> applyCroppedBitmap(cropped) }, onRetake = { galleryLauncher.launch("image/*") })
                        return@registerForActivityResult
                    }
                    val cachedPath = writeUriToCache(uri)
                    if (cachedPath != null) {
                        try {
                            sharedVm.setImagePath(cachedPath)
                            Glide.with(this).clear(commPic)
                            commPic.setImageDrawable(null)
                            Glide.with(this)
                                .load(File(cachedPath))
                                .signature(ObjectKey(File(cachedPath).absolutePath + "-" + File(cachedPath).lastModified()))
                                .circleCrop()
                                .listener(object : RequestListener<Drawable?> {
                                    override fun onLoadFailed(
                                        e: GlideException?,
                                        model: Any?,
                                        target: Target<Drawable?>?,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        try {
                                            Glide.with(this@CommunityNamePicFragment).clear(commPic)
                                            Glide.with(this@CommunityNamePicFragment).load(File(cachedPath)).skipMemoryCache(true)
                                                .signature(ObjectKey(File(cachedPath).absolutePath + "-" + File(cachedPath).lastModified())).circleCrop().into(commPic)
                                        } catch (_: Exception) {}
                                        return false
                                    }

                                    override fun onResourceReady(
                                        resource: Drawable?,
                                        model: Any?,
                                        target: Target<Drawable?>?,
                                        dataSource: DataSource?,
                                        isFirstResource: Boolean
                                    ): Boolean { return false }
                                })
                                .into(commPic)
                        } catch (_: Exception) {
                            try { Glide.with(this).clear(commPic); commPic.setImageDrawable(null); Glide.with(this).load(uri).circleCrop().into(commPic) } catch (_: Exception) {}
                        }
                    } else {
                        try { Glide.with(this).clear(commPic); commPic.setImageDrawable(null); Glide.with(this).load(uri).circleCrop().into(commPic) } catch (_: Exception) {}
                    }
                } catch (_: Exception) {
                    try { Glide.with(this).clear(commPic); commPic.setImageDrawable(null); Glide.with(this).load(uri).circleCrop().into(commPic) } catch (_: Exception) {}
                }
            }
        }

        // wire clicks to open chooser
        val openPicker = {
            val items = arrayOf("Take Photo", "Choose from Gallery")
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.create_your_community)
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            val uri = createImageContentUri()
                            if (uri != null) cameraLauncher.launch(uri) else {
                                try {
                                    val legacy = registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
                                        if (bmp != null) showCropDialog(bmp, onUse = { cropped -> applyCroppedBitmap(cropped) }, onRetake = {})
                                    }
                                    legacy.launch(null)
                                } catch (_: Exception) {}
                            }
                        }
                        1 -> galleryLauncher.launch("image/*")
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        commPicFrame?.setOnClickListener { openPicker() }
        addIcon?.setOnClickListener { openPicker() }

        // Next button: upload and navigate
        try {
            val createBtn = view.findViewById<View>(R.id.btn_create_comm)
            createBtn.setOnClickListener {
                lifecycleScope.launch {
                    // show loader if BaseFragment supports it
                    try { setLoaderVisible(true) } catch (_: Exception) {}
                    try {
                        uploadCommunityImage()
                    } finally {
                        try { setLoaderVisible(false) } catch (_: Exception) {}
                    }
                    try { findNavController().navigate(R.id.action_communityNamePicFragment_to_communityDescriptionFragment) } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    // Upload helper: reuse same multipart upload logic as ChooseProfilePicFragment (stores uploaded url in sharedVm.uploadedProfileUrl)
    private suspend fun uploadCommunityImage(): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val api = NetworkModule.createApiService(requireContext())
                // For community we don't have an email; send empty text parts to match server expectations
                val emptyRb = "".toRequestBody("text/plain".toMediaTypeOrNull())
                val imgPath = try { sharedVm.selectedImagePath.value } catch (_: Exception) { null }
                val filePart = try {
                    if (!imgPath.isNullOrBlank()) {
                        val f = File(imgPath)
                        if (f.exists()) {
                            val contentUri = try { sharedVm.selectedContentUri.value } catch (_: Exception) { null }
                            val mimeFromResolver = try { contentUri?.let { requireContext().contentResolver.getType(it) } } catch (_: Exception) { null }
                            val ext = f.extension.takeIf { it.isNotBlank() } ?: MimeTypeMap.getFileExtensionFromUrl(f.absolutePath)
                            val mimeFromExt = ext?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
                            val mime = mimeFromResolver ?: mimeFromExt ?: "application/octet-stream"
                            val rejectNonImage = true
                            if (rejectNonImage && !mime.startsWith("image/")) {
                                null
                            } else {
                                val req = f.asRequestBody(mime.toMediaTypeOrNull())
                                MultipartBody.Part.createFormData("image", f.name, req)
                            }
                        } else null
                    } else null
                } catch (_: Exception) { null }

                // NOTE: reusing uploadProfile endpoint because it accepts multipart image upload. If your backend has a dedicated endpoint for community images, replace this call accordingly.
                val resp = api.uploadProfile(emptyRb, emptyRb, filePart)
                var success = false
                val rawRespBodyString: String? = try { try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null } } catch (_: Exception) { null }
                if (resp.isSuccessful) {
                    try {
                        val body = resp.body()
                        var downloadUrl = body?.data
                        if ((downloadUrl == null || downloadUrl.isBlank()) && !rawRespBodyString.isNullOrBlank()) {
                            try {
                                // crude extraction of any URL from response
                                val regex = "https?://[\\w\\-./?=&%:;#@+~]+".toRegex()
                                val m = regex.find(rawRespBodyString)
                                if (m != null) downloadUrl = m.value
                            } catch (_: Exception) {}
                        }
                        if (!downloadUrl.isNullOrBlank()) {
                            try {
                                withContext(Dispatchers.Main) { sharedVm.setUploadedProfileUrl(downloadUrl) }
                                try { val prefs = requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE); prefs.edit().putString("uploaded_profile_url", downloadUrl).apply() } catch (_: Exception) {}
                            } catch (_: Exception) { try { sharedVm.setUploadedProfileUrl(downloadUrl) } catch (_: Exception) {} }
                        }
                        success = true
                    } catch (_: Exception) { success = false }
                } else {
                    success = false
                }
                return@withContext success
            } catch (_: Exception) { return@withContext false }
        }
    }
}