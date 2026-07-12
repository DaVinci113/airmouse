package com.airmouse.tv

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.airmouse.proto.Net
import java.net.InetAddress
import java.net.NetworkInterface

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
            openAccessibilitySettings()
        }

        showNetworkInfo()
    }

    /**
     * Открывает системные настройки доступности.
     *
     * На многих Android TV ACTION_ACCESSIBILITY_SETTINGS не открывается без
     * FLAG_ACTIVITY_NEW_TASK, а иногда ActivityNotFoundException падает молча.
     * Поэтому: ставим флаг, а при ошибке пробуем fallback на общие настройки,
     * и только если и они недоступны — показываем Toast.
     */
    private fun openAccessibilitySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in intents) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (_: android.content.ActivityNotFoundException) {
                // пробуем следующий вариант
            }
        }
        android.widget.Toast.makeText(
            this,
            "Не удалось открыть настройки доступности. Откройте: Настройки → Система → Специальные возможности → Air Mouse",
            android.widget.Toast.LENGTH_LONG,
        ).show()
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
        when {
            !AirMouseAccessibilityService.isEnabled() -> {
                serviceStatusLabel.text = getString(R.string.service_off)
                serviceStatusLabel.setTextColor(getColor(R.color.status_bad))
            }
            AirMouseAccessibilityService.isServerRunning() -> {
                serviceStatusLabel.text = getString(R.string.service_on)
                serviceStatusLabel.setTextColor(getColor(R.color.status_ok))
            }
            else -> {
                // Служба включена, но UDP мёртв (порт занят/краш при старте).
                serviceStatusLabel.text = getString(R.string.service_on_udp_dead, Net.DEFAULT_PORT)
                serviceStatusLabel.setTextColor(getColor(R.color.status_bad))
            }
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
     * Локальный IPv4-адрес устройства через NetworkInterface.
     * Работает и для Wi-Fi, и для Ethernet (который WifiManager не видит).
     * Возвращает первый ненулевой IPv4-адрес, исключая loopback.
     */
    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces?.hasMoreElements() == true) {
                val iface = interfaces.nextElement()
                // Пропускаем loopback и отключённые интерфейсы.
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    // Берём только IPv4 и не loopback.
                    if (addr is InetAddress && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        // IPv6-адреса содержат ':', пропускаем.
                        if (host.contains(":")) continue
                        return host
                    }
                }
            }
            null
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val REFRESH_MS = 1000L
    }
}
