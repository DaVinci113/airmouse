package com.airmouse.phone.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.max

/**
 * Связывает SensorManager с [SensorFusion] и периодически отдаёт накопленное
 * смещение курсора через [onMotion].
 *
 * Поток данных:
 *   SensorManager (GAME) → SensorEventListener → SensorFusion → аккумулятор дельт
 *   → каждые ~16.6 мс (60 Гц) коллбэк [onMotion] с накопленным dx/dy.
 *
 * Throttle на 60 Гц вместо отправки каждого сенсорного события:
 *  - сенсоры GAME/FASTEST могут выдавать 200..500 Гц, что перегрузило бы сеть;
 *  - 60 Гц = плавное движение и комфортный пинг.
 *
 * [dt] считается по eventTime датчика (наносекунды) — это точнее, чем wall-clock,
 * и устойчиво к jitter'у планировщика потоков.
 */
class MotionEngine(
    context: Context,
    private val fusion: SensorFusion,
    private val onMotion: (dx: Float, dy: Float) -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var lastGyroTimeNanos: Long = -1L

    // Аккумулятор дельт между отправками коллбэка.
    private var pendingDx = 0f
    private var pendingDy = 0f

    // Throttle отправки: не чаще, чем раз в SEND_INTERVAL_NS.
    private var lastSendUptimeMs: Long = 0L

    private var running = false

    /** Минимальный интервал между коллбэками onMotion, мс (~60 Гц). */
    private val sendIntervalMs: Long = (1000 / MAX_SEND_HZ).toLong()

    /**
     * Запуск подписки на сенсоры. Если гироскоп отсутствует — вернёт false.
     * У вызывающего кода (UI) должна быть возможность показать ошибку.
     */
    fun start(): Boolean {
        if (running) return true
        if (gyro == null) return false

        // SENSOR_DELAY_GAME = ~50 Гц сэмплинга (хорошее соотношение задержки/батареи).
        // Если нужны меньшие задержки — SENSOR_DELAY_FASTEST, но ценой расхода батареи.
        gyro?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }

        lastGyroTimeNanos = -1L
        pendingDx = 0f
        pendingDy = 0f
        lastSendUptimeMs = 0L
        running = true
        return true
    }

    fun stop() {
        if (!running) return
        running = false
        sensorManager.unregisterListener(this)
    }

    /** Сброс внутреннего состояния фильтров (используется при калибровке). */
    fun reset() {
        fusion.reset()
        pendingDx = 0f
        pendingDy = 0f
        lastGyroTimeNanos = -1L
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                // Кормим акселерометр в mapper для оценки наклона (комплементарная коррекция).
                fusion.updateGravity(event.values)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val nowNanos = event.timestamp
                val dtSec: Float = if (lastGyroTimeNanos > 0L) {
                    // event.timestamp — наносекунды относительные, разность корректна.
                    max((nowNanos - lastGyroTimeNanos) / 1e9f, 0f)
                } else {
                    // Первое событие: dt неизвестен, пропускаем (точка отсчёта).
                    lastGyroTimeNanos = nowNanos
                    return
                }
                lastGyroTimeNanos = nowNanos

                // Защита от аномально больших dt (например, после паузы GC/сна).
                if (dtSec > 0.1f) return

                val d = fusion.processGyro(event.values, dtSec)
                pendingDx += d[0]
                pendingDy += d[1]
                flushIfNeeded()
            }
        }
    }

    /** Отдаёт накопленные дельты, если прошло достаточно времени с прошлой отправки. */
    private fun flushIfNeeded() {
        val now = SystemClock.uptimeMillis()
        if (now - lastSendUptimeMs < sendIntervalMs) return
        lastSendUptimeMs = now
        if (pendingDx == 0f && pendingDy == 0f) return
        val dx = pendingDx
        val dy = pendingDy
        pendingDx = 0f
        pendingDy = 0f
        onMotion(dx, dy)
    }

    private companion object {
        const val MAX_SEND_HZ = 60
    }
}
