package com.airmouse.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Эмуляция касаний через AccessibilityService.dispatchGesture (API 24+).
 *
 * Это единственный способ без Root «кликнуть» в произвольную точку экрана
 * поверх любого приложения на Android TV. dispatchGesture строит
 * [GestureDescription] из одного или нескольких [GestureDescription.StrokeDescription],
 * каждый из которых описывает траекторию (Path) и длительность.
 *
 * Варианты:
 *  - [tap]      — короткое касание в точке (ЛКМ);
 *  - [longPress]— длинное нажатие (ПКМ / контекстное меню);
 *  - [scroll]   — жест-протяжка из точки в точку (скролл списков).
 */
@RequiresApi(Build.VERSION_CODES.N)
class GestureExecutor(private val service: AccessibilityService) {

    /**
     * Короткий клик в точке ([x], [y]).
     * Длительность TAP_DURATION_MS: достаточно, чтобы система распознала тап,
     * но не слишком долго — иначе трактуется как long-press.
     */
    fun tap(x: Float, y: Float) {
        dispatch(buildPointPath(x, y), TAP_DURATION_MS) { ok ->
            logResult("tap", x, y, ok)
        }
    }

    /**
     * Длинное нажатие в точке ([x], [y]).
     * Длительность LONG_PRESS_DURATION_MS > порога long-press (~500 мс),
     * что вызывает контекстное меню в большинстве приложений.
     */
    fun longPress(x: Float, y: Float) {
        dispatch(buildPointPath(x, y), LONG_PRESS_DURATION_MS) { ok ->
            logResult("longPress", x, y, ok)
        }
    }

    /**
     * Жест прокрутки: протягивает курсор из текущей точки на ([dx], [dy]).
     * Положительная dy = вниз (скролл списка вверх).
     */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx, y + dy)
        }
        dispatch(path, SCROLL_DURATION_MS) { ok ->
            logResult("scroll", x, y, ok)
        }
    }

    private fun buildPointPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        // Микро-смещение: нулевой длины stroke некоторые прошивки игнорируют.
        lineTo(x + 0.5f, y + 0.5f)
    }

    private fun dispatch(path: Path, durationMs: Long, onResult: (Boolean) -> Unit) {
        val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) = onResult(true)
            override fun onCancelled(g: GestureDescription?) = onResult(false)
        }, null)
        if (!ok) onResult(false)
    }

    private fun logResult(tag: String, x: Float, y: Float, ok: Boolean) {
        Log.d(TAG, String.format("%s at (%.1f, %.1f): %b", tag, x, y, ok))
    }

    private companion object {
        const val TAG = "AirMouse/Gesture"
        const val TAP_DURATION_MS = 50L
        const val LONG_PRESS_DURATION_MS = 600L
        const val SCROLL_DURATION_MS = 200L
    }
}
