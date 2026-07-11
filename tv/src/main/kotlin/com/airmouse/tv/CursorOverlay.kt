package com.airmouse.tv

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.View
import android.view.WindowManager
import android.widget.ImageView

/**
 * Рисует иконку курсора поверх всех окон Android TV.
 *
 * Использует [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY] — особый
 * тип окна, доступный из AccessibilityService. Он НЕ требует разрешения
 * SYSTEM_ALERT_WINDOW, что упрощает настройку для пользователя.
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
        // Точка привязки — кончик стрелки (левый верхний угол курсора).
        elevation = 0f
    }

    private val params = WindowManager.LayoutParams().apply {
        width = WindowManager.LayoutParams.WRAP_CONTENT
        height = WindowManager.LayoutParams.WRAP_CONTENT
        type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        format = PixelFormat.TRANSLUCENT
        flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        // Избегаем лишних анимаций появления.
        windowAnimations = 0
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
        runCatching { windowManager.addView(imageView, params) }
        attached = true
    }

    /** Перемещает курсор в экранные координаты (x, y). */
    fun moveTo(x: Int, y: Int) {
        if (!attached) return
        params.x = x
        params.y = y
        runCatching { windowManager.updateViewLayout(imageView, params) }
    }

    /** Прячет и удаляет оверлей. */
    fun hide() {
        if (!attached) return
        runCatching { windowManager.removeView(imageView) }
        attached = false
    }

    val isShown: Boolean get() = attached
}
