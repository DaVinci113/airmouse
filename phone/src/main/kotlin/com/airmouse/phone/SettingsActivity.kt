package com.airmouse.phone

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Экран настройки параметров sensor fusion.
 *
 * Все параметры хранятся в SharedPreferences и читаются [MainActivity] при
 * onResume. SeekBar'ы маппят удобный для пользователя диапазон во внутренние
 * единицы (px на рад/с для чувствительности, доля для α и т.д.).
 *
 * Это намеренно простые "сырые" настройки без PreferenceFragment, чтобы
 * сохранялась полная прозрачность того, что именно меняется.
 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        val sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        bindSensitivity(R.id.sensXBar, R.id.sensXValue, R.string.sens_format,
            KEY_SENS_X, DEFAULT_SENS, sp)
        bindSensitivity(R.id.sensYBar, R.id.sensYValue, R.string.sens_format,
            KEY_SENS_Y, DEFAULT_SENS, sp)
        bindSmoothing(R.id.smoothingBar, R.id.smoothingValue, sp)
        bindDeadzone(R.id.deadzoneBar, R.id.deadzoneValue, sp)

        findViewById<Button>(R.id.resetDefaultsButton).setOnClickListener {
            sp.edit()
                .putFloat(KEY_SENS_X, DEFAULT_SENS)
                .putFloat(KEY_SENS_Y, DEFAULT_SENS)
                .putFloat(KEY_EMA_ALPHA, DEFAULT_EMA_ALPHA)
                .putFloat(KEY_GRAVITY_ALPHA, DEFAULT_GRAVITY_ALPHA)
                .putFloat(KEY_DEADZONE, DEFAULT_DEADZONE)
                .apply()
            recreate()
        }
    }

    /**
     * Чувствительность: SeekBar 0..200 → 100..2100 px/(рад/с) с шагом 10.
     */
    private fun bindSensitivity(
        barId: Int, valueId: Int, fmtId: Int, key: String, default: Float,
        sp: android.content.SharedPreferences,
    ) {
        val bar = findViewById<SeekBar>(barId)
        val value = findViewById<TextView>(valueId)
        val stored = sp.getFloat(key, default)
        bar.progress = ((stored - 100f) / 10f).toInt().coerceIn(0, 200)
        value.text = getString(fmtId, (bar.progress * 10 + 100))
        bar.setOnSeekBarChangeListener(simpleListener {
            val px = bar.progress * 10 + 100
            value.text = getString(fmtId, px)
            sp.edit().putFloat(key, px.toFloat()).apply()
        })
    }

    /**
     * Сглаживание: SeekBar 0..40 → α = 0.5 - progress*0.01 (т.е. 0.50..0.10).
     * Большее значение прогресса = больше сглаживания (меньше α).
     */
    private fun bindSmoothing(
        barId: Int, valueId: Int,
        sp: android.content.SharedPreferences,
    ) {
        val bar = findViewById<SeekBar>(barId)
        val value = findViewById<TextView>(valueId)
        val storedAlpha = sp.getFloat(KEY_EMA_ALPHA, DEFAULT_EMA_ALPHA)
        bar.progress = ((0.5f - storedAlpha) / 0.01f).toInt().coerceIn(0, 40)
        val alpha = 0.5f - bar.progress * 0.01f
        value.text = getString(R.string.smoothing_format, alpha)
        bar.setOnSeekBarChangeListener(simpleListener {
            val a = 0.5f - bar.progress * 0.01f
            value.text = getString(R.string.smoothing_format, a)
            sp.edit().putFloat(KEY_EMA_ALPHA, a).apply()
        })
    }

    /**
     * Мёртвая зона: SeekBar 0..50 → 0.00..0.05 рад/с с шагом 0.01.
     */
    private fun bindDeadzone(
        barId: Int, valueId: Int,
        sp: android.content.SharedPreferences,
    ) {
        val bar = findViewById<SeekBar>(barId)
        val value = findViewById<TextView>(valueId)
        val stored = sp.getFloat(KEY_DEADZONE, DEFAULT_DEADZONE)
        bar.progress = (stored / 0.001f).toInt().coerceIn(0, 50)
        val dz = bar.progress * 0.001f
        value.text = getString(R.string.deadzone_format, dz)
        bar.setOnSeekBarChangeListener(simpleListener {
            val d = bar.progress * 0.001f
            value.text = getString(R.string.deadzone_format, d)
            sp.edit().putFloat(KEY_DEADZONE, d).apply()
        })
    }

    private fun simpleListener(onChange: () -> Unit) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onChange()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
    }

    companion object {
        const val PREFS = "airmouse_settings"
        const val KEY_SENS_X = "sens_x"
        const val KEY_SENS_Y = "sens_y"
        const val KEY_EMA_ALPHA = "ema_alpha"
        const val KEY_GRAVITY_ALPHA = "gravity_alpha"
        const val KEY_DEADZONE = "deadzone"

        const val DEFAULT_SENS = 800f
        const val DEFAULT_EMA_ALPHA = 0.1f
        const val DEFAULT_GRAVITY_ALPHA = 0.05f
        const val DEFAULT_DEADZONE = 0.05f
    }
}
