package com.example.storevisitmanagementsystem.ui.task

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding

internal fun View.applyTaskWindowInsets() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val padding = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or
                WindowInsetsCompat.Type.displayCutout() or
                WindowInsetsCompat.Type.ime()
        )
        view.updatePadding(left = padding.left, top = padding.top, right = padding.right, bottom = padding.bottom)
        insets
    }
    ViewCompat.requestApplyInsets(this)
}
