package com.example.myapplication.ui.dashboard

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.core.os.bundleOf
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.bumptech.glide.Glide
import com.bumptech.glide.signature.ObjectKey
import com.example.myapplication.R
import com.example.myapplication.data.network.NetworkModule
import com.example.myapplication.ui.common.BaseFragment
import com.example.myapplication.ui.common.ProfileSharedViewModel
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min

class ChooseProfilePicFragment : BaseFragment(R.layout.fragment_choose_profile_pic) {

    private val sharedVm: ProfileSharedViewModel by activityViewModels()
    private var signupEmailArg: String? = null

    private lateinit var cameraLauncher: ActivityResultLauncher<Uri>
    private lateinit var galleryLauncher: ActivityResultLauncher<String>
    private var lastSelectedAvatar: ImageView? = null

    companion object {
        private const val CACHE_FILENAME = "profile_pic.png"
        private const val MAX_IMAGE_DIM = 512
        private const val IMAGE_QUALITY = 60
        private const val PREFS_NAME = "app_prefs"

        private val AVATAR_MAP = mapOf(
            R.id.avatar_1 to R.drawable.avatar_1,
            R.id.avatar_2 to R.drawable.avatar_2,
            R.id.avatar_3 to R.drawable.avatar_3,
            R.id.avatar_4 to R.drawable.avatar_4,
            R.id.avatar_5 to R.drawable.avatar_5,
            R.id.avatar_6 to R.drawable.avatar_6,
            R.id.avatar_7 to R.drawable.avatar_7,
            R.id.avatar_8 to R.drawable.avatar_8
        )
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        signupEmailArg = arguments?.getString("email")
        sharedVm.setUploadedProfileUrl(null)

        val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val outlineBase = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_white)
        val logoSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)

        setupCameraLauncher(imgLogo)
        setupGalleryLauncher(imgLogo, logoSize)
        setupAvatarSelection(view, imgLogo, outlineBase, logoSize)
        setupAddIconClick(addIcon)
        setupNavigationButtons(view)
    }

    // ==================== Bitmap Utils ====================

    private fun centerCropBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return cropped.scale(targetSize, targetSize)
    }

    private fun writeBitmapToCache(bitmap: Bitmap): String? {
        return try {
            val outFile = File(requireContext().cacheDir, CACHE_FILENAME)

            // Delete old file
            sharedVm.selectedImagePath.value?.let { oldPath ->
                if (oldPath != outFile.absolutePath) {
                    File(oldPath).takeIf { it.exists() }?.delete()
                }
            }
            outFile.delete()

            FileOutputStream(outFile).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            outFile.setLastModified(System.currentTimeMillis())
            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    private fun writeUriToCache(uri: Uri): String? {
        return try {
            val outFile = File(requireContext().cacheDir, CACHE_FILENAME)
            outFile.delete()

            requireContext().contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            if (outFile.exists()) outFile.absolutePath else null
        } catch (e: Exception) {
            null
        }
    }

    private fun compressImage(file: File) {
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val scaled = bitmap.scale(MAX_IMAGE_DIM, MAX_IMAGE_DIM)
            FileOutputStream(file).use { fos ->
                scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, fos)
            }
        } catch (e: Exception) {
            // Ignore compression errors
        }
    }

    private fun drawableToBitmap(drawable: Drawable?, logoSize: Int): Bitmap? {
        return when {
            drawable == null -> null
            drawable is BitmapDrawable -> drawable.bitmap
            else -> try {
                val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else logoSize
                val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else logoSize
                createBitmap(w, h).also { bmp ->
                    val canvas = Canvas(bmp)
                    drawable.setBounds(0, 0, canvas.width, canvas.height)
                    drawable.draw(canvas)
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    // ==================== Glide Loading ====================

    private fun loadImageWithGlide(imgLogo: ImageView, file: File) {
        Glide.with(this).clear(imgLogo)
        Glide.with(this)
            .load(file)
            .signature(ObjectKey("${file.absolutePath}-${file.lastModified()}"))
            .circleCrop()
            .into(imgLogo)
    }

    private fun loadImageFromResource(imgLogo: ImageView, resId: Int) {
        Glide.with(this).clear(imgLogo)
        Glide.with(this)
            .load(resId)
            .circleCrop()
            .into(imgLogo)
    }

    // ==================== Dialogs ====================

    private fun showCropDialog(original: Bitmap, logoSize: Int, onUse: (Bitmap) -> Unit, onRetake: () -> Unit) {
        val preview = centerCropBitmap(original, logoSize)
        val previewIv = ImageView(requireContext()).apply {
            setImageBitmap(preview)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val dlg = com.example.myapplication.ui.common.AppDialogHelper.createViewDialog(requireContext(), title = getString(R.string.choose_profile_pic), customView = previewIv, positiveText = getString(android.R.string.ok), negativeText = getString(R.string.skip), onPositive = { onUse(preview) }, onNegative = { onRetake() })
        try { dlg.show() } catch (_: Exception) {}
    }

    private fun showImageSourceDialog() {
        val items = arrayOf("Take Photo", "Choose from Gallery")
        com.example.myapplication.ui.common.AppDialogHelper.showItemsDialog(requireContext(), title = "Select image", items = items, onSelected = { which ->
            when (which) {
                0 -> createImageContentUri()?.let { cameraLauncher.launch(it) }
                1 -> galleryLauncher.launch("image/*")
            }
        }, negativeText = getString(android.R.string.cancel))
    }

    // ==================== Apply Image ====================

    private fun applyCroppedBitmap(imgLogo: ImageView, bitmap: Bitmap) {
        val path = writeBitmapToCache(bitmap) ?: return
        sharedVm.setImagePath(path)
        loadImageWithGlide(imgLogo, File(path))
    }

    private fun applyAvatarDrawable(imgLogo: ImageView, logoSize: Int, drawable: Drawable? = null, resId: Int? = null) {
        val sourceDrawable = resId?.let { ContextCompat.getDrawable(requireContext(), it) } ?: drawable
        val bmp = drawableToBitmap(sourceDrawable, logoSize)
        val cropped = bmp?.let { centerCropBitmap(it, logoSize) }

        if (cropped != null) {
            val path = writeBitmapToCache(cropped)
            if (path != null) {
                sharedVm.setImagePath(path)
                loadImageWithGlide(imgLogo, File(path))
                return
            }
        }

        // Fallback
        if (resId != null) {
            loadImageFromResource(imgLogo, resId)
            sharedVm.setDrawableRes(resId)
        } else if (drawable != null) {
            imgLogo.setImageDrawable(drawable)
        }
    }

    // ==================== Setup Functions ====================

    private fun createImageContentUri(): Uri? {
        return try {
            val file = File(requireContext().cacheDir, CACHE_FILENAME)
            file.parentFile?.mkdirs()
            file.delete()

            val authority = "${requireContext().packageName}.provider"
            FileProvider.getUriForFile(requireContext(), authority, file)
        } catch (e: Exception) {
            null
        }
    }

    private fun setupCameraLauncher(imgLogo: ImageView) {
        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success) {
                val file = File(requireContext().cacheDir, CACHE_FILENAME)
                if (file.exists()) {
                    compressImage(file)
                    sharedVm.setImagePath(file.absolutePath)
                    loadImageWithGlide(imgLogo, file)
                }
            }
        }
    }

    private fun setupGalleryLauncher(imgLogo: ImageView, logoSize: Int) {
        galleryLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri ?: return@registerForActivityResult

            sharedVm.setSelectedContentUri(uri)

            // Try to decode bitmap for crop dialog
            val decoded = try {
                requireContext().contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }
            } catch (e: Exception) {
                null
            }

            if (decoded != null) {
                showCropDialog(decoded, logoSize,
                    onUse = { cropped -> applyCroppedBitmap(imgLogo, cropped) },
                    onRetake = { galleryLauncher.launch("image/*") }
                )
                return@registerForActivityResult
            }

            // Fallback: copy URI to cache
            val cachedPath = writeUriToCache(uri)
            if (cachedPath != null) {
                sharedVm.setImagePath(cachedPath)
                loadImageWithGlide(imgLogo, File(cachedPath))
            } else {
                // Last resort: load URI directly
                Glide.with(this).load(uri).circleCrop().into(imgLogo)
            }
        }
    }

    private fun setupAvatarSelection(view: View, imgLogo: ImageView, outlineBase: Drawable?, logoSize: Int) {
        AVATAR_MAP.keys.forEach { avatarId ->
            view.findViewById<ImageView>(avatarId)?.setOnClickListener { v ->
                val imageView = v as? ImageView

                // Clear previous selection
                lastSelectedAvatar?.foreground = null

                // Apply outline to selected avatar
                val outline = outlineBase?.constantState?.newDrawable()?.mutate()
                imageView?.foreground = outline
                lastSelectedAvatar = imageView

                // Apply avatar drawable
                val resId = AVATAR_MAP[v.id] ?: 0
                if (resId != 0) {
                    applyAvatarDrawable(imgLogo, logoSize, resId = resId)
                }
            }
        }
    }

    private fun setupAddIconClick(addIcon: ImageView?) {
        addIcon?.setOnClickListener {
            showImageSourceDialog()
        }
    }

    private fun setupNavigationButtons(view: View) {
        val skipTv = view.findViewById<TextView>(R.id.tv_skip)
        val nextBtn = view.findViewById<AppCompatButton>(R.id.btn_next)

        // Update Next button state based on image selection
        val updateNextButton = {
            val hasImage = !sharedVm.selectedImagePath.value.isNullOrBlank() ||
                          sharedVm.selectedDrawableRes.value != null
            setNextButtonEnabled(nextBtn, hasImage)
        }

        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { updateNextButton() }
        sharedVm.selectedDrawableRes.observe(viewLifecycleOwner) { updateNextButton() }
        updateNextButton()

        skipTv?.setOnClickListener {
            val logoSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)
            val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
            applyAvatarDrawable(imgLogo, logoSize, resId = R.drawable.default_profile)
            navigateToUsername()
        }

        nextBtn?.setOnClickListener {
            val imagePath = sharedVm.selectedImagePath.value
            val fileValid = imagePath?.let { File(it).run { exists() && length() > 0 } } ?: false

            if (!fileValid) {
                setNextButtonEnabled(nextBtn, false)
                return@setOnClickListener
            }

            lifecycleScope.launch {
                setLoaderVisible(true)
                try {
                    val email = signupEmailArg ?: arguments?.getString("email").orEmpty()
                    uploadProfile(email)

                    // Save uploaded URL
                    sharedVm.uploadedProfileUrl.value?.let { url ->
                        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                            putString("profile_image_url", url)
                        }
                    }
                } finally {
                    setLoaderVisible(false)
                }
                navigateToUsername()
            }
        }
    }

    private fun setNextButtonEnabled(nextBtn: AppCompatButton?, enabled: Boolean) {
        nextBtn?.apply {
            isEnabled = enabled
            val bgRes = if (enabled) R.drawable.rounded_button_bg else R.drawable.rounded_button_bg_dull_blue
            background = AppCompatResources.getDrawable(requireContext(), bgRes)
            alpha = 1.0f
        }
    }

    private fun navigateToUsername() {
        val email = signupEmailArg ?: arguments?.getString("email").orEmpty()
        val bundle = bundleOf("email" to email)

        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
            putString("last_screen", "choose_profile")
        }

        val navOptions = androidx.navigation.NavOptions.Builder()
            .setPopUpTo(R.id.chooseProfilePicFragment, true)
            .build()

        findNavController().navigate(
            R.id.action_chooseProfilePicFragment_to_usernameFragment,
            bundle,
            navOptions
        )
    }

    // ==================== Upload ====================

    private suspend fun uploadProfile(email: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val api = NetworkModule.createApiService(requireContext())
            val imgPath = sharedVm.selectedImagePath.value

            val emailRb = email.toRequestBody("text/plain".toMediaTypeOrNull())
            val imageUriRb = (imgPath ?: "").toRequestBody("text/plain".toMediaTypeOrNull())

            val filePart = createFilePart(imgPath)
            val resp = api.uploadProfile(emailRb, imageUriRb, filePart)

            if (resp.isSuccessful) {
                val downloadUrl = extractDownloadUrl(resp.body()?.data, resp.raw().peekBody(1024 * 1024).string())
                if (!downloadUrl.isNullOrBlank()) {
                    withContext(Dispatchers.Main) {
                        sharedVm.setUploadedProfileUrl(downloadUrl)
                        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit {
                            putString("uploaded_profile_url", downloadUrl)
                        }
                    }
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun createFilePart(imgPath: String?): MultipartBody.Part? {
        if (imgPath.isNullOrBlank()) return null

        val file = File(imgPath)
        if (!file.exists()) return null

        val contentUri = sharedVm.selectedContentUri.value
        val mimeFromResolver = contentUri?.let {
            try {
                requireContext().contentResolver.getType(it)
            } catch (e: Exception) {
                null
            }
        }

        val ext = file.extension.takeIf { it.isNotBlank() }
                  ?: MimeTypeMap.getFileExtensionFromUrl(file.absolutePath)
        val mimeFromExt = ext?.lowercase()?.let {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(it)
        }

        val mime = mimeFromResolver ?: mimeFromExt ?: "application/octet-stream"

        // Reject non-image files
        if (!mime.startsWith("image/")) return null

        val requestBody = file.asRequestBody(mime.toMediaTypeOrNull())
        return MultipartBody.Part.createFormData("image", file.name, requestBody)
    }

    private fun extractDownloadUrl(bodyData: String?, rawResponse: String?): String? {
        bodyData?.takeIf { it.isNotBlank() }?.let { return it }

        if (rawResponse.isNullOrBlank()) return null

        return try {
            val parsed = Gson().fromJson(rawResponse, Map::class.java)
            extractUrlFromMap(parsed) ?: extractUrlWithRegex(rawResponse)
        } catch (e: Exception) {
            extractUrlWithRegex(rawResponse)
        }
    }

    private fun extractUrlFromMap(map: Map<*, *>?): String? {
        if (map == null) return null

        val keys = listOf("url", "data", "downloadUrl", "download_url", "profile", "file", "path")
        for (key in keys) {
            val value = map[key]
            when (value) {
                is String -> if (value.isNotBlank()) return value
                is Map<*, *> -> extractUrlFromMap(value)?.let { return it }
            }
        }
        return null
    }

    private fun extractUrlWithRegex(text: String): String? {
        val regex = "https?://[\\w\\-./?=&%:;#@+~]+".toRegex()
        return regex.find(text)?.value
    }
}
