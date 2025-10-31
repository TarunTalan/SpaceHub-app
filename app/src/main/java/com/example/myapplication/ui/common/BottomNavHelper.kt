package com.example.myapplication.ui.common

import android.content.Context
import android.graphics.Paint
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.example.myapplication.R

object BottomNavHelper {

    fun attach(
        context: Context,
        iconToLabel: List<Pair<ImageView, TextView>>,
        // optional callbacks aligned with iconToLabel; null = no action
        callbacks: List<(() -> Unit)?> = emptyList()
    ) {
        val focusColor = ContextCompat.getColor(context, R.color.color_tab_focus)
        val defaultColor = ContextCompat.getColor(context, R.color.white)

        fun resetAll() {
            for ((icon, label) in iconToLabel) {
                icon.setColorFilter(defaultColor)
                label.setTextColor(defaultColor)
                label.paintFlags = label.paintFlags and Paint.UNDERLINE_TEXT_FLAG.inv()
            }
        }

        // default: select first
        resetAll()
        val (firstIcon, firstLabel) = iconToLabel.first()
        firstIcon.setColorFilter(focusColor)
        firstLabel.setTextColor(focusColor)
        firstLabel.paintFlags = firstLabel.paintFlags or Paint.UNDERLINE_TEXT_FLAG

        for ((index, pair) in iconToLabel.withIndex()) {
            val (icon, label) = pair
            icon.setOnClickListener {
                resetAll()
                icon.setColorFilter(focusColor)
                label.setTextColor(focusColor)
                label.paintFlags = label.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                // invoke optional callback if provided
                if (index < callbacks.size) callbacks[index]?.invoke()
            }

            // also allow clicking label itself
            label.setOnClickListener {
                resetAll()
                icon.setColorFilter(focusColor)
                label.setTextColor(focusColor)
                label.paintFlags = label.paintFlags or Paint.UNDERLINE_TEXT_FLAG
                if (index < callbacks.size) callbacks[index]?.invoke()
            }
        }
    }
}
