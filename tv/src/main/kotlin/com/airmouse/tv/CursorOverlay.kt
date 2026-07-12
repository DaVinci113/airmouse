package com.airmouse.tv

import android.content.Context
import android.graphics.PixelFormat
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView

/**
 * Рисует иконку курсора поверх всех окон Android TV.
 *
 * Использует [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] — особый
 * тип окна, доступный из AccessibilityService. Он НЕ требует разрешения
 * SYSTEM_ALERT_WINDOW.
 *
 * Окно помечено NOT_FOCUSABLE | NOT_TOUCHABLE: оно не перехватывает ввод,
 * поэтому клики проходят в нижележащие приложения (мы сами диспатчим жесты
 * через dispatchGesture в точке курсора).
 *
 * Все операции с WindowManager обязаны выполняться на main потоке.
 */
class CursorOverlay(context: Context) {

    private val windowManager =
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val imageView = ImageView(context).apply {
        setImageResource(R.drawable.cursor)
    }

    private val params = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        @Suppress("DEPRECATION")
        gravity = android.view.Gravity.TOP or android.view.Gravity.START
    }

    private var attached = false

    /** Добавляет оверлей на экран. Безопасно вызывать повторно. */
    fun show(initialX: Int, initialY: Int) {
        if (attached) {
            moveTo(initialX, initialY)
            return
        }
        params.x = initialX
        params.y = initialY
        try {
            windowManager.addView(imageView, params)
            attached = true
            Log.i(TAG, "Overlay shown at ($initialX, $initialY)")
        } catch (e: Throwable) {
            // Логируем вместо тихого проглатывания — иначе курсор "просто не работает".
            Log.e(TAG, "addView failed", e)
        }
    }

    /** Перемещает курсор в экранные координаты (x, y). */
    fun moveTo(x: Int, y: Int) {
        if (!attached) return
        params.x = x
        params.y = y
        try {
            windowManager.updateViewLayout(imageView, params)
        } catch (e: Throwable) {
            Log.w(TAG, "updateViewLayout failed: ${e.message}")
        }
    }

    /** Прячет и удаляет оверлей. */
    fun hide() {
        if (!attached) return
        try {
            windowManager.removeView(imageView)
        } catch (e: Throwable) {
            Log.w(TAG, "removeView failed: ${e.message}")
        }
        attached = false
    }

    val isShown: Boolean get() = attached

    private companion object {
        const val TAG = "AirMouse/Overlay"
    }
}
