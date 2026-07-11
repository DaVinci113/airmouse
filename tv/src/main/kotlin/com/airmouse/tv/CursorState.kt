package com.airmouse.tv

import android.util.DisplayMetrics
import android.view.Display

/**
 * Текущее положение курсора на экране ТВ.
 *
 * Сервер хранит абсолютные координаты и обновляет их относительными дельтами
 * от клиента (Packet.Move). Это делает протокол stateless с точки зрения
 * клиента: потерянный UDP-пакет = потеря микро-движения, а не рассинхронизация.
 *
 * Координаты клампятся по границам экрана, чтобы курсор не "уплыл" за пределы
 * видимой области. CALIBRATE сбрасывает позицию в центр.
 */
class CursorState {

    var width: Int = 1920
        private set
    var height: Int = 1080
        private set

    var x: Float = width / 2f
        private set
    var y: Float = height / 2f
        private set

    /** Инициализация границ экрана из реального Display. */
    fun initFromDisplay(display: Display) {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        width = metrics.widthPixels
        height = metrics.heightPixels
        // Центрируем курсор при первом определении размера.
        x = width / 2f
        y = height / 2f
    }

    /** Применяет относительное смещение и возвращает новую позицию (x, y). */
    fun applyDelta(dx: Float, dy: Float): Pair<Float, Float> {
        x = (x + dx).coerceIn(0f, width.toFloat())
        y = (y + dy).coerceIn(0f, height.toFloat())
        return Pair(x, y)
    }

    /** Сброс в центр экрана (команда CALIBRATE от клиента). */
    fun recenter(): Pair<Float, Float> {
        x = width / 2f
        y = height / 2f
        return Pair(x, y)
    }

    fun current(): Pair<Float, Float> = Pair(x, y)
}
