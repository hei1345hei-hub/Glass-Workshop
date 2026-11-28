package com.c17.glass.ui.utils

import androidx.compose.runtime.withFrameNanos

/** 等一帧，给弹簧动画留出提交时间。 */
suspend fun awaitFrame() {
    withFrameNanos { }
}
