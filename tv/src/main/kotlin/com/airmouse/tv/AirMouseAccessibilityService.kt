package com.airmouse.tv

import android.accessibilityservice.AccessibilityService
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import androidx.annotation.RequiresApi
import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.tv.net.DiscoveryResponder
import com.airmouse.tv.net.UdpServer

/**
 * AccessibilityService — ядро ТВ-сервера.
 *
 * Связывает воедино три компонента:
 *  1. [UdpServer] — приём пакетов от смартфона (сетевой поток);
 *  2. [CursorOverlay] — рисование курсора поверх окон (main поток);
 *  3. [GestureExecutor] — эмуляция нажатий/скролла через dispatchGesture.
 *
 * Состояние курсора хранится в [state] (абсолютные экранные координаты).
 *
 * Маршалинг потоков: пакеты приходят в сетевом потоке, а overlay и dispatchGesture
 * требуют main поток — для этого используется [mainHandler].
 *
 * Сервис регистрирует себя в [companion][instance], чтобы [MainActivity] могла
 * показывать актуальный статус (включён/выключен) без IPC.
 */
@RequiresApi(Build.VERSION_CODES.N)
class AirMouseAccessibilityService : AccessibilityService() {

    private lateinit var state: CursorState
    private lateinit var overlay: CursorOverlay
    private lateinit var gestures: GestureExecutor
    private lateinit var responder: DiscoveryResponder
    private var server: UdpServer? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this

        // Границы экрана из дефолтного дисплея.
        // Свойство AccessibilityService.display доступно только с API 34,
        // поэтому на старых устройствах используем DisplayManager напрямую.
        state = CursorState()
        try {
            val dm = getSystemService(DISPLAY_SERVICE) as? DisplayManager
            val d = dm?.getDisplay(Display.DEFAULT_DISPLAY)
            d?.let { state.initFromDisplay(it) }
        } catch (e: Throwable) {
            Log.w(TAG, "Cannot determine screen size: ${e.message}")
        }

        overlay = CursorOverlay(applicationContext)
        gestures = GestureExecutor(this)

        // Responder создаётся с лямбдой, которая дёргает server?.socket.
        // К моменту первого вызова respondAnnounce/respondPong сервер уже
        // стартован и сокет доступен — никаких дублей не нужно.
        responder = DiscoveryResponder(
            socket = { server?.socket },
            width = { state.width },
            height = { state.height },
        )

        server = UdpServer(
            onPacket = { packet, _, _ -> handlePacket(packet) },
            discoveryResponder = responder,
        )

        if (server!!.start()) {
            mainHandler.post { overlay.show(state.x.toInt(), state.y.toInt()) }
            Log.i(TAG, "Air Mouse connected, port ${Net.DEFAULT_PORT}, screen ${state.width}x${state.height}")
        } else {
            Log.e(TAG, "Failed to start UDP server")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "Air Mouse service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        server?.stop()
        server = null
        mainHandler.post { if (this::overlay.isInitialized) overlay.hide() }
        return super.onUnbind(intent)
    }

    /**
     * Маршрутизация пакетов → действия. Вызывается в сетевом потоке UdpServer,
     * поэтому всё, что трогает UI/overlay/dispatchGesture, оборачиваем в post.
     */
    private fun handlePacket(packet: Packet) {
        when (packet) {
            is Packet.Move -> {
                val (nx, ny) = state.applyDelta(packet.dx, packet.dy)
                mainHandler.post { overlay.moveTo(nx.toInt(), ny.toInt()) }
            }
            is Packet.Tap -> {
                val (x, y) = state.current()
                mainHandler.post { gestures.tap(x, y) }
            }
            is Packet.LongPress -> {
                val (x, y) = state.current()
                mainHandler.post { gestures.longPress(x, y) }
            }
            is Packet.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is Packet.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is Packet.Scroll -> {
                val (x, y) = state.current()
                mainHandler.post { gestures.scroll(x, y, packet.dx, packet.dy) }
            }
            is Packet.Calibrate -> {
                val (nx, ny) = state.recenter()
                mainHandler.post { overlay.moveTo(nx.toInt(), ny.toInt()) }
            }
            // DISCOVER/PING обрабатываются DiscoveryResponder внутри UdpServer.
            is Packet.Discover, is Packet.Ping,
            is Packet.Announce, is Packet.Pong -> Unit
        }
    }

    companion object {
        private const val TAG = "AirMouse/Service"

        @Volatile
        private var instance: AirMouseAccessibilityService? = null

        /** Текущий экземпляр сервиса или null, если служба выключена. */
        fun get(): AirMouseAccessibilityService? = instance

        /** Признак, что служба доступности включена пользователем. */
        fun isEnabled(): Boolean = instance != null
    }
}
