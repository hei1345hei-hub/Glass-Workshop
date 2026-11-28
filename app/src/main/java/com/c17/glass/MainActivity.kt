package com.c17.glass

import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.c17.glass.core.AppPrefs
import com.c17.glass.core.TweakState
import com.c17.glass.ui.GlassApp
import com.c17.glass.ui.screens.LicenseGate
import com.c17.glass.ui.theme.C17GlassTheme
import com.c17.glass.ui.theme.ThemeState

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppPrefs.init(this)
        ThemeState.mode = AppPrefs.instance.loadTheme()
        TweakState.applyConfig(AppPrefs.instance.loadConfig())

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        enableMaxRefreshRate()

        setContent {
            C17GlassTheme {
                val dark = ThemeState.isDark
                val view = LocalView.current

                DisposableEffect(dark, view) {
                    val activity = view.context as? Activity
                    if (activity != null) {
                        WindowCompat.getInsetsController(activity.window, view).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                    onDispose { }
                }

                // 首次启动的反倒卖声明：只有手动完整输入后才能进入主界面。
                var licensed by remember { mutableStateOf(AppPrefs.instance.isLicenseAccepted()) }
                if (licensed) {
                    GlassApp()
                } else {
                    LicenseGate(
                        onAccept = {
                            AppPrefs.instance.acceptLicense()
                            licensed = true
                        }
                    )
                }
            }
        }
    }

    /** 尽量锁定到屏幕支持的最高刷新率，让玻璃动效更顺。 */
    private fun enableMaxRefreshRate() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val modes = display?.supportedModes ?: return
                val maxMode = modes.maxByOrNull { it.refreshRate } ?: return
                if (maxMode.refreshRate >= 90f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                        preferredRefreshRate = maxMode.refreshRate
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val modes = windowManager.defaultDisplay.supportedModes ?: return
                val maxMode = modes.maxByOrNull { it.refreshRate } ?: return
                if (maxMode.refreshRate >= 90f) {
                    window.attributes = window.attributes.apply {
                        preferredDisplayModeId = maxMode.modeId
                        preferredRefreshRate = maxMode.refreshRate
                    }
                }
            }
        }
    }
}
