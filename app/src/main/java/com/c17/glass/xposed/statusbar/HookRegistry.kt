package com.c17.glass.xposed.statusbar

import com.c17.glass.xposed.glass.FluidGlassHook

// 状态栏开发期间只挂载液态玻璃；其余状态栏 Hook 不安装，避免应用/重启改变系统原样。
fun statusbarHooks(): List<StatusbarHook> = listOf(
    FluidGlassHook
)
