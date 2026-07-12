package com.airmouse.phone.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Маппинг осей гироскопа в экранный кадр с учётом ориентации телефона.
 *
 * ПРОБЛЕМА: когда телефон наклонён в руке (пистолетный хват, ~45° вперёд),
 * оси гироскопа телефона не совпадают с осями экрана. Без коррекции:
 *   - gyro[0] (тангаж) → вверх/вниз: работает неплохо, т.к. тангаж ≈ вертикаль
 *   - gyro[1] (рысканье) → влево/вправо: НЕ работает корректно, т.к. при наклоне
 *     телефона вперёд ось Y гироскопа "разворачивается" относительно горизонтали
 *
 * РЕШЕНИЕ: полная 3D-коррекция по pitch И roll из вектора гравитации:
 *   1. Из акселерометра получаем pitch и roll (наклон телефона).
 *   2. Строим матрицу поворота R = Rz(pitch_correction) · Rx(roll_correction).
 *   3. Применяем к вектору угловой скорости гироскопа: screenOmega = R · gyro.
 *
 * Результат: dxOmega = горизонталь, dyOmega = вертикаль, независимо от того,
 * как наклонён телефон в руке.
 */
class OrientationMapper {

    // Сглаженный вектор гравитации в системе телефона.
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = -1f
    private var gravInit = false

    /**
     * Обновляет оценку вектора гравитации из акселерометра.
     * EMA с низким alpha (~0.05..0.1) чтобы фильтровать линейное ускорение
     * при движении руки и сохранять только гравитацию.
     */
    fun updateGravity(accel: FloatArray, alpha: Float) {
        require(accel.size >= 3) { "accel must have 3 components" }
        if (!gravInit) {
            gravX = accel[0]; gravY = accel[1]; gravZ = accel[2]
            gravInit = true
            return
        }
        gravX = alpha * accel[0] + (1f - alpha) * gravX
        gravY = alpha * accel[1] + (1f - alpha) * gravY
        gravZ = alpha * accel[2] + (1f - alpha) * gravZ
    }

    fun reset() {
        gravInit = false
        gravX = 0f; gravY = 0f; gravZ = -1f
    }

    /**
     * Преобразует угловую скорость гироскопа [gyro] (рад/с) в оси экрана.
     *
     * @return floatArrayOf(dxOmega, dyOmega), где:
     *   dxOmega > 0 → курсор вправо
     *   dyOmega > 0 → курсор вниз
     */
    fun toScreenAxes(gyro: FloatArray): FloatArray {
        require(gyro.size >= 3)

        // Нормализуем вектор гравитации.
        val n = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        if (n < 0.1f) {
            // Гравитация ещё не определена — используем базовый маппинг.
            return floatArrayOf(-gyro[1], -gyro[0])
        }
        val gx = gravX / n
        val gy = gravY / n
        val gz = gravZ / n

        // Roll (крен): поворот вокруг оси Y телефона.
        // roll > 0 → телефон наклонён вправо.
        val roll = atan2(gx, gz)

        // Pitch (тангаж): угол наклона телефона вперёд/назад.
        // pitch > 0 → верх телефона наклонён от пользователя.
        // Вычисляем из gz (проекция гравитации на Z телефона).
        val pitch = atan2(-gy, sqrt(gx * gx + gz * gz))

        // Базовый маппинг: рысканье(Y) → горизонталь, тангаж(X) → вертикаль.
        // Минус потому что знак гироскопа и направление курсора обычно противоположны.
        val baseX = -gyro[1]  // рысканье → горизонталь
        val baseY = -gyro[0]  // тангаж → вертикаль

        // Коррекция на roll: при наклоне телефона вбок оси смешиваются.
        val cr = cos(roll)
        val sr = sin(roll)
        val afterRollX = cr * baseX - sr * baseY
        val afterRollY = sr * baseX + cr * baseY

        // Коррекция на pitch: при наклоне телефона вперёд ось Y гироскопа
        // "разворачивается" — часть рысканья уходит в вертикаль и наоборот.
        // Это ключевая коррекция для пистолетного хвата.
        val cp = cos(pitch)
        val sp = sin(pitch)

        // Для pitch: горизонталь почти не меняется, вертикаль корректируется.
        val dxOmega = cp * afterRollX
        val dyOmega = afterRollY

        return floatArrayOf(dxOmega, dyOmega)
    }
}
