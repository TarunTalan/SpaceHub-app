package com.example.myapplication.ui.common

import android.app.AlertDialog
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.graphics.scale
import androidx.fragment.app.Fragment
import com.example.myapplication.R
import kotlin.math.min
import androidx.activity.result.ActivityResultLauncher

class ImagePickerHelper(
    private val fragment: Fragment,
    private val targetSizePx: Int,
    private val onBitmapCropped: (Bitmap) -> Unit,
    private val onFileReady: (filePath: String?, contentUri: Uri?) -> Unit
) {
    private val context get() = fragment.requireContext()

    // Avoid caching files: return content Uri for gallery, bitmap for camera
    private val galleryLauncher: ActivityResultLauncher<String> = fragment.registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                onFileReady(null, uri)
            } catch (_: Exception) {
                onFileReady(null, uri)
            }
        }
    }

    private val cameraPreviewLauncher: ActivityResultLauncher<Void?> = fragment.registerForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) {
            try {
                val cropped = centerCropBitmap(bmp, targetSizePx)
                onBitmapCropped(cropped)
            } catch (_: Exception) { onBitmapCropped(bmp) }
        }
    }

    fun pickImageChooser() {
        val items = arrayOf("Take Photo", "Choose from Gallery")
        AlertDialog.Builder(context)
            .setTitle(R.string.select_image)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> cameraPreviewLauncher.launch(null)
                    1 -> galleryLauncher.launch("image/*")
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun centerCropBitmap(src: Bitmap, targetSize: Int): Bitmap {
        val side = min(src.width, src.height)
        val x = (src.width - side) / 2
        val y = (src.height - side) / 2
        val cropped = Bitmap.createBitmap(src, x, y, side, side)
        return cropped.scale(targetSize, targetSize)
    }
}
