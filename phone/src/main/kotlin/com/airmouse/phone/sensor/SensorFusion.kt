package com.airmouse.phone.sensor

/**
 * Конвейер sensor fusion: гироскоп + акселерометр → смещение курсора в пикселях.
 *
 * Объединяет три алгоритма из технического плана:
 *  1. Deadzone — отсекает шум покоящейся руки (устраняет дрейф на столе);
 *  2. EMA (low-pass) — убирает высокочастотное дрожание (jitter);
 *  3. OrientationMapper (комплементарная коррекция) — корректно маппит оси
 *     гироскопа в кадр экрана с учётом наклона телефона.
 *
 * Интегрирование ведётся от события к событию:
 *   `px = omegaSmoothed * dt * sensitivity`.
 *
 * Это относительное накопление дрейфует на больших временах, но:
 *   - на коротких отрезках точность гироскопа высокая;
 *   - накопленный дрейф пользователь сбрасывает кнопкой CALIBRATE.
 *
 * @param config настраиваемые параметры фильтрации (см. [FusionConfig])
 */
class SensorFusion(config: FusionConfig = FusionConfig()) {

    /** Параметры фильтрации. Могут меняться во время работы. */
    var config: FusionConfig = config
        set(value) {
            field = value
            emaX.alpha = value.emaAlpha
            emaY.alpha = value.emaAlpha
            deadzoneX = Deadzone(value.deadzoneRad)
            deadzoneY = Deadzone(value.deadzoneRad)
        }

    private var emaX = EmaFilter(config.emaAlpha)
    private var emaY = EmaFilter(config.emaAlpha)
    private var deadzoneX = Deadzone(config.deadzoneRad)
    private var deadzoneY = Deadzone(config.deadzoneRad)
    private val mapper = OrientationMapper()

    fun updateGravity(accel: FloatArray) {
        mapper.updateGravity(accel, config.gravityAlpha)
    }

    fun resetGravity() = mapper.reset()

    /**
     * Преобразует угловую скорость [gyro] (рад/с) за интервал [dtSec] секунд
     * в смещение курсора (dxScreen, dyScreen) в пикселях.
     *
     * @return массив из двух элементов [dxPx, dyPx]
     */
    fun processGyro(gyro: FloatArray, dtSec: Float): FloatArray {
        // 1. Перевод осей гироскопа в кадр экрана (с учётом наклона).
        val screenAxes = mapper.toScreenAxes(gyro)
        val omegaX = screenAxes[0]
        val omegaY = screenAxes[1]

        // 2. Мёртвая зона — гасим шум неподвижной руки.
        val dampedX = deadzoneX.apply(omegaX)
        val dampedY = deadzoneY.apply(omegaY)

        // 3. EMA low-pass — убираем высокочастотное дрожание.
        val smoothX = emaX.filter(dampedX)
        val smoothY = emaY.filter(dampedY)

        // 4. Интегрируем в пиксели: dθ * dt * sensitivity.
        val dxPx = smoothX * dtSec * config.sensitivityX
        val dyPx = smoothY * dtSec * config.sensitivityY
        return floatArrayOf(dxPx, dyPx)
    }

    /** Сброс внутреннего состояния фильтров (например, при калибровке). */
    fun reset() {
        emaX.reset()
        emaY.reset()
        mapper.reset()
    }
}

/**
 * Настройки sensor fusion. Значения по умолчанию подобраны для типичного
 * air-mouse хвата и могут быть изменены пользователем в SettingsActivity.
 *
 * @param emaAlpha коэффициент EMA гироскопа (0.05..0.3, меньше = сильнее сглаживание)
 * @param gravityAlpha коэффициент EMA вектора гравитации (0.05..0.15)
 * @param deadzoneRad порог мёртвой зоны гироскопа, рад/с (~0.05..0.1)
 * @param sensitivityX px на (рад/с · с) по горизонтали (~800)
 * @param sensitivityY px на (рад/с · с) по вертикали (~800)
 */
data class FusionConfig(
    var emaAlpha: Float = 0.1f,
    var gravityAlpha: Float = 0.05f,
    var deadzoneRad: Float = 0.05f,
    var sensitivityX: Float = 800f,
    var sensitivityY: Float = 800f,
)
