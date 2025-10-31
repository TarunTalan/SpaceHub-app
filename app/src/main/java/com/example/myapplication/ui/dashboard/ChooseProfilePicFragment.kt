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
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatButton
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
import java.io.IOException
import kotlin.math.min
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.core.content.edit

class ChooseProfilePicFragment : BaseFragment(R.layout.fragment_choose_profile_pic) {
    private val sharedVm: ProfileSharedViewModel by activityViewModels()

    // Email passed from signup flow (if available)
    private var signupEmailArg: String? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Read signup email argument
        signupEmailArg = try {
            arguments?.getString("email")
        } catch (_: Exception) {
            null
        }

        // Clear any stale uploaded profile URL from previous flows
        try {
            sharedVm.setUploadedProfileUrl(null)
        } catch (_: Exception) {
        }

        // find main image and add icon
        val imgLogo = view.findViewById<ImageView>(R.id.img_logo)
        val addIcon = view.findViewById<ImageView>(R.id.add_icon)
        val outlineBase = ContextCompat.getDrawable(requireContext(), R.drawable.outline_circle_white)

        // target pixel size for final avatar (use dims resource)
        val logoSize = resources.getDimensionPixelSize(R.dimen.onboarding_logo_size)

        // helper: center-crop a bitmap to square and scale to target size
        fun centerCropBitmap(src: Bitmap, targetSize: Int): Bitmap {
            val side = min(src.width, src.height)
            val x = (src.width - side) / 2
            val y = (src.height - side) / 2
            val cropped = Bitmap.createBitmap(src, x, y, side, side)
            return cropped.scale(targetSize, targetSize)
        }

