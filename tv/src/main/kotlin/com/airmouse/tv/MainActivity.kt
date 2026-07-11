package com.airmouse.tv

import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airmouse.proto.Net

/**
 * Главный экран ТВ-сервера.
 *
 * Это экран настройки/диагностики, а не рабочая поверхность — собственно приём
 * команд выполняется в [AirMouseAccessibilityService], которая работает в фоне.
 *
 * Здесь пользователь:
 *  - видит статус службы доступности (включена/нет) и может её включить;
 *  - узнаёт свой IP-адрес и порт, чтобы ввести на смартфоне вручную;
 *  - читает краткую инструкцию.
 *
 * Статус службы периодически опрашивается (AccessibilityService не даёт
 * подписки на изменения), поэтому экран обновляется раз в секунду.
 */
class MainActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            updateServiceStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private lateinit var serviceStatusLabel: TextView
    private lateinit var ipLabel: TextView
    private lateinit var listeningLabel: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        serviceStatusLabel = findViewById(R.id.serviceStatusLabel)
        ipLabel = findViewById(R.id.ipLabel)
        listeningLabel = findViewById(R.id.listeningLabel)

        findViewById<Button>(R.id.enableServiceButton).setOnClickListener {
            // Открывает системные настройки доступности (пользователь включает службу вручную).
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        showNetworkInfo()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refresh)
    }

    /** Обновляет подпись статуса службы доступности. */
    private fun updateServiceStatus() {
        if (AirMouseAccessibilityService.isEnabled()) {
            serviceStatusLabel.text = getString(R.string.service_on)
            serviceStatusLabel.setTextColor(getColor(R.color.status_ok))
        } else {
            serviceStatusLabel.text = getString(R.string.service_off)
            serviceStatusLabel.setTextColor(getColor(R.color.status_bad))
        }
    }

    /** Показывает локальный IP-адрес (для ручного ввода на смартфоне). */
    private fun showNetworkInfo() {
        val ip = getLocalIpAddress()
        ipLabel.text = if (ip == null) {
            getString(R.string.ip_unknown)
        } else {
            getString(R.string.ip_format, ip, Net.DEFAULT_PORT)
        }
        listeningLabel.text = getString(R.string.listening, Net.DEFAULT_PORT)
    }

    /**
     * IPv4-адрес устройства из WifiManager. На ТВ с Ethernet это может вернуть
     * 0.0.0.0; в таком случае пользователь увидит "определяется…" и сможет
     * узнать IP из системных настроек сети.
     */
    @Suppress("DEPRECATION")
    private fun getLocalIpAddress(): String? {
        return try {
            val wifi = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager
            val ipInt = wifi?.connectionInfo?.ipAddress ?: 0
            if (ipInt == 0) null
            else String.format(
                "%d.%d.%d.%d",
                ipInt and 0xff,
                (ipInt shr 8) and 0xff,
                (ipInt shr 16) and 0xff,
                (ipInt shr 24) and 0xff,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
