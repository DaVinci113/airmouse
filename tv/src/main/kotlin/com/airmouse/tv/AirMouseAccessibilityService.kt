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
        try {
            initAll()
        } catch (e: Throwable) {
            Log.e(TAG, "onServiceConnected failed", e)
            // Очищаем состояние, чтобы UI не показывал "Включена"
            isUdpRunning = false
            instance = null
            server?.stop()
            server = null
            try { if (this::overlay.isInitialized) overlay.hide() } catch (_: Throwable) {}
        }
    }

    private fun initAll() {
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

        // Контекст сервиса (this) — не applicationContext: для TYPE_ACCESSIBILITY_OVERLAY
        // нужен WindowManager, привязанный к дисплею сервиса, иначе addView молча
        // не отрисует окно на некоторых прошивках Android TV.
        overlay = CursorOverlay(this)
        gestures = GestureExecutor(this)
        gestures.updateFocus(state.x, state.y)

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
            // instance ставим ТОЛЬКО после успешного старта UDP —
            // иначе UI будет врать "Включена" при мёртвом сервере.
            instance = this
            isUdpRunning = true
            mainHandler.post { overlay.show(state.x.toInt(), state.y.toInt()) }
            Log.i(TAG, "Air Mouse connected, port ${Net.DEFAULT_PORT}, screen ${state.width}x${state.height}")
        } else {
            Log.e(TAG, "Failed to start UDP server")
            server?.stop()
            server = null
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        Log.w(TAG, "Air Mouse service interrupted")
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        isUdpRunning = false
        instance = null
        server?.stop()
        server = null
        mainHandler.post { if (this::overlay.isInitialized) overlay.hide() }
        return super.onUnbind(intent)
    }

    /**
     * Маршрутизация пакетов → действия. Вызывается в сетевом потоке UdpServer.
     * Overlay-операции и dispatchGesture маршалируются на main поток
     * (GestureExecutor делает это сам; для overlay используем mainHandler).
     */
    private fun handlePacket(packet: Packet) {
        try {
            when (packet) {
            is Packet.Move -> {
                val (nx, ny) = state.applyDelta(packet.dx, packet.dy)
                gestures.updateFocus(nx, ny)
                mainHandler.post { overlay.moveTo(nx.toInt(), ny.toInt()) }
            }
            // ЛКМ = эмуляция тапа по координатам курсора.
            // performGlobalAction(DPAD_CENTER) существует только с API 34 —
            // на большинстве Android TV (API 30-33) это крашит службу.
            is Packet.Tap -> {
                val (x, y) = state.current()
                gestures.tap(x, y)
            }
            // ПКМ = длинное нажатие по координатам курсора.
            is Packet.LongPress -> gestures.longPress(state.x, state.y)
            is Packet.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            is Packet.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            is Packet.Scroll -> {
                val (x, y) = state.current()
                gestures.scroll(x, y, packet.dx, packet.dy)
            }
            is Packet.Calibrate -> {
                val (nx, ny) = state.recenter()
                gestures.updateFocus(nx, ny)
                mainHandler.post { overlay.moveTo(nx.toInt(), ny.toInt()) }
            }
            // D-pad: эмуляция стрелок пульта.
            // GLOBAL_ACTION_DPAD_* существует только с API 34 (Android 14).
            // Для совместимости с Android 10-13 используем KeyCode-ввод
            // через AccessibilityService.performGlobalAction с BACK/HOME,
            // а стрелки — через dispatchGesture (свайп в нужную сторону).
            is Packet.DpadUp -> gestures.dpadSwipe(dy = -DPAD_SWIPE_PX)
            is Packet.DpadDown -> gestures.dpadSwipe(dy = DPAD_SWIPE_PX)
            is Packet.DpadLeft -> gestures.dpadSwipe(dx = -DPAD_SWIPE_PX)
            is Packet.DpadRight -> gestures.dpadSwipe(dx = DPAD_SWIPE_PX)
            is Packet.DpadCenter -> gestures.tap(state.x, state.y)
            is Packet.Discover, is Packet.Ping,
            is Packet.Announce, is Packet.Pong -> Unit
            }
        } catch (e: Throwable) {
            Log.e(TAG, "handlePacket error", e)
        }
    }

    companion object {
        private const val TAG = "AirMouse/Service"
        /** Длина свайпа для D-pad стрелок в пикселях. */
        private const val DPAD_SWIPE_PX = 100f

        @Volatile
        private var instance: AirMouseAccessibilityService? = null
        @Volatile
        private var isUdpRunning = false

        /** Текущий экземпляр сервиса или null, если служба выключена. */
        fun get(): AirMouseAccessibilityService? = instance

        /** Признак, что служба доступности включена пользователем. */
        fun isEnabled(): Boolean = instance != null

        /** Признак, что UDP-сервер реально принимает пакеты. */
        fun isServerRunning(): Boolean = isUdpRunning
    }
}
