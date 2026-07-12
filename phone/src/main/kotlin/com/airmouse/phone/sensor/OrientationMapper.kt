package com.airmouse.phone.sensor

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Маппинг осей гироскопа в экранный кадр.
 *
 * ОСИ ANDROID (портретный хват, экран к лицу):
 *   - gyro[0] (X): вращение вокруг горизонтальной оси → наклон верх/низ телефона
 *   - gyro[1] (Y): вращение вокруг вертикальной оси → поворот влево/вправо
 *   - gyro[2] (Z): вращение вокруг оси, перпендикулярной экрану → крен
 *
 * МАППИНГ В КУРСОР:
 *   - поворот телефона влево/вправо (рысканье, gyro[1]) → курсор по горизонтали
 *   - наклон телефона вперёд/назад (тангаж, gyro[0]) → курсор по вертикали
 *
 * Знаки подобраны так, чтобы движение было интуитивным ("как мышью"):
 *   - повернуть телефон вправо → курсор вправо
 *   - наклонить верх телефона к себе → курсор вниз
 *
 * ROLL-КОРРЕКЦИЯ: если телефон наклонён вбок (крен), оси X и Y частично
 * смешиваются. Вектор гравитации из акселерометра даёт угол roll, и мы
 * поворачиваем оси обратно, чтобы курсор двигался в "мировой" системе
 * координат, а не в системе наклонённого телефона.
 *
 * ВАЖНО: полная 3D-коррекция (с pitch) здесь намеренно НЕ применяется —
 * она нестабильна из-за шума акселерометра и делает горизонталь
 * непредсказуемо слабой/сильной в зависимости от наклона. Чувствительность
 * по осям пользователь настраивает отдельными ползунками в SettingsActivity.
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

        // Базовый маппинг для вертикального хвата.
        // Знаки проверены эмпирически для большинства телефонов Android.
        var dxOmega = -gyro[1]  // рысканье → горизонталь
        var dyOmega = -gyro[0]  // тангаж → вертикаль

        // Roll (крен): наклон телефона вокруг продольной оси.
        val n = sqrt(gravX * gravX + gravZ * gravZ)
        if (n < 0.1f) {
            // Гравитация ещё не определена — отдаём базовый маппинг.
            return floatArrayOf(dxOmega, dyOmega)
        }
        val roll = atan2(gravX, sqrt(gravY * gravY + gravZ * gravZ))

        // Коррекция на roll: при наклоне телефона вбок оси смешиваются.
        val c = cos(roll)
        val s = sin(roll)
        val ndx = c * dxOmega - s * dyOmega
        val ndy = s * dxOmega + c * dyOmega
        return floatArrayOf(ndx, ndy)
    }
}
