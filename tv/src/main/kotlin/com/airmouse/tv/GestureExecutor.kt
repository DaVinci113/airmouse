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
 * Эмуляция жестов через AccessibilityService.dispatchGesture (API 24+).
 *
 * dispatchGesture строит [GestureDescription] из [GestureDescription.StrokeDescription],
 * который описывает траекторию (Path) и длительность.
 *
 * ВАЖНО про Android TV: TV-приложения в основном используют D-pad навигацию
 * (фокус), а не касания экрана. Поэтому:
 *  - ЛКМ (тап) реализован через performGlobalAction(DPAD_CENTER) в AccessibilityService,
 *    а не через dispatchGesture — это надёжно кликает по элементу в фокусе.
 *  - Длинное нажатие (ПКМ) и скролл идут через dispatchGesture, т.к. для них нет
 *    эквивалента в performGlobalAction. Они работают только в приложениях,
 *    поддерживающих касания; результат логируется в logcat (AirMouse/Gesture).
 *
 * Особенности реализации:
 *  - Stroke имеет небольшую ненулевую длину (1px): нулевой длины stroke
 *    некоторыми прошивками игнорируется.
 *  - Все вызовы маршалируются на main поток (dispatchGesture требует его).
 */
@RequiresApi(Build.VERSION_CODES.N)
class GestureExecutor(private val service: AccessibilityService) {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Координаты курсора, обновляемые при MOVE. Используются для longPressAtFocus
    // (для тапа используется GLOBAL_ACTION_DPAD_CENTER — надёжнее на Android TV).
    @Volatile private var focusX: Float = 0f
    @Volatile private var focusY: Float = 0f

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

    /**
     * Длинное нажатие "OK" для контекстного меню на элементе в фокусе.
     *
     * На Android TV нет performGlobalAction для долгого нажатия центра D-pad,
     * поэтому эмулируем долгое нажатие через dispatchGesture в точке курсора.
     * На части прошивок это сработает только если приложение поддерживает
     * касания (не все TV-приложения это делают). Результат логируется.
     */
    fun longPressAtFocus() {
        post {
            // Точка курсора (обновляется через MOVE); если overlay не двигался —
            // это центр экрана. Координаты фокуса D-pad недоступны без доступа
            // к дереву окон, поэтому используем позицию курсора как приближение.
            dispatch(buildPointPath(focusX, focusY), LONG_PRESS_DURATION_MS) { ok ->
                Log.d(TAG, "longPressAtFocus: $ok")
            }
        }
    }

    /** Обновляет координаты точки для longPressAtFocus (из CursorState). */
    fun updateFocus(x: Float, y: Float) {
        focusX = x
        focusY = y
    }

    /** Свайп для D-pad навигации (двигает фокус на один элемент). */
    fun dpadSwipe(dx: Float = 0f, dy: Float = 0f) {
        post {
            dispatch(buildSwipePath(focusX, focusY, dx, dy), SCROLL_DURATION_MS) { ok ->
                Log.d(TAG, String.format("dpadSwipe (%.0f,%.0f): %b", dx, dy, ok))
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

    private fun buildSwipePath(x: Float, y: Float, dx: Float, dy: Float): Path = Path().apply {
        moveTo(x, y)
        lineTo(x + dx, y + dy)
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
