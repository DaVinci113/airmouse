package com.airmouse.phone

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.TextUtils
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.airmouse.phone.net.Connection
import com.airmouse.phone.net.Device
import com.airmouse.phone.net.Discovery
import com.airmouse.phone.net.UdpTransport
import com.airmouse.phone.sensor.FusionConfig
import com.airmouse.phone.sensor.MotionEngine
import com.airmouse.phone.sensor.SensorFusion

/**
 * Главный экран клиента (смартфон).
 *
 * Отвечает за:
 *  - ввод IP ТВ вручную или выбор из списка discovery;
 *  - запуск sensor-fusion конвейера [MotionEngine] и отправку MOVE по сети;
 *  - кнопки управления (клик, ПКМ, BACK/HOME, скролл, калибровка);
 *  - периодическое измерение RTT для индикатора качества связи.
 *
 * Сенсоры стартуют только после подключения — иначе нет смысла гонять
 * сэмплы в пустоту и тратить батарею.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var transport: UdpTransport
    private lateinit var connection: Connection
    private lateinit var fusion: SensorFusion
    private var engine: MotionEngine? = null

    // Ссылки на UI.
    private lateinit var ipField: EditText
    private lateinit var statusLabel: TextView
    private lateinit var pingLabel: TextView
    private lateinit var devicesContainer: LinearLayout
    private lateinit var disconnectButton: Button

    private val pingChecker = PingChecker()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ipField = findViewById(R.id.ipField)
        statusLabel = findViewById(R.id.statusLabel)
        pingLabel = findViewById(R.id.pingLabel)
        devicesContainer = findViewById(R.id.devicesContainer)
        disconnectButton = findViewById(R.id.disconnectButton)

        transport = UdpTransport()
        // Биндим локальный порт, чтобы принимать ANNOUNCE и PONG.
        transport.open(bindLocalPort = 0)
        connection = Connection(transport)

        fusion = SensorFusion(loadFusionConfig())

        findViewById<Button>(R.id.connectButton).setOnClickListener {
            val ip = ipField.text.toString().trim()
            if (!isValidIp(ip)) {
                Toast.makeText(this, R.string.invalid_ip, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectTo(Device("", ip, 0, 0))
        }

        findViewById<Button>(R.id.discoverButton).setOnClickListener { runDiscovery() }

        findViewById<Button>(R.id.leftClickButton).setOnClickListener { connection.tap() }
        findViewById<Button>(R.id.rightClickButton).setOnClickListener { connection.longPress() }
        findViewById<Button>(R.id.backButton).setOnClickListener { connection.back() }
        findViewById<Button>(R.id.homeButton).setOnClickListener { connection.home() }
        findViewById<Button>(R.id.recenterButton).setOnClickListener {
            connection.calibrate()
            engine?.reset()
        }
        findViewById<Button>(R.id.scrollUpButton).setOnClickListener {
            connection.scroll(0f, SCROLL_DELTA_PX)
        }
        findViewById<Button>(R.id.scrollDownButton).setOnClickListener {
            connection.scroll(0f, -SCROLL_DELTA_PX)
        }

        // D-pad: стрелки пульта (performGlobalAction на сервере).
        findViewById<Button>(R.id.dpadUpButton).setOnClickListener { connection.dpadUp() }
        findViewById<Button>(R.id.dpadDownButton).setOnClickListener { connection.dpadDown() }
        findViewById<Button>(R.id.dpadLeftButton).setOnClickListener { connection.dpadLeft() }
        findViewById<Button>(R.id.dpadRightButton).setOnClickListener { connection.dpadRight() }
        findViewById<Button>(R.id.dpadCenterButton).setOnClickListener { connection.dpadCenter() }

        findViewById<Button>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        disconnectButton.setOnClickListener { disconnect() }
        // Кнопка «Отключить» видна только при активном подключении.
        disconnectButton.visibility = android.view.View.GONE
    }

    override fun onResume() {
        super.onResume()
        // Если пользователь сменил параметры фильтрации в настройках — применим.
        fusion.config = loadFusionConfig()
    }

    override fun onDestroy() {
        super.onDestroy()
        engine?.stop()
        pingChecker.stop()
        transport.close()
    }

    /** Запуск UDP-broadcast поиска ТВ. */
    private fun runDiscovery() {
        devicesContainer.removeAllViews()
        val hint = TextView(this).apply { text = getString(R.string.discovering) }
        devicesContainer.addView(hint)

        Discovery(transport).discover { devices ->
            runOnUiThread {
                devicesContainer.removeAllViews()
                if (devices.isEmpty()) {
                    val empty = TextView(this).apply { text = getString(R.string.discovery_empty) }
                    devicesContainer.addView(empty)
                    return@runOnUiThread
                }
                val inflater = LayoutInflater.from(this)
                for (d in devices) {
                    val row = inflater.inflate(R.layout.item_device, devicesContainer, false) as TextView
                    row.text = d.toString()
                    row.setOnClickListener { connectTo(d) }
                    devicesContainer.addView(row)
                }
            }
        }
    }

    /** Подключение к устройству и старт sensor-fusion конвейера. */
    private fun connectTo(device: Device) {
        connection.connect(device)
        statusLabel.text = getString(R.string.status_connected_format, device.toString())
        statusLabel.setTextColor(getColor(R.color.status_ok))
        disconnectButton.visibility = android.view.View.VISIBLE

        startMotion()
        pingChecker.start(connection, pingLabel)
        Toast.makeText(this, device.toString(), Toast.LENGTH_SHORT).show()
    }

    /** Отключение от ТВ: останавливает сенсоры и пинг. */
    private fun disconnect() {
        engine?.stop()
        engine = null
        pingChecker.stop()
        connection.disconnect()
        statusLabel.text = getString(R.string.status_disconnected)
        statusLabel.setTextColor(getColor(R.color.status_bad))
        pingLabel.text = getString(R.string.ping_unknown)
        disconnectButton.visibility = android.view.View.GONE
        Toast.makeText(this, R.string.status_disconnected, Toast.LENGTH_SHORT).show()
    }

    /** Создаёт/перезапускает MotionEngine. */
    private fun startMotion() {
        engine?.stop()
        val engine = MotionEngine(
            context = this,
            fusion = fusion,
            onMotion = { dx, dy -> connection.move(dx, dy) }
        )
        this.engine = engine
        if (!engine.start()) {
            Toast.makeText(this, R.string.no_gyroscope, Toast.LENGTH_LONG).show()
            statusLabel.setTextColor(getColor(R.color.status_bad))
        }
    }

    /** Читает сохранённые параметры sensor fusion из SharedPreferences. */
    private fun loadFusionConfig(): FusionConfig {
        val sp = getSharedPreferences(SettingsActivity.PREFS, Activity.MODE_PRIVATE)
        return FusionConfig(
            emaAlpha = sp.getFloat(SettingsActivity.KEY_EMA_ALPHA, 0.2f),
            gravityAlpha = sp.getFloat(SettingsActivity.KEY_GRAVITY_ALPHA, 0.1f),
            deadzoneRad = sp.getFloat(SettingsActivity.KEY_DEADZONE, 0.03f),
            sensitivityX = sp.getFloat(SettingsActivity.KEY_SENS_X, 800f),
            sensitivityY = sp.getFloat(SettingsActivity.KEY_SENS_Y, 800f),
        )
    }

    private fun isValidIp(ip: String): Boolean {
        return !TextUtils.isEmpty(ip)
    }

    /**
     * Периодически (раз в 2 с) измеряет RTT и обновляет подпись пинга на UI.
     * Помогает пользователю заметить потерю связи или высокий пинг.
     */
    private inner class PingChecker {
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())
        private val runnable = object : Runnable {
            override fun run() {
                if (connection.isConnected) {
                    connection.measureRtt { ms ->
                        runOnUiThread {
                            pingLabel.text = if (ms < 0) {
                                getString(R.string.ping_unknown)
                            } else {
                                getString(R.string.ping_format, ms)
                            }
                        }
                    }
                }
                handler.postDelayed(this, PING_INTERVAL_MS)
            }
        }

        fun start(connection: Connection, label: TextView) { handler.post(runnable) }
        fun stop() { handler.removeCallbacks(runnable) }
    }

    private companion object {
        const val SCROLL_DELTA_PX = 400f
        const val PING_INTERVAL_MS = 2000L
    }
}