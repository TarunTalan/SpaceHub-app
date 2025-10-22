package com.example.myapplication.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.myapplication.R

object AppDialogHelper {
    // Default to the app-specific theme overlay so all dialogs match the app style.

    fun showConfirmation(
        context: Context,
        @StringRes titleRes: Int,
        @StringRes messageRes: Int,
        @StringRes positiveRes: Int = android.R.string.ok,
        @StringRes negativeRes: Int = android.R.string.cancel,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ) {
        try {
            MaterialAlertDialogBuilder(context, themeRes)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(positiveRes) { dialog, _ ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onPositive?.invoke()
                }
                .setNegativeButton(negativeRes) { dialog, _ ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onNegative?.invoke()
                }
                .show()
        } catch (_: Exception) { /* ignore UI issues */ }
    }

    @Suppress("unused")
    fun showMessage(
        context: Context,
        @StringRes titleRes: Int? = null,
        @StringRes messageRes: Int,
        @StringRes positiveRes: Int = android.R.string.ok,
        onPositive: (() -> Unit)? = null,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ) {
        try {
            val builder = MaterialAlertDialogBuilder(context, themeRes)
            titleRes?.let { builder.setTitle(it) }
            builder
                .setMessage(messageRes)
                .setPositiveButton(positiveRes) { dialog, _ ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onPositive?.invoke()
                }
                .show()
        } catch (_: Exception) { }
    }
}
