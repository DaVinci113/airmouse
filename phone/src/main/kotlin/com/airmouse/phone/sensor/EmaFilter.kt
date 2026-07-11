package com.airmouse.phone.sensor

/**
 * Экспоненциальное скользящее среднее (EMA) — низкочастотный фильтр.
 *
 * Устраняет высокочастотное дрожание (jitter) сенсора:
 * `smoothed = alpha * raw + (1 - alpha) * previous`.
 *
 * Малый alpha => сильнее сглаживание (и больше задержка).
 * Типичное значение для гироскопа air-mouse: 0.1..0.3.
 *
 * @param alpha вес нового значения, диапазон (0; 1].
 */
class EmaFilter(var alpha: Float) {
    private var value: Float = 0f
    private var initialized = false

    /** Применяет фильтр к новому [raw] отсчёту и возвращает сглаженное значение. */
    fun filter(raw: Float): Float {
        value = if (!initialized) {
            initialized = true
            raw
        } else {
            alpha * raw + (1f - alpha) * value
        }
        return value
    }

    /** Сбрасывает фильтр (следующий отсчёт станет новым начальным значением). */
    fun reset() {
        initialized = false
        value = 0f
    }
}
