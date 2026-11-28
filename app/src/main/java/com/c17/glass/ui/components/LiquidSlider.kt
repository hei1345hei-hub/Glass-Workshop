package com.c17.glass.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.c17.glass.ui.theme.Glass
import com.c17.glass.ui.utils.DampedDragAnimation
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs

@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    visibilityThreshold: Float,
    backdrop: Backdrop,
    modifier: Modifier = Modifier
) {
    val colors = Glass.colors
    val currentValue by rememberUpdatedState(value)
    val onValueChangeUpdated by rememberUpdatedState(onValueChange)
    val accentColor = colors.accent
    val trackColor =
        if (colors.isDark) Color(0xFF787880).copy(0.36f)
        else Color(0xFF787878).copy(0.2f)

    val trackBackdrop = rememberLayerBackdrop()
    var trackCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var thumbCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(36f.dp)
            .onGloballyPositioned { trackCoords = it },
        contentAlignment = Alignment.CenterStart
    ) {
        val trackWidth = constraints.maxWidth
        val density = LocalDensity.current
        val thumbWidthPx = with(density) { 40f.dp.toPx() }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val dampedDragAnimation = remember(animationScope, trackWidth, isLtr, valueRange) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = value(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {
                    onValueChangeUpdated(targetValue)
                },
                onDragCancelled = {},
                onDrag = { _, _ -> }
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { current ->
                    if (dampedDragAnimation.targetValue != current) {
                        dampedDragAnimation.updateValue(current)
                    }
                }
        }

        Box(Modifier.layerBackdrop(trackBackdrop)) {
            Box(
                Modifier
                    .clip(Capsule())
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(Capsule())
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) { placeable.place(0, 0) }
                    }
            )
        }

        Box(
            Modifier
                .graphicsLayer {
                    val minX = -size.width / 4f
                    val maxX = (trackWidth - size.width * 3f / 4f).coerceAtLeast(minX)
                    translationX =
                        (-size.width / 2f + trackWidth * dampedDragAnimation.progress)
                            .fastCoerceIn(minX, maxX) * if (isLtr) 1f else -1f
                }
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) { drawBackdrop() }
                        }
                    ),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )

        // 仅在滑块胶囊本体（40dp × 24dp）上挂载手势监听，轨道线条完全无手势节点；
        // 点到或划过线条轴空白处时不会命中滑块，页面上下滚动与左右翻页完全正常。
        Box(
            Modifier
                .offset {
                    val w = trackWidth.toFloat()
                    val minX = -thumbWidthPx / 4f
                    val maxX = (w - thumbWidthPx * 3f / 4f).coerceAtLeast(minX)
                    val rawOffset = (-thumbWidthPx / 2f + w * dampedDragAnimation.progress)
                        .fastCoerceIn(minX, maxX)
                    IntOffset(rawOffset.fastRoundToInt(), 0)
                }
                .size(40f.dp, 24f.dp)
                .onGloballyPositioned { thumbCoords = it }
                .pointerInput(dampedDragAnimation, trackWidth, isLtr, valueRange) {
                    awaitEachGesture {
                        val down = awaitFirstDown(
                            requireUnconsumed = false,
                            pass = PointerEventPass.Initial
                        )
                        val tc = trackCoords
                        val hc = thumbCoords
                        val downParentPos = if (tc != null && hc != null && tc.isAttached && hc.isAttached) {
                            tc.localPositionOf(hc, down.position)
                        } else {
                            down.position
                        }
                        val downParentX = downParentPos.x
                        val downParentY = downParentPos.y
                        var prevParentX = downParentX
                        val touchSlop = viewConfiguration.touchSlop
                        var axisLockedHorizontal = false
                        var abortedForVertical = false
                        var dragValue = currentValue().coerceIn(valueRange)
                        val span = valueRange.endInclusive - valueRange.start

                        dampedDragAnimation.press()

                        var pointer = down.id
                        var upEvent: PointerInputChange? = null
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.fastFirstOrNull { it.id == pointer } ?: break
                            if (change.changedToUpIgnoreConsumed()) {
                                val otherDown = event.changes.fastFirstOrNull { it.pressed }
                                if (otherDown == null) {
                                    upEvent = change
                                    break
                                } else {
                                    pointer = otherDown.id
                                    val oc = if (tc != null && hc != null && tc.isAttached && hc.isAttached) {
                                        tc.localPositionOf(hc, otherDown.position)
                                    } else {
                                        otherDown.position
                                    }
                                    prevParentX = oc.x
                                    continue
                                }
                            }
                            val curParentPos = if (tc != null && hc != null && tc.isAttached && hc.isAttached) {
                                tc.localPositionOf(hc, change.position)
                            } else {
                                change.position
                            }
                            if (!axisLockedHorizontal) {
                                val totalDx = curParentPos.x - downParentX
                                val totalDy = curParentPos.y - downParentY
                                val absDx = abs(totalDx)
                                val absDy = abs(totalDy)
                                if (absDy > touchSlop * 0.45f && absDy > absDx * 1.15f) {
                                    abortedForVertical = true
                                    break
                                }
                                if (absDx > touchSlop * 0.30f && absDx >= absDy) {
                                    axisLockedHorizontal = true
                                    change.consume()
                                    dragValue = currentValue().coerceIn(valueRange)
                                    if (trackWidth > 0) {
                                        val delta = span * (totalDx / trackWidth)
                                        dragValue = (if (isLtr) dragValue + delta else dragValue - delta).coerceIn(valueRange)
                                        onValueChangeUpdated(dragValue)
                                    }
                                    prevParentX = curParentPos.x
                                }
                            } else {
                                val dx = curParentPos.x - prevParentX
                                prevParentX = curParentPos.x
                                change.consume()
                                if (dx != 0f && trackWidth > 0) {
                                    val delta = span * (dx / trackWidth)
                                    dragValue = (if (isLtr) dragValue + delta else dragValue - delta).coerceIn(valueRange)
                                    onValueChangeUpdated(dragValue)
                                }
                            }
                        }

                        if (!abortedForVertical && upEvent != null && axisLockedHorizontal) {
                            onValueChangeUpdated(dragValue)
                        }
                        dampedDragAnimation.release()
                    }
                }
        )
    }
}
