package com.airmouse.phone.net

import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import java.net.DatagramPacket

/**
 * Логическое соединение с выбранным ТВ.
 *
 * Хранит адрес точки назначения и инкапсулирует отправку типовых команд.
 * Также реализует измерение RTT (PING/PONG) для индикатора качества связи.
 *
 * Соединение "stateless" в том смысле, что сервер хранит состояние курсора;
 * клиент только шлёт события. Это упрощает восстановление после потери пакетов:
 * потерянный MOVE = потеря микро-движения, а не рассинхронизация координат.
 */
class Connection(private val transport: UdpTransport) {

    var device: Device? = null
        private set

    /** Подключение к найденному вручную/через discovery устройству. */
    fun connect(device: Device) {
        this.device = device
    }

    /** Подключение по введённому вручную IP (без discovery). */
    fun connect(host: String) {
        this.device = Device("", host, 0, 0)
    }

    val isConnected: Boolean get() = device != null
    val host: String? get() = device?.host

    /**
     * Проверяет реальную доступность ТВ: шлёт PING и ждёт PONG.
     * Возвращает true только если сервер реально ответил.
     *
     * Блокирующая операция — вызывать из рабочего потока, не из UI.
     * Параметр [onResult] вызывается в вызывающем потоке.
     */
    fun verifyConnect(host: String, onResult: (Boolean) -> Unit) {
        Thread {
            val socket = transport.socket()
            if (socket == null) {
                onResult(false)
                return@Thread
            }
            val stamp = System.nanoTime()
            val ok = try {
                transport.sendSync(host, Net.DEFAULT_PORT, Packet.Ping(stamp))
                socket.soTimeout = VERIFY_TIMEOUT_MS
                val buf = ByteArray(PacketCodec.MAX_PACKET_BYTES)
                val incoming = DatagramPacket(buf, buf.size)
                val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
                // Слушаем, пока не получим наш PONG или не выйдет таймаут.
                while (System.currentTimeMillis() < deadline) {
                    socket.receive(incoming)
                    val packet = PacketCodec.decode(incoming.data.copyOfRange(0, incoming.length))
                    if (packet is Packet.Pong && packet.clientNanos == stamp) {
                        onResult(true)
                        return@Thread
                    }
                    // чужой пакет — продолжаем ждать
                }
                onResult(false)
            } catch (_: Throwable) {
                onResult(false)
            }
        }.also { it.isDaemon = true }.start()
    }

    /** Отключение: сбрасывает устройство, перестаёт отправлять пакеты. */
    fun disconnect() {
        device = null
    }

    fun move(dx: Float, dy: Float) =
        send(Packet.Move(dx, dy))

    fun tap() = send(Packet.Tap)
    fun longPress() = send(Packet.LongPress)
    fun back() = send(Packet.Back)
    fun home() = send(Packet.Home)
    fun calibrate() = send(Packet.Calibrate)
    fun scroll(dx: Float, dy: Float) = send(Packet.Scroll(dx, dy))
    fun dpadUp() = send(Packet.DpadUp)
    fun dpadDown() = send(Packet.DpadDown)
    fun dpadLeft() = send(Packet.DpadLeft)
    fun dpadRight() = send(Packet.DpadRight)
    fun dpadCenter() = send(Packet.DpadCenter)

    private fun send(packet: Packet) {
        val target = device ?: return
        transport.send(target.host, Net.DEFAULT_PORT, packet)
    }

    /**
     * Измеряет RTT до сервера. Шлёт PING с текущим значением времени и слушает
     * PONG в отдельном потоке; результат (полный RTT, мс) возвращается через [onRttMs].
     *
     * Если ответа нет за [RTT_TIMEOUT_MS], возвращается -1.
     */
    fun measureRtt(onRttMs: (Long) -> Unit) {
        val target = device ?: return
        val socket = transport.socket() ?: return

        Thread {
            val stamp = System.nanoTime()
            // Синхронная отправка: PING должен уйти до того, как мы начнём слушать PONG.
            transport.sendSync(target.host, Net.DEFAULT_PORT, Packet.Ping(stamp))

            socket.soTimeout = RTT_TIMEOUT_MS
            val buf = ByteArray(PacketCodec.MAX_PACKET_BYTES)
            val incoming = DatagramPacket(buf, buf.size)
            val ok = try {
                socket.receive(incoming)
                true
            } catch (_: Throwable) {
                false
            }

            if (!ok) {
                onRttMs(-1L)
                return@Thread
            }
            val packet = PacketCodec.decode(incoming.data.copyOfRange(0, incoming.length))
            if (packet is Packet.Pong && packet.clientNanos == stamp) {
                val rtt = (System.nanoTime() - stamp) / 1_000_000
                onRttMs(rtt)
            } else {
                onRttMs(-1L)
            }
        }.also { it.isDaemon = true }.start()
    }

    private companion object {
        const val RTT_TIMEOUT_MS = 500
        const val VERIFY_TIMEOUT_MS = 1000
    }
}
