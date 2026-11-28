package com.c17.glass.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.spring
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp
import com.c17.glass.ui.theme.Glass
import com.c17.glass.ui.utils.DampedDragAnimation
import com.c17.glass.ui.utils.InteractiveHighlight
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

internal val LocalLiquidTabPosition =
    staticCompositionLocalOf { { 0f } }

internal val LocalLiquidTabClick =
    staticCompositionLocalOf< (Int) -> Unit > { {} }

internal val LocalLiquidSelectedIndex =
    staticCompositionLocalOf { { 0 } }

internal val LocalLiquidStaticActive =
    staticCompositionLocalOf { false }

/**
 * Draw 阶段通过 lambda 提供 activeFraction，滑块移动时只重绘图标与文字染色，零重组零文字重排。
 */
@Composable
fun RowScope.LiquidTab(
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.(activeFraction: () -> Float) -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val sliderPosition = LocalLiquidTabPosition.current
    val onTabClick = LocalLiquidTabClick.current
    val selectedIndex = LocalLiquidSelectedIndex.current
    val staticActive = LocalLiquidStaticActive.current
    val activeFractionProvider: () -> Float = remember(index, staticActive, sliderPosition) {
        if (staticActive) {
            { 1f }
        } else {
            { (1f - abs(sliderPosition() - index)).fastCoerceIn(0f, 1f) }
        }
    }

    Column(
        modifier
            .clip(Capsule())
            .semantics(mergeDescendants = true) {
                this.selected = selectedIndex() == index
                role = Role.Tab
                onClick {
                    onTabClick(index)
                    onClick()
                    true
                }
            }
            .focusable()
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                if (!staticActive) {
                    val currentScale = scale()
                    scaleX = currentScale
                    scaleY = currentScale
                }
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        content(activeFractionProvider)
    }
}

@Composable
fun RowScope.LiquidTab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    Column(
        modifier
            .clip(Capsule())
            .semantics(mergeDescendants = true) {
                this.selected = selected
                role = Role.Tab
                onClick {
                    onClick()
                    true
                }
            }
            .focusable()
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}

/**
 * 悬浮液态玻璃底部导航栏（参考 Kyant0/AndroidLiquidGlass `LiquidBottomTabs` 实现：
 * https://github.com/Kyant0/AndroidLiquidGlass ）：
 * 1. 外层玻璃底板（Row）：drawBackdrop 采样背景并渲染通透磨砂与边缘折射；
 * 2. 隐藏高亮录制层（Row）：通过 drawBackdrop 录制带模糊磨砂的底色与静态强调色图标到 tabsBackdrop；
 * 3. 滑动液态透镜胶囊（Box）：采样融合背景与 tabsBackdrop，按压或滑动时触发透镜放大、模糊折射与边缘高光。
 */
