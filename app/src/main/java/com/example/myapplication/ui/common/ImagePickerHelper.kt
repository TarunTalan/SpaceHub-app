package com.example.myapplication.ui.common

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.scale
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.min
import androidx.core.content.FileProvider
import androidx.activity.result.ActivityResultLauncher
import android.graphics.Bitmap.CompressFormat
import com.example.myapplication.data.dashboard.DashboardRepository
/**
 * Helper that centralizes image picking (camera/gallery), writing a fixed cache file,
 * optional simple crop-preview and multipart uploading.
 */
class ImagePickerHelper(
    private val fragment: Fragment,
    private val filename: String = "profile_pic.png",
    private val targetSizePx: Int,
    private val onBitmapCropped: (Bitmap) -> Unit,
    private val onFileReady: (filePath: String?, contentUri: Uri?) -> Unit
) {
    private val context get() = fragment.requireContext()

    // initialize launchers as vals to avoid lateinit usage
    private val cameraLauncher: ActivityResultLauncher<Uri> = fragment.registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success) {
            val cacheDir = context.cacheDir
            val file = File(cacheDir, filename)
            if (file.exists()) {
                try {
                    val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    if (bitmap != null) {
                        val maxDim = 512
                        val scaled = bitmap.scale(maxDim, maxDim)
                        FileOutputStream(file, false).use { fos ->
                            scaled.compress(CompressFormat.JPEG, 60, fos)
                            fos.flush()
                        }
                    }
                } catch (_: Exception) {}
                val contentUri = try {
                    val authority = context.packageName + ".provider"
                    FileProvider.getUriForFile(context, authority, file)
                } catch (_: Exception) { null }
                onFileReady(file.absolutePath, contentUri)
            }
        }
    }

    private val galleryLauncher: ActivityResultLauncher<String> = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val stream = context.contentResolver.openInputStream(uri)
                var decoded: Bitmap? = null
                stream?.use { decoded = BitmapFactory.decodeStream(it) }
                if (decoded != null) {
                    showCropDialog(decoded)
                    return@registerForActivityResult
                }
                val cached = writeUriToCache(uri)
                onFileReady(cached, uri)
            } catch (_: Exception) {
                onFileReady(null, uri)
            }
        }
    }

    fun pickImageChooser() {
        val items = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(context)
            .setTitle(R.string.select_image)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val uri = createImageContentUri()
                        if (uri != null) cameraLauncher.launch(uri) else {
                            try {
                                val legacy = fragment.registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp: Bitmap? ->
                                    if (bmp != null) showCropDialog(bmp)
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

    private fun createImageContentUri(): Uri? {
        return try {
            val cacheDir = context.cacheDir
            val file = File(cacheDir, filename)
            file.parentFile?.mkdirs()
            try { if (file.exists()) file.delete() } catch (_: Exception) {}
            val authority = context.packageName + ".provider"
            FileProvider.getUriForFile(context, authority, file).also { uri ->
                try { context.grantUriPermission(context.packageName, uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) } catch (_: Exception) {}
            }
        } catch (_: Exception) { null }
    }

    private fun writeUriToCache(uri: Uri): String? {
        val cacheDir = context.cacheDir
        val outFile = File(cacheDir, filename)
        try {
            try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}
            context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(outFile).use { output -> input.copyTo(output); output.flush() } }
            if (outFile.exists()) return outFile.absolutePath
        } catch (_: Exception) {}
        return null
    }

    private fun centerCropBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return cropped.scale(targetSize, targetSize)
    }

    private fun showCropDialog(original: Bitmap) {
        val preview = centerCropBitmap(original, targetSizePx)
        val previewIv = android.widget.ImageView(context).apply {
            setImageBitmap(preview)
            adjustViewBounds = true
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            val pad = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(context)
            .setTitle(R.string.select_image)
            .setView(previewIv)
            .setPositiveButton(android.R.string.ok) { _, _ -> onBitmapCropped(preview) }
            .setNegativeButton(R.string.skip) { _, _ -> /* retake: nothing */ }
            .setCancelable(true)
            .show()
    }

    suspend fun uploadFile(email: String?, sharedVm: ProfileSharedViewModel?): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val repo = DashboardRepository(context)
                val imgPath = try { sharedVm?.selectedImagePath?.value } catch (_: Exception) { null }
                val contentUri = try { sharedVm?.selectedContentUri?.value } catch (_: Exception) { null }
                val result = repo.uploadProfileImage(email, imgPath, contentUri)
                if (result.success && !result.downloadUrl.isNullOrBlank()) {
                    try { withContext(Dispatchers.Main) { sharedVm?.setUploadedProfileUrl(result.downloadUrl) } } catch (_: Exception) { try { sharedVm?.setUploadedProfileUrl(result.downloadUrl) } catch (_: Exception) {} }
                }
                return@withContext result.success
            } catch (_: Exception) { return@withContext false }
        }
    }

    // expose writeBitmapToCache for callers that need to persist processed bitmaps
    fun writeBitmapToCache(bitmap: Bitmap): String? {
        return try {
            val cacheDir = context.cacheDir
            val outFile = File(cacheDir, filename)
            try { if (outFile.exists()) outFile.delete() } catch (_: Exception) {}
            FileOutputStream(outFile).use { fos -> bitmap.compress(CompressFormat.PNG, 100, fos); fos.flush() }
            try { outFile.setLastModified(System.currentTimeMillis()) } catch (_: Exception) {}
            outFile.absolutePath
        } catch (_: Exception) { null }
    }
}
