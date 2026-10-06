package io.github.chayanforyou.quickball.domain.models

import androidx.compose.ui.graphics.ImageBitmap

data class InstalledApp(
    val packageName: String,
    val appName: String,
    /** Rasterised once at list size when the apps are loaded, never during composition. */
    val icon: ImageBitmap,
    var isSelected: Boolean = false
)