        // Write a bitmap to cache and return the absolute path. Overwrites previous profile cache.
        fun writeBitmapToCache(bitmap: Bitmap): String? {
            val cacheDir = requireContext().cacheDir
            val filename = "profile_pic.png"
            val outFile = File(cacheDir, filename)
            try {
                // Delete any previously stored path (if different) to avoid orphan files
                try {
                    sharedVm.selectedImagePath.value?.let { oldPath ->
                        try {
                            if (oldPath != outFile.absolutePath) {
                                File(oldPath).takeIf { it.exists() }?.delete()
                            }
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                }

                // If the fixed file exists, delete it so we overwrite cleanly
                try {
                    if (outFile.exists()) outFile.delete()
                } catch (_: Exception) {
                }

                // Write the bitmap to the fixed cache file
                FileOutputStream(outFile).use { fos ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos)
                    fos.flush()
                }

                // Ensure lastModified is updated to invalidate Glide signatures based on timestamp
                try {
                    outFile.setLastModified(System.currentTimeMillis())
                } catch (_: Exception) {
                }

                return outFile.absolutePath
            } catch (_: IOException) {
                // ignore write errors
            }
            return null
        }

        // show a simple crop preview dialog (center square) and let user Use or Retake
        fun showCropDialog(original: Bitmap, onUse: (Bitmap) -> Unit, onRetake: () -> Unit) {
            val preview = centerCropBitmap(original, logoSize)
            val previewIv = ImageView(requireContext()).apply {
                setImageBitmap(preview)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (8 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }

            AlertDialog.Builder(requireContext())
                .setTitle(R.string.choose_profile_pic)
                .setView(previewIv)
                .setPositiveButton(android.R.string.ok) { _, _ -> onUse(preview) }
                .setNegativeButton(R.string.skip) { _, _ -> onRetake() }
                .setCancelable(true)
                .show()
        }

        // apply cropped bitmap to the main logo
        fun applyCroppedBitmap(bm: Bitmap) {
            // Persist the cropped bitmap to cache and store its path in shared ViewModel
            try {
                val path = writeBitmapToCache(bm)
                if (path != null) {
                    sharedVm.setImagePath(path)
                    // Load via Glide with circleCrop to ensure consistent circular display
                    try {
                        val file = File(path)
                        Glide.with(this).clear(imgLogo)
                        imgLogo.setImageDrawable(null)
                        Glide.with(this)
                            .load(file)
                            .signature(ObjectKey(file.absolutePath + "-" + file.lastModified()))
                            .circleCrop()
                            .listener(object : RequestListener<Drawable?> {
                                override fun onLoadFailed(
                                    e: GlideException?,
                                    model: Any?,
                                    target: Target<Drawable?>?,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    try {
                                        // try forcing a fresh load
                                        Glide.with(this@ChooseProfilePicFragment).clear(imgLogo)
                                        Glide.with(this@ChooseProfilePicFragment)
                                            .load(file)
                                            .skipMemoryCache(true)
                                            .signature(ObjectKey(file.absolutePath + "-" + file.lastModified()))
                                            .circleCrop()
                                            .into(imgLogo)
                                    } catch (_: Exception) {
                                    }
                                    return false
                                }

                                override fun onResourceReady(
                                    resource: Drawable?,
                                    model: Any?,
                                    target: Target<Drawable?>?,
                                    dataSource: DataSource?,
                                    isFirstResource: Boolean
                                ): Boolean {
                                    return false
                                }
                            })
                            .into(imgLogo)

                    } catch (_: Exception) {
                        // fallback: set bitmap directly
                        imgLogo.setImageBitmap(bm)
                    }
                } else {
                    imgLogo.setImageBitmap(bm)
                }
                // outline is now rendered by the circle_mask stroke background
            } catch (_: Exception) {
            }
        }

        // Helper: copy a content Uri to the app cache and return the path (overwrites fixed filename)
        fun writeUriToCache(uri: Uri): String? {
            val cacheDir = requireContext().cacheDir
            val filename = "profile_pic.png"
            val outFile = File(cacheDir, filename)
            try {
                // Remove previous file if it exists
                try {
                    if (outFile.exists()) outFile.delete()
                } catch (_: Exception) {
                }

                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(outFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }

                // verify file written
                if (outFile.exists()) {
                    return outFile.absolutePath
                }
            } catch (_: Exception) {
                // ignore
            }
            return null
        }

        // ActivityResult launchers: TakePicture (writes to provided Uri) and GetContent (returns a Uri)
        lateinit var cameraLauncher: ActivityResultLauncher<Uri>
        lateinit var galleryLauncher: ActivityResultLauncher<String>

        // Create a content Uri for the camera to write into. We'll reuse a fixed cache filename.
        fun createImageContentUri(): Uri? {
            return try {
                val cacheDir = requireContext().cacheDir
                val filename = "profile_pic.png"
                val file = File(cacheDir, filename)
                // Ensure parent exists
                file.parentFile?.mkdirs()
                // Delete previous file to avoid reusing stale content
                try {
                    if (file.exists()) file.delete()
                } catch (_: Exception) {
                }

                // Use FileProvider to get content Uri
                val authority = requireContext().packageName + ".provider"
                FileProvider.getUriForFile(requireContext(), authority, file).also { uri ->
                    // grant temporary write permission for camera app
                    try {
                        requireContext().grantUriPermission(
                            requireContext().packageName,
                            uri,
                            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        )
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
                null
            }
        }

        cameraLauncher = registerForActivityResult(ActivityResultContracts.TakePicture()) { success: Boolean ->
            if (success) {
                val cacheDir = requireContext().cacheDir
                val file = File(cacheDir, "profile_pic.png")
                if (file.exists()) {
                    // Resize/compress the image before saving and uploading
                    try {
                        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                        if (bitmap != null) {
                            val maxDim = 512 // Reduce to 512x512 for strict server limits
                            val scaled = bitmap.scale(maxDim, maxDim)
                            FileOutputStream(file, false).use { fos ->
                                scaled.compress(Bitmap.CompressFormat.JPEG, 60, fos) // Lower quality to 60
                                fos.flush()
                            }
                        }
                    } catch (_: Exception) {}
                    val contentUri = try {
                        val authority = requireContext().packageName + ".provider"
                        FileProvider.getUriForFile(requireContext(), authority, file)
                    } catch (_: Exception) {
                        null
                    }
                    if (contentUri != null) {
                        sharedVm.setSelectedContentUri(contentUri)
                        sharedVm.setImagePath(file.absolutePath)
                        try {
                            Glide.with(this).clear(imgLogo)
                            Glide.with(this)
                                .load(file)
                                .signature(ObjectKey(file.absolutePath + "-" + file.lastModified()))
                                .circleCrop()
                                .into(imgLogo)
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
                    stream?.use {
                        decoded = BitmapFactory.decodeStream(it)
                    }
                    if (decoded != null) {
                        showCropDialog(
                            decoded,
                            onUse = { cropped -> applyCroppedBitmap(cropped) },
                            onRetake = { galleryLauncher.launch("image/*") })
                        return@registerForActivityResult
                    }
                    val cachedPath = writeUriToCache(uri)
                    if (cachedPath != null) {
                        try {
                            sharedVm.setImagePath(cachedPath)
                            Glide.with(this).clear(imgLogo)
                            imgLogo.setImageDrawable(null)
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
                                            Glide.with(this@ChooseProfilePicFragment).clear(imgLogo)
                                            Glide.with(this@ChooseProfilePicFragment)
                                                .load(File(cachedPath))
                                                .skipMemoryCache(true)
                                                .signature(
                                                    ObjectKey(
                                                        File(cachedPath).absolutePath + "-" + File(
                                                            cachedPath
                                                        ).lastModified()
                                                    )
                                                )
                                                .circleCrop()
                                                .into(imgLogo)
                                        } catch (_: Exception) {
                                        }
                                        return false
                                    }
                                    override fun onResourceReady(
                                        resource: Drawable?,
                                        model: Any?,
                                        target: Target<Drawable?>?,
                                        dataSource: DataSource?,
                                        isFirstResource: Boolean
                                    ): Boolean {
                                        return false
                                    }
                                })
                                .into(imgLogo)
                        } catch (_: Exception) {
                            try {
                                Glide.with(this).clear(imgLogo); imgLogo.setImageDrawable(null); Glide.with(this)
                                    .load(uri).circleCrop().into(imgLogo)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        try {
                            Glide.with(this).clear(imgLogo); imgLogo.setImageDrawable(null); Glide.with(this).load(uri)
                                .circleCrop().into(imgLogo)
                        } catch (_: Exception) {
                        }
                    }
                } catch (_: Exception) {
                    try {
                        Glide.with(this).clear(imgLogo); imgLogo.setImageDrawable(null); Glide.with(this).load(uri)
                            .circleCrop().into(imgLogo)
                    } catch (_: Exception) {
                    }
                }
            }
        }

        // helper to convert Drawable to Bitmap
        fun drawableToBitmap(drawable: Drawable?): Bitmap? {
            if (drawable == null) return null
            if (drawable is BitmapDrawable) return drawable.bitmap
            val w = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else logoSize
            val h = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else logoSize
            return try {
                val bmp = createBitmap(w, h)
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                bmp
            } catch (_: Exception) {
                null
            }
        }

        // helper to apply selection: copy drawable and keep add icon visible
        fun applyAvatarDrawable(drawable: Drawable?, resId: Int? = null) {
            try {
                // convert source (resource or drawable instance) to a center-cropped bitmap sized to logoSize
                val sourceDrawable = if (resId != null) ContextCompat.getDrawable(requireContext(), resId) else drawable
                val bmp = drawableToBitmap(sourceDrawable)
                val cropped = bmp?.let { centerCropBitmap(it, logoSize) }

                if (cropped != null) {
                    // Persist cropped bitmap to cache and update shared ViewModel
                    val path = writeBitmapToCache(cropped)
                    if (path != null) {
                        sharedVm.setImagePath(path)

                        // Load the cached file via Glide with signature to avoid stale cache
                        try {
                            val f = File(path)
                            Glide.with(this).clear(imgLogo)
                            imgLogo.setImageDrawable(null)
                            Glide.with(this)
                                .load(f)
                                .signature(ObjectKey(f.absolutePath + "-" + f.lastModified()))
                                .circleCrop()
                                .into(imgLogo)
                            return
                        } catch (_: Exception) {
                        }
                    }
                }

                // Fallback: if conversion/writing failed, still try to display the drawable/resId via Glide
                if (resId != null) {
                    try {
                        Glide.with(this).load(resId).circleCrop().into(imgLogo)
                        sharedVm.setDrawableRes(resId)
                        return
                    } catch (_: Exception) {
                    }
                }

                if (drawable != null) {
                    try {
                        Glide.with(this).load(drawable).circleCrop().into(imgLogo)
                    } catch (_: Exception) {
                        imgLogo.setImageDrawable(drawable)
                    }
                }
            } catch (_: Exception) {
            }
        }

        // list of avatar ids to wire up
        val avatarIds = listOf(
            R.id.avatar_1, R.id.avatar_2, R.id.avatar_3, R.id.avatar_4,
            R.id.avatar_5, R.id.avatar_6, R.id.avatar_7, R.id.avatar_8
        )

        var lastSelected: ImageView? = null

        for (id in avatarIds) {
            val av = view.findViewById<ImageView?>(id)
            av?.setOnClickListener { v ->
                // cast the clicked view to ImageView so we can safely access `.drawable`
                val imageView = v as? ImageView
                val drawable = imageView?.background ?: imageView?.drawable

                // clear previous selection outline
                lastSelected?.foreground = null
                // no overlay outline to clear; avatar foreground outline remains

                // apply outline to tapped avatar (use a fresh drawable instance)
                val outlineForAvatar = outlineBase?.constantState?.newDrawable()?.mutate()
                imageView?.foreground = outlineForAvatar
                lastSelected = imageView

                // determine resource id mapping for the tapped avatar
                val resId = when (v.id) {
                    R.id.avatar_1 -> R.drawable.avatar_1
                    R.id.avatar_2 -> R.drawable.avatar_2
                    R.id.avatar_3 -> R.drawable.avatar_3
                    R.id.avatar_4 -> R.drawable.avatar_4
                    R.id.avatar_5 -> R.drawable.avatar_5
                    R.id.avatar_6 -> R.drawable.avatar_6
                    R.id.avatar_7 -> R.drawable.avatar_7
                    R.id.avatar_8 -> R.drawable.avatar_8
                    else -> 0
                }

                if (resId != 0) {
                    // apply via resource id path (more reliable for caching)
                    applyAvatarDrawable(null, resId)
                } else {
                    // if no resource mapping, fallback to drawable instance
                    if (drawable != null) applyAvatarDrawable(drawable)
                }
            }
        }

        // open a chooser dialog to pick from Camera or Gallery
        addIcon?.setOnClickListener {
            val items = arrayOf("Take Photo", "Choose from Gallery")
            AlertDialog.Builder(requireContext())
                .setTitle("Select image")
                .setItems(items) { _, which ->
                    when (which) {
                        0 -> {
                            // Camera intent (write to file via FileProvider)
                            val uri = createImageContentUri()
                            if (uri != null) {
                                // launch camera with the content uri where image should be saved
                                cameraLauncher.launch(uri)
                            } else {
                                // fallback to old preview flow if content uri couldn't be created
                                try {
                                    // fallback: use TakePicturePreview
                                    val legacy =
                                        registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
                                            if (bmp != null) showCropDialog(
                                                bmp,
                                                onUse = { cropped -> applyCroppedBitmap(cropped) },
                                                onRetake = { /* no-op */ })
                                        }
                                    legacy.launch(null)
                                } catch (_: Exception) {
                                }
                            }
                        }

                        1 -> {
                            // Gallery pick
                            galleryLauncher.launch("image/*")
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        // Wire Skip and Next buttons to hide the add icon only when the user explicitly proceeds.
        val skipTv = view.findViewById<TextView>(R.id.tv_skip)
        val nextBtn = view.findViewById<AppCompatButton>(R.id.btn_next)

        // Helper to enable/disable Next button and swap background (dull-blue when disabled)
        fun setNextEnabled(enabled: Boolean) {
            try {
                nextBtn?.isEnabled = enabled
                val bgRes = if (enabled) R.drawable.rounded_button_bg else R.drawable.rounded_button_bg_dull_blue
                nextBtn?.background = AppCompatResources.getDrawable(requireContext(), bgRes)
                // Ensure opacity remains full (user requested opacity 1)
                nextBtn?.alpha = 1.0f
            } catch (_: Exception) {
            }
        }

        // Update Next based on current ViewModel selection state
        val updateNext = {
            val hasImage =
                !sharedVm.selectedImagePath.value.isNullOrBlank() || (sharedVm.selectedDrawableRes.value != null)
            setNextEnabled(hasImage)
        }

        // Observe changes to selection
        sharedVm.selectedImagePath.observe(viewLifecycleOwner) { updateNext() }
        sharedVm.selectedDrawableRes.observe(viewLifecycleOwner) { updateNext() }
        // initialize button state
        updateNext()

        skipTv?.setOnClickListener {
            // Always use the default profile drawable when Skip is tapped (Option A)
            try {
                applyAvatarDrawable(null, R.drawable.default_profile)
            } catch (_: Exception) {
            }
            // Navigate to UsernameFragment after selection
            try {
                val emailToSend = signupEmailArg ?: (arguments?.getString("email") ?: "")
                val bundle = bundleOf("email" to emailToSend)
                // Persist that we completed choose_profile so app can resume to username later
                try {
                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit { putString("last_screen", "choose_profile") }
                } catch (_: Exception) { }
                // Pop chooseProfilePicFragment so user cannot navigate back to it
                val navOptions = androidx.navigation.NavOptions.Builder()
                    .setPopUpTo(R.id.chooseProfilePicFragment, true)
                    .build()
                findNavController().navigate(R.id.action_chooseProfilePicFragment_to_usernameFragment, bundle, navOptions)
            } catch (_: Exception) {
            }
            // keep existing behavior for Skip (if navigation is handled elsewhere, do not interfere)
        }

        nextBtn?.setOnClickListener {
            val emailToSend = try {
                signupEmailArg ?: (arguments?.getString("email") ?: "")
            } catch (_: Exception) {
                ""
            }
            val imagePath = sharedVm.selectedImagePath.value
            val fileValid = imagePath?.let { path ->
                val f = File(path)
                f.exists() && f.length() > 0
            } ?: false
            if (!fileValid) {
                setNextEnabled(false)
                return@setOnClickListener
            }
            lifecycleScope.launch {
                setLoaderVisible(true)
                try {
                    uploadProfile(emailToSend)
                } finally {
                    setLoaderVisible(false)
                }
                val uploadedUrl = try { sharedVm.uploadedProfileUrl.value } catch (_: Exception) { null }
                if (!uploadedUrl.isNullOrBlank()) {
                    val prefs = requireActivity().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit { putString("profile_image_url", uploadedUrl) }
                }
                try {
                    val bundle = bundleOf("email" to emailToSend)
                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    prefs.edit { putString("last_screen", "choose_profile") }
                    val navOptions = androidx.navigation.NavOptions.Builder()
                        .setPopUpTo(R.id.chooseProfilePicFragment, true)
                        .build()
                    findNavController().navigate(R.id.action_chooseProfilePicFragment_to_usernameFragment, bundle, navOptions)
                } catch (_: Exception) {}
            }
        }
    }

    // Upload helper: sends multipart/form-data containing two text fields (email, image_uri)
    // and an optional file part named "image" when a cached file exists.
    private suspend fun uploadProfile(email: String): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val api = NetworkModule.createApiService(requireContext())
                val emailRb = (email).toRequestBody("text/plain".toMediaTypeOrNull())
                val imgPath = try {
                    sharedVm.selectedImagePath.value
                } catch (_: Exception) {
                    null
                }
                val imageUriRb = (imgPath ?: "").toRequestBody("text/plain".toMediaTypeOrNull())
                val filePart = try {
                    if (!imgPath.isNullOrBlank()) {
                        val f = File(imgPath)
                        if (f.exists()) {
                            val contentUri = sharedVm.selectedContentUri.value
                            val mimeFromResolver = try {
                                contentUri?.let { requireContext().contentResolver.getType(it) }
                            } catch (_: Exception) {
                                null
                            }
                            val ext = f.extension.takeIf { it.isNotBlank() }
                                ?: MimeTypeMap.getFileExtensionFromUrl(f.absolutePath)
                            val mimeFromExt =
                                ext?.lowercase()?.let { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
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
                } catch (_: Exception) {
                    null
                }
                val resp = api.uploadProfile(emailRb, imageUriRb, filePart)
                var success = false
                val rawRespBodyString: String? = try {
                    try { resp.raw().peekBody(1024 * 1024).string() } catch (_: Exception) { null }
                } catch (_: Exception) { null }
                if (resp.isSuccessful) {
                    try {
                        val body = resp.body()
                        var downloadUrl = body?.data
                        if ((downloadUrl == null || downloadUrl.isBlank()) && !rawRespBodyString.isNullOrBlank()) {
                            try {
                                val parsed = Gson().fromJson(rawRespBodyString, Map::class.java)
                                fun extractFromAny(x: Any?): String? {
                                    try {
                                        when (x) {
                                            is String -> return x.takeIf { it.isNotBlank() }
                                            is Map<*, *> -> {
                                                val keys = listOf("url", "data", "downloadUrl", "download_url", "profile", "file", "path")
                                                for (k in keys) {
                                                    val v = x[k]
                                                    val cand = extractFromAny(v)
                                                    if (!cand.isNullOrBlank()) return cand
                                                }
                                                for (v in x.values) {
                                                    val cand = extractFromAny(v)
                                                    if (!cand.isNullOrBlank()) return cand
                                                }
                                            }
                                            is Collection<*> -> {
                                                for (v in x) {
                                                    val cand = extractFromAny(v)
                                                    if (!cand.isNullOrBlank()) return cand
                                                }
                                            }
                                        }
                                    } catch (_: Exception) { }
                                    return null
                                }
                                val candidate = parsed["data"] ?: parsed["url"] ?: parsed["result"] ?: parsed
                                val got = extractFromAny(candidate)
                                if (!got.isNullOrBlank()) downloadUrl = got
                                if (downloadUrl.isNullOrBlank()) {
                                    val regex = "https?://[\\w\\-./?=&%:;#@+~]+".toRegex()
                                    val m = regex.find(rawRespBodyString)
                                    if (m != null) downloadUrl = m.value
                                }
                            } catch (_: Exception) {
                                try {
                                    val regex = "https?://[\\w\\-./?=&%:;#@+~]+".toRegex()
                                    val m = regex.find(rawRespBodyString)
                                    if (m != null) downloadUrl = m.value
                                } catch (_: Exception) { }
                            }
                        }
                        if (!downloadUrl.isNullOrBlank()) {
                            try {
                                withContext(Dispatchers.Main) {
                                    try { sharedVm.setUploadedProfileUrl(downloadUrl) } catch (_: Exception) { }
                                    try {
                                        val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                        prefs.edit { putString("uploaded_profile_url", downloadUrl) }
                                    } catch (_: Exception) { }
                                }
                            } catch (_: Exception) {
                                try { sharedVm.setUploadedProfileUrl(downloadUrl) } catch (_: Exception) { }
                                try {
                                    val prefs = requireContext().getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                                    prefs.edit { putString("uploaded_profile_url", downloadUrl) }
                                } catch (_: Exception) { }
                            }
                        }
                        success = true
                    } catch (_: Exception) {
                        success = false
                    }
                } else {
                    success = false
                }
                return@withContext success
            } catch (_: Exception) {
                return@withContext false
            }
        }
    }
}
