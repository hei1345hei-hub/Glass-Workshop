package com.c17.glass.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun glassIcon(name: String, pathData: String): ImageVector {
    val nodes = PathParser().parsePathString(pathData).toNodes()
    return ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).addPath(
        pathData = nodes,
        fill = SolidColor(Color.Black)
    ).build()
}

/** 应用内使用的线面图标，全部为 24dp 视口矢量。 */
object GlassIcons {
    val Home: ImageVector by lazy { glassIcon("Home", "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z") }
    val Tune: ImageVector by lazy {
        glassIcon(
            "Tune",
            "M3 17v2h6v-2H3zm0-12v2h10V5H3zm10 16v-2h8v-2h-8zM7 9v6h2V9H7zm-4 4v2h8v-2H3zm12 0v2h6v-2h-6zm-2-4v2h8V9h-8z"
        )
    }
    val Log: ImageVector by lazy {
        glassIcon(
            "Log",
            "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H8v-2h6v2zm3-4H8v-2h9v2zm0-4H8V7h9v2z"
        )
    }
    val Info: ImageVector by lazy {
        glassIcon(
            "Info",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z"
        )
    }
    val ChevronLeft: ImageVector by lazy {
        glassIcon("ChevronLeft", "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z")
    }
    val ChevronRight: ImageVector by lazy {
        glassIcon("ChevronRight", "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z")
    }
    val Layers: ImageVector by lazy {
        glassIcon(
            "Layers",
            "M11.99 18.54l-7.37-5.73L3 14.07l9 7 9-7-1.63-1.27-7.38 5.74zM12 16l7.36-5.73L21 9l-9-7-9 7 1.63 1.27L12 16z"
        )
    }
    val Shield: ImageVector by lazy {
        glassIcon(
            "Shield",
            "M12 1L3 5v6c0 5.55 3.84 10.74 9 12 5.16-1.26 9-6.45 9-12V5l-9-4zm-2 16l-4-4 1.41-1.41L10 14.17l6.59-6.59L18 9l-8 8z"
        )
    }
    val Bolt: ImageVector by lazy {
        glassIcon(
            "Bolt",
            "M11 21h-1l1-7H7.5c-.58 0-.57-.32-.38-.66.19-.34.05-.08.07-.12C8.48 10.94 10.42 7.54 13 3h1l-1 7h3.5c.49 0 .56.33.47.51l-.07.15C12.96 17.06 11 21 11 21z"
        )
    }
    val Restore: ImageVector by lazy {
        glassIcon(
            "Restore",
            "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-7.99 8s3.57 8 7.99 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"
        )
    }
    val Wifi: ImageVector by lazy {
        glassIcon(
            "Wifi",
            "M1 9l2 2c4.97-4.97 13.03-4.97 18 0l2-2C16.93 3.93 7.08 3.93 1 9zm8 8l3 3 3-3c-1.65-1.66-4.34-1.66-6 0zm-4-4l2 2c2.76-2.76 7.24-2.76 10 0l2-2C15.14 9.14 8.87 9.14 5 13z"
        )
    }
    val Silent: ImageVector by lazy {
        glassIcon(
            "Silent",
            "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.9 2 2 2zm6-6v-5c0-3.07-1.63-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2zM4.27 3L3 4.27 20.73 22 22 20.73 4.27 3z"
        )
    }
    val Bluetooth: ImageVector by lazy {
        glassIcon(
            "Bluetooth",
            "M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4.29-4.29 4.29-4.29zM13 5.83l1.88 1.88-1.88 1.88V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z"
        )
    }
    val Code: ImageVector by lazy {
        glassIcon(
            "Code",
            "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"
        )
    }
    val Book: ImageVector by lazy {
        glassIcon(
            "Book",
            "M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z"
        )
    }
    val Bug: ImageVector by lazy {
        glassIcon(
            "Bug",
            "M21 8h-2.63c-.32-.75-.75-1.44-1.29-2.05l1.9-1.9-1.41-1.41-2.17 2.17C14.65 4.29 13.35 4 12 4s-2.65.29-3.4.81L6.43 2.64 5.02 4.05l1.9 1.9c-.54.61-.97 1.3-1.29 2.05H3v2h2.09c-.05.33-.09.66-.09 1v1H3v2h2v1c0 .34.04.67.09 1H3v2h2.63c.32.75.75 1.44 1.29 2.05l-1.9 1.9 1.41 1.41 2.17-2.17C9.35 19.71 10.65 20 12 20s2.65-.29 3.4-.81l2.17 2.17 1.41-1.41-1.9-1.9c.54-.61.97-1.3 1.29-2.05H21v-2h-2.09c.05-.33.09-.66.09-1v-1h2v-2h-2v-1c0-.34-.04-.67-.09-1zM12 18c-2.21 0-4-1.79-4-4v-4c0-2.21 1.79-4 4-4s4 1.79 4 4v4c0 2.21-1.79 4-4 4z"
        )
    }
    val Heart: ImageVector by lazy {
        glassIcon(
            "Heart",
            "M12 21.35l-1.45-1.32C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09C13.09 3.81 14.76 3 16.5 3 19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35z"
        )
    }
    val Download: ImageVector by lazy {
        glassIcon(
            "Download",
            "M5 20h14v-2H5v2zM19 9h-4V3H9v6H5l7 7 7-7z"
        )
    }
}

@Composable
fun GlassIcon(
    icon: ImageVector,
    tint: Color,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    Box(
        modifier
            .size(size)
            .paint(
                painter = rememberVectorPainter(icon),
                colorFilter = ColorFilter.tint(tint)
            )
    )
}

/** Draw 阶段动态取色的图标版本：色值插值时零重组。 */
@Composable
fun GlassIcon(
    icon: ImageVector,
    tint: () -> Color,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    val painter = rememberVectorPainter(icon)
    Canvas(modifier.size(size)) {
        with(painter) {
            draw(
                size = this@Canvas.size,
                colorFilter = ColorFilter.tint(tint())
            )
        }
    }
}
