package com.airmouse.tv

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.os.Handler
import android.os.Looper
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
 *
 * Особенности реализации:
 *  - TAP_DURATION_MS достаточно длинный (~150 мс), чтобы система гарантированно
 *    распознала тап; слишком короткие жесты (<100 мс) на части прошивок отбрасываются.
 *  - Stroke имеет небольшую ненулевую длину (1px): нулевой длины stroke
 *    некоторыми прошивками игнорируется.
 */
@RequiresApi(Build.VERSION_CODES.N)
class GestureExecutor(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Короткий клик в точке ([x], [y]). */
    fun tap(x: Float, y: Float) {
        post {
            dispatch(buildPointPath(x, y), TAP_DURATION_MS) { ok ->
                Log.d(TAG, String.format("tap at (%.1f, %.1f): %b", x, y, ok))
            }
        }
    }

    /** Длинное нажатие в точке ([x], [y]) — контекстное меню. */
    fun longPress(x: Float, y: Float) {
        post {
            dispatch(buildPointPath(x, y), LONG_PRESS_DURATION_MS) { ok ->
                Log.d(TAG, String.format("longPress at (%.1f, %.1f): %b", x, y, ok))
            }
        }
    }

    /** Жест прокрутки на ([dx], [dy]) от текущей точки. */
    fun scroll(x: Float, y: Float, dx: Float, dy: Float) {
        post {
            val path = Path().apply {
                moveTo(x, y)
                lineTo(x + dx, y + dy)
            }
            dispatch(path, SCROLL_DURATION_MS) { ok ->
                Log.d(TAG, String.format("scroll (%.1f, %.1f)+(%.1f, %.1f): %b", x, y, dx, dy, ok))
            }
        }
    }

    private fun post(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action()
        } else {
            mainHandler.post(action)
        }
    }

    private fun buildPointPath(x: Float, y: Float): Path = Path().apply {
        moveTo(x, y)
        // Ненулевая длина 1px: нулевой stroke некоторые прошивки отбрасывают.
        lineTo(x + 1f, y + 1f)
    }

    private fun dispatch(path: Path, durationMs: Long, onResult: (Boolean) -> Unit) {
        try {
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs)
            val gesture = GestureDescription.Builder().addStroke(stroke).build()
            val ok = service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) = onResult(true)
                override fun onCancelled(g: GestureDescription?) = onResult(false)
            }, null)
            if (!ok) {
                Log.w(TAG, "dispatchGesture returned false (rejected)")
                onResult(false)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "dispatchGesture threw", e)
            onResult(false)
        }
    }

    private companion object {
        const val TAG = "AirMouse/Gesture"
        const val TAP_DURATION_MS = 150L
        const val LONG_PRESS_DURATION_MS = 600L
        const val SCROLL_DURATION_MS = 250L
    }
}
