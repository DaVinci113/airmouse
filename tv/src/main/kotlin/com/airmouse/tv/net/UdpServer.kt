package com.airmouse.tv.net

import android.util.Log
import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.proto.PacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.StandardSocketOptions

/**
 * UDP-сервер ТВ: слушает датаграммы от смартфона и передаёт декодированные
 * [Packet]'ы в [onPacket]. Также отвечает на служебные пакеты DISCOVER/PING.
 *
 * Запускается в отдельном потоке; цикл приёма крутится до [stop].
 * Обработка пакета (особенно dispatchGesture/overlay) должна маршалиться
 * на main поток вызывающим кодом — здесь только декодирование и маршрутизация.
 *
 * [socket] — единственный DatagramSocket на порту 5005; им же пользуется
 * [DiscoveryResponder] для отправки ответов, чтобы не открывать второй сокет.
 *
 * @param onPacket обработчик пакета (вызывается в сетевом потоке!)
 * @param discoveryResponder отвечает на DISCOVER и PING (может быть null)
 */
class UdpServer(
    private val onPacket: (Packet, InetAddress, Int) -> Unit,
    private val discoveryResponder: DiscoveryResponder? = null,
) {

    /** Сокет приёма/отправки (порт 5005). null, пока сервер не запущен. */
    var socket: DatagramSocket? = null
        private set

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    private var thread: Thread? = null

    /** Запуск приёмника. Возвращает false, если не удалось открыть сокет. */
    fun start(): Boolean {
        if (running.get()) return true
        return try {
            val s = DatagramSocket(null)
            // SO_REUSEADDR: позволяет занять порт, даже если предыдущий сокет
            // ещё в TIME_WAIT (после перезапуска службы). Без этого start()
            // может вернуть false, а статус "включён" будет ложно-зелёным.
            try { s.setOption(StandardSocketOptions.SO_REUSEADDR, true) } catch (_: Throwable) {}
            s.reuseAddress = true
            s.bind(java.net.InetSocketAddress(Net.DEFAULT_PORT))
            s.soTimeout = ACCEPT_TIMEOUT_MS
            socket = s
            running.set(true)
            thread = Thread { receiveLoop() }.also { it.isDaemon = true; it.start() }
            Log.i(TAG, "UDP listening on port ${Net.DEFAULT_PORT}")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to bind UDP port ${Net.DEFAULT_PORT}", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        socket?.runCatching { close() }
        socket = null
        thread?.interrupt()
    }

    private fun receiveLoop() {
        val buf = ByteArray(PacketCodec.MAX_PACKET_BYTES * 2)
        while (running.get()) {
            val incoming = DatagramPacket(buf, buf.size)
            val received = try {
                socket?.receive(incoming)
                true
            } catch (_: java.net.SocketTimeoutException) {
                continue
            } catch (e: Throwable) {
                if (running.get()) Log.w(TAG, "receive error: ${e.message}")
                continue
            }
            if (!received) continue

            val packet = PacketCodec.decode(incoming.data.copyOfRange(0, incoming.length))
                ?: continue
            val sender = incoming.address
            val senderPort = incoming.port

            // Служебные пакеты обрабатываем тут же: responder шлёт ответ
            // на порт отправителя (senderPort), а не на фиксированный 5005,
            // т.к. клиент слушает с произвольного локального порта.
            discoveryResponder?.let { res ->
                when (packet) {
                    is Packet.Discover -> res.respondAnnounce(sender, senderPort)
                    is Packet.Ping -> res.respondPong(sender, senderPort, packet)
                    else -> Unit
                }
            }
            if (packet is Packet.Discover || packet is Packet.Ping) continue

            onPacket(packet, sender, senderPort)
        }
    }

    private companion object {
        const val TAG = "AirMouse/UdpServer"
        const val ACCEPT_TIMEOUT_MS = 500
    }
}
