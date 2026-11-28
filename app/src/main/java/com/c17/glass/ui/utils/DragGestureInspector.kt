package com.c17.glass.ui.utils

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.util.fastFirstOrNull
import kotlin.math.abs

/** 统一指针拖拽手势识别器，支持横向轴锁定以兼顾滑动控件与外层页面滚动。 */
suspend fun PointerInputScope.inspectDragGestures(
    consumeDrag: Boolean = false,
    lockHorizontalAxis: Boolean = false,
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val down = awaitFirstDown(
            requireUnconsumed = false,
            pass = PointerEventPass.Initial,
        )

        onDragStart(down)
        if (!lockHorizontalAxis) {
            onDrag(down, Offset.Zero)
            val upEvent = drag(
                pointerId = down.id,
                onDrag = { change ->
                    val delta = change.positionChangeIgnoreConsumed()
                    // 控件自身的拖动必须在这里消费掉，否则同一手势会继续冒泡给
                    // HorizontalPager / LazyColumn，表现为拖滑杆时页面跟着翻页。
                    if (consumeDrag) change.consume()
                    onDrag(change, delta)
                    true
                },
            )
            if (upEvent != null) onDragEnd(upEvent) else onDragCancel()
            return@awaitEachGesture
        }

        val touchSlop = viewConfiguration.touchSlop
        val downPos = down.position
        var axisLockedHorizontal = false
        var abortedForVertical = false
        val upEvent = drag(
            pointerId = down.id,
            onDrag = { change ->
                if (!axisLockedHorizontal) {
                    val totalDx = change.position.x - downPos.x
                    val totalDy = change.position.y - downPos.y
                    val absDx = abs(totalDx)
                    val absDy = abs(totalDy)
                    // 纵向滑动主导时主动让出事件，避免页面上下滚动被滑块抢断。
                    if (absDy > touchSlop * 0.45f && absDy > absDx * 1.15f) {
                        abortedForVertical = true
                        return@drag false
                    }
                    if (absDx > touchSlop * 0.30f && absDx >= absDy) {
                        axisLockedHorizontal = true
                        if (consumeDrag) change.consume()
                        onDrag(change, Offset(totalDx, totalDy))
                    }
                    return@drag true
                }
                val delta = change.positionChangeIgnoreConsumed()
                if (consumeDrag) change.consume()
                onDrag(change, delta)
                true
            },
        )
        if (abortedForVertical || upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    onDrag: (PointerInputChange) -> Boolean
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) {
        return null
    }
    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (change.changedToUpIgnoreConsumed()) {
            return change
        }
        if (!onDrag(change)) {
            return null
        }
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            } else {
                pointer = otherDown.id
            }
        } else {
            val hasDragged = dragEvent.previousPosition != dragEvent.position
            if (hasDragged) {
                return dragEvent
            }
        }
    }
}
