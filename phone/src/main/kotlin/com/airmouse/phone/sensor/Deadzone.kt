package com.airmouse.phone.sensor

import kotlin.math.abs

/**
 * Мёртвая зона — гасит микро-шум покоящегося сенсора.
 *
 * Пока рука неподвижна, гироскоп всё равно отдаёт шум ~0.01..0.05 рад/с.
 * Значения по модулю ниже [threshold] обнуляются, устраняя медленный дрейф
 * курсора, когда телефон просто лежит или держится неподвижно.
 *
 * Чтобы переход через порог не был резким (ступенькой), применяется
 * плавное сужение зоны: значения чуть выше порога слегка ослабляются.
 */
class Deadzone(val threshold: Float) {

    /** Возвращает значение с применением мёртвой зоны. */
    fun apply(value: Float): Float {
        val a = abs(value)
        if (a < threshold) return 0f
        // Плавный выход из мёртвой зоны на отрезке [threshold; threshold*1.5].
        val soft = threshold * 1.5f
        return if (a < soft) {
            val sign = if (value < 0f) -1f else 1f
            sign * (a - threshold) * (soft / (soft - threshold))
        } else {
            value
        }
    }
}
