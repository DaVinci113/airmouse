package com.airmouse.phone.sensor

import kotlin.math.cos
import kotlin.math.sin

/**
 * Маппинг осей и комплементарная коррекция ориентации.
 *
 * ПРОБЛЕМА: какая ось гироскопа соответствует «влево/вправо» на экране,
 * зависит от того, как наклонён телефон в руке. Если просто взять
 * gyro[Y] → dx, gyro[X] → dy, то при наклоне телефона «нose-up»
 * направления исказятся и курсор поедет по диагонали.
 *
 * РЕШЕНИЕ: из акселерометра восстанавливаем вектор гравитации в системе
 * телефона (углы pitch/roll), а затем поворотом приводим оси гироскопа
 * в кадр экрана, прежде чем интегрировать в пиксели.
 *
 * Yaw-дрейф акселерометром не убирается (нет магнитометра), но для air-mouse
 * это не критично: движение курсора относительное, а накопленный сдвиг
 * пользователь сбрасывает кнопкой "Сброс центра" (Packet.Calibrate).
 *
 * Конкретно для типичного хвата (телефон вертикально, экран к пользователю):
 *   - поворот влево/вправо (рысканье вокруг вертикали) => ось Y гироскопа;
 *   - наклон вперёд/назад (тангаж) => ось X гироскопа.
 * Знаки подобраны так, чтобы движение было интуитивным («как мышью»).
 */
class OrientationMapper {

    // Сглаженный вектор гравитации в системе телефона (нормализованный).
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = -1f
    private var gravInit = false

    /**
     * Обновляет оценку вектора гравитации из сырых значений акселерометра.
     * Применяется EMA-сглаживание, т.к. акселерометр шумит при движении руки.
     *
     * @param accel линейное ускорение, м/с² (модуль ~9.81 в покое)
     * @param alpha коэффициент сглаживания вектора гравитации (0.05..0.15)
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
     * Поворачивает угловую скорость [gyro] (рад/с, оси телефона X/Y/Z) в
     * экранный кадр и возвращает пару (dxOmega, dyOmega) — компоненты,
     * которые дальше умножаются на dt и чувствительность.
     *
     * Возвращаемое dxOmega => движение по горизонтали экрана,
     * dyOmega => по вертикали (положительное = вниз).
     *
     * По умолчанию используется простая модель для вертикального хвата:
     *   dxOmega = -gyro[Y]
     *   dyOmega = -gyro[X]
     * При сильном наклоне телефона добавляется коррекция через roll,
     * чтобы оси "поворачивались" вслед за наклоном устройства.
     */
    fun toScreenAxes(gyro: FloatArray): FloatArray {
        require(gyro.size >= 3) { "gyro must have 3 components" }

        // Roll: поворот вокруг продольной оси (вдоль Y телефона).
        // По вектору гравитации: roll = atan2(gravX, gravZ).
        val n = norm(gravX, gravY, gravZ)
        val gx = if (n > 0.001f) gravX / n else 0f
        val gz = if (n > 0.001f) gravZ / n else -1f
        val roll = kotlin.math.atan2(gx, gz)

        // Базовое назначение осей для вертикального хвата (экран к лицу).
        // Знаки подобраны экспериментально под "пистолетный" хват.
        var dxOmega = -gyro[1]
        var dyOmega = -gyro[0]

        // Коррекция: при наклоне телефона вбок (roll) частично смешиваем оси,
        // чтобы курсор двигался в "мировой" системе, а не в системе телефона.
        val c = cos(roll)
        val s = sin(roll)
        val ndx = c * dxOmega - s * dyOmega
        val ndy = s * dxOmega + c * dyOmega
        return floatArrayOf(ndx, ndy)
    }

    private fun norm(x: Float, y: Float, z: Float): Float =
        kotlin.math.sqrt(x * x + y * y + z * z)
}