@Composable
fun LiquidTabBar(
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    backdrop: Backdrop,
    tabsCount: Int,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit
) {
    val colors = Glass.colors
    val isLightTheme = !colors.isDark
    val accentColor = colors.accent
    val containerColor =
        if (isLightTheme) Color(0xFFFAFAFA).copy(0.25f)
        else Color(0xFF222222).copy(0.65f)

    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(
        modifier,
        contentAlignment = Alignment.CenterStart
    ) {
        val density = LocalDensity.current
        var tabWidthPx by remember { mutableFloatStateOf(0f) }
        var totalWidthPx by remember { mutableFloatStateOf(0f) }

        val offsetAnimation = remember { Animatable(0f) }
        val rubberBandPx = with(density) { 4.dp.toPx() }
        val panelOffset by remember(rubberBandPx) {
            derivedStateOf {
                if (totalWidthPx == 0f) {
                    0f
                } else {
                    val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                    rubberBandPx * fraction.sign * EaseOut.transform(abs(fraction))
                }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()

        var currentIndex by remember { mutableIntStateOf(selectedTabIndex()) }
        val onSelectedUpdated by rememberUpdatedState(onTabSelected)

        fun indexAt(positionX: Float): Int {
            if (tabWidthPx == 0f) return currentIndex
            val horizontalPaddingPx = with(density) { 4.dp.toPx() }
            val logicalX = if (isLtr) positionX else totalWidthPx - positionX
            return ((logicalX - horizontalPaddingPx) / tabWidthPx)
                .toInt()
                .coerceIn(0, tabsCount - 1)
        }

        val dampedDragAnimation = remember(animationScope, tabsCount, density, isLtr) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedTabIndex().toFloat(),
                valueRange = 0f..(tabsCount - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                valueDampingRatio = 0.85f,
                valueStiffness = 520f,
                canDrag = { offset ->
                    offset.x in 0f..totalWidthPx
                },
                onDragStarted = { position ->
                    updateValue(indexAt(position.x).toFloat())
                },
                onDragStopped = {
                    val targetIndex = targetValue.roundToInt().coerceIn(0, tabsCount - 1)
                    if (currentIndex != targetIndex) {
                        currentIndex = targetIndex
                        onSelectedUpdated(targetIndex)
                    }
                    updateValue(targetIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDragCancelled = {
                    updateValue(currentIndex.toFloat())
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f && dragAmount.x != 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .coerceIn(0f, (tabsCount - 1).toFloat())
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                }
            )
        }

        fun activateTab(index: Int) {
            if (index !in 0 until tabsCount) return
            if (currentIndex != index) {
                currentIndex = index
                onSelectedUpdated(index)
            }
            dampedDragAnimation.animateToValue(index.toFloat())
        }

        val incomingSelectedTab = selectedTabIndex()
        LaunchedEffect(incomingSelectedTab) {
            if (currentIndex != incomingSelectedTab) {
                currentIndex = incomingSelectedTab
                dampedDragAnimation.animateToValue(incomingSelectedTab.toFloat())
            }
        }

        val interactiveHighlight = remember(animationScope) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    val currentTabWidth = if (tabWidthPx > 0f) tabWidthPx else size.width / tabsCount
                    Offset(
                        if (isLtr) (dampedDragAnimation.value + 0.5f) * currentTabWidth + panelOffset
                        else size.width - (dampedDragAnimation.value + 0.5f) * currentTabWidth + panelOffset,
                        size.height / 2f
                    )
                }
            )
        }

        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
            LocalLiquidTabPosition provides {
                dampedDragAnimation.value
            },
            LocalLiquidTabClick provides ::activateTab,
            LocalLiquidSelectedIndex provides { currentIndex }
        ) {
            Row(
                Modifier
                    .onGloballyPositioned { layoutCoordinates ->
                        totalWidthPx = layoutCoordinates.size.width.toFloat()
                        val contentWidthPx = totalWidthPx - with(density) { 8.dp.toPx() }
                        tabWidthPx = (contentWidthPx / tabsCount).coerceAtLeast(0f)
                    }
                    .selectableGroup()
                    .graphicsLayer {
                        translationX = panelOffset
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 12f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            vibrancy()
                            blur(2.5f.dp.toPx()) // 原生细腻磨砂液态玻璃
                            lens(24f.dp.toPx(), 24f.dp.toPx())
                        },
                        onDrawSurface = { drawRect(containerColor) }
                    )
                    .then(interactiveHighlight.modifier.then(interactiveHighlight.gestureModifier))
                    .then(dampedDragAnimation.modifier)
                    .height(64f.dp)
                    .fillMaxWidth()
                    .padding(4f.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = content
            )

            // 隐藏层在 tabsBackdrop 中同时录制磨砂模糊底色（与外层底板一致）与静态强调色标签，
            // 保证胶囊在按压与滑动过程中透出模糊毛玻璃桌面并用透镜锐利折射图标。
            CompositionLocalProvider(LocalLiquidStaticActive provides true) {
                Row(
                    Modifier
                        .clearAndSetSemantics {}
                        .alpha(0f)
                        .layerBackdrop(tabsBackdrop)
                        .graphicsLayer {
                            translationX = panelOffset
                        }
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { Capsule() },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress <= 0.001f) return@drawBackdrop
                                vibrancy()
                                blur(2.5f.dp.toPx())
                                lens(
                                    24f.dp.toPx() * progress,
                                    24f.dp.toPx() * progress
                                )
                            },
                            highlight = {
                                val progress = dampedDragAnimation.pressProgress
                                if (progress <= 0.001f) null else Highlight.Default.copy(alpha = progress)
                            },
                            onDrawSurface = { drawRect(containerColor) }
                        )
                        .then(interactiveHighlight.modifier)
                        .height(56f.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 4f.dp)
                        .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                    verticalAlignment = Alignment.CenterVertically,
                    content = content
                )
            }
        }

        if (tabWidthPx > 0f) {
            val tabWidthDp = with(density) { tabWidthPx.toDp() }
            val pillFadePaint = remember { Paint() }
            Box(
                Modifier
                    .padding(horizontal = 4f.dp)
                    .graphicsLayer {
                        val progressOffset = dampedDragAnimation.value * tabWidthPx
                        translationX = if (isLtr) progressOffset + panelOffset else -progressOffset + panelOffset
                    }
                    .drawBackdrop(
                        backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            lens(
                                10f.dp.toPx() * progress,
                                14f.dp.toPx() * progress,
                                chromaticAberration = true
                            )
                        },
                        highlight = {
                            val progress = dampedDragAnimation.pressProgress
                            Highlight.Default.copy(alpha = progress)
                        },
                        shadow = {
                            val progress = dampedDragAnimation.pressProgress
                            Shadow(alpha = progress)
                        },
                        innerShadow = {
                            val progress = dampedDragAnimation.pressProgress
                            // 保持恒定模糊半径以复用 RenderEffect 与离屏图层缓存，避免动画每帧重建。
                            InnerShadow(
                                radius = 8f.dp,
                                alpha = progress
                            )
                        },
                        layerBlock = {
                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY
                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                        },
                        onDrawBackdrop = { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            if (progress > 0.001f) {
                                if (progress < 0.999f) {
                                    pillFadePaint.alpha = progress
                                    drawContext.canvas.saveLayer(
                                        Rect(Offset.Zero, size),
                                        pillFadePaint
                                    )
                                    drawBackdrop()
                                    drawContext.canvas.restore()
                                } else {
                                    drawBackdrop()
                                }
                            }
                        },
                        onDrawSurface = {
                            val progress = dampedDragAnimation.pressProgress
                            val idleAlpha = (1f - progress).fastCoerceIn(0f, 1f)
                            // 深色模式采用微白高光透明胶囊，浅色模式采用微暗透亮胶囊，保留底层折射贯通感
                            val staticPillColor = if (isLightTheme) Color.Black.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.12f)
                            drawRect(
                                staticPillColor,
                                alpha = idleAlpha
                            )
                            if (progress > 0.001f) {
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            }
                        }
                    )
                    .height(56f.dp)
                    .width(tabWidthDp)
            )
        }
    }
}
