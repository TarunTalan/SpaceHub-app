package com.example.myapplication.ui.common

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.myapplication.R
import android.view.ViewGroup
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog

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
                .setPositiveButton(positiveRes) { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onPositive?.invoke()
                }
                .setNegativeButton(negativeRes) { dialog: android.content.DialogInterface, _: Int ->
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
                .setPositiveButton(positiveRes) { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onPositive?.invoke()
                }
                .show()
        } catch (_: Exception) { }
    }

    // Create and return a themed progress dialog (caller is responsible for showing/dismissing)
    fun createProgressDialog(context: Context, title: String? = null, cancelable: Boolean = false, themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog): AlertDialog {
        val progress = ProgressBar(context)
        progress.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val builder: androidx.appcompat.app.AlertDialog.Builder = try {
            MaterialAlertDialogBuilder(context, themeRes)
        } catch (_: Exception) {
            androidx.appcompat.app.AlertDialog.Builder(context)
        }
        title?.let { builder.setTitle(it) }
        builder.setView(progress)
        builder.setCancelable(cancelable)
        return try {
            builder.create()
        } catch (_: Exception) {
            // Fallback to a plain AlertDialog if create fails
            androidx.appcompat.app.AlertDialog.Builder(context).apply {
                title?.let { setTitle(it) }
                setView(progress)
                setCancelable(cancelable)
            }.create()
        }
    }

    // Show an invite link dialog with Copy and Share actions
    fun showInviteLinkDialog(
        context: Context,
        title: String = "Invite link",
        link: String,
        onCopy: (() -> Unit)? = null,
        onShare: (() -> Unit)? = null,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ) {
        try {
            MaterialAlertDialogBuilder(context, themeRes)
                .setTitle(title)
                .setMessage(link)
                .setPositiveButton("Share") { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onShare?.invoke()
                }
                .setNeutralButton("Copy") { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onCopy?.invoke()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (_: Exception) {
            try {
                // Fallback: copy to clipboard and toast
                onCopy?.invoke()
            } catch (_: Exception) {}
        }
    }

    // Create a dialog around a custom view with optional callbacks (returns dialog so caller can show/dismiss)
    fun createViewDialog(
        context: Context,
        title: String? = null,
        customView: android.view.View,
        positiveText: String? = null,
        negativeText: String? = null,
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        cancelable: Boolean = true,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ): AlertDialog {
        val builder: androidx.appcompat.app.AlertDialog.Builder = try {
            MaterialAlertDialogBuilder(context, themeRes)
        } catch (_: Exception) {
            androidx.appcompat.app.AlertDialog.Builder(context)
        }
        title?.let { builder.setTitle(it) }
        builder.setView(customView)
        if (!positiveText.isNullOrBlank()) builder.setPositiveButton(positiveText) { d: android.content.DialogInterface, _: Int -> try { d.dismiss() } catch (_: Exception) {} ; onPositive?.invoke() }
        if (!negativeText.isNullOrBlank()) builder.setNegativeButton(negativeText) { d: android.content.DialogInterface, _: Int -> try { d.dismiss() } catch (_: Exception) {} ; onNegative?.invoke() }
        builder.setCancelable(cancelable)
        return try { builder.create() } catch (_: Exception) { androidx.appcompat.app.AlertDialog.Builder(context).setView(customView).create() }
    }

    // Show a simple items list (single choice) in a themed dialog
    fun showItemsDialog(
        context: Context,
        title: String? = null,
        items: Array<String>,
        onSelected: (index: Int) -> Unit,
        negativeText: String? = null,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ) {
        try {
            val builder = MaterialAlertDialogBuilder(context, themeRes)
            title?.let { builder.setTitle(it) }
            builder.setItems(items) { dialog: android.content.DialogInterface, which: Int ->
                try { dialog.dismiss() } catch (_: Exception) {}
                onSelected(which)
            }
            if (!negativeText.isNullOrBlank()) builder.setNegativeButton(negativeText, null)
            builder.show()
        } catch (_: Exception) {
            // fallback: call onSelected directly for first item
            if (items.isNotEmpty()) onSelected(0)
        }
    }

    // Variant of confirmation dialog that accepts plain strings (useful for quick inline prompts)
    fun showConfirmation(
        context: Context,
        title: String,
        message: String,
        positiveText: String = context.getString(android.R.string.ok),
        negativeText: String = context.getString(android.R.string.cancel),
        onPositive: (() -> Unit)? = null,
        onNegative: (() -> Unit)? = null,
        themeRes: Int = R.style.ThemeOverlay_MyApplication_MaterialAlertDialog
    ) {
        try {
            MaterialAlertDialogBuilder(context, themeRes)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(positiveText) { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onPositive?.invoke()
                }
                .setNegativeButton(negativeText) { dialog: android.content.DialogInterface, _: Int ->
                    try { dialog.dismiss() } catch (_: Exception) {}
                    onNegative?.invoke()
                }
                .show()
        } catch (_: Exception) { }
    }
}
