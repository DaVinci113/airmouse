package com.airmouse.phone.net

import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.proto.PacketCodec
import java.net.DatagramPacket

/**
 * Результат автообнаружения: найденный ТВ-сервер в локальной сети.
 */
data class Device(
    val name: String,
    val host: String,
    val width: Int,
    val height: Int,
) {
    override fun toString(): String =
        if (name.isBlank()) host else "$name ($host)"
}

/**
 * Автообнаружение ТВ через UDP-broadcast.
 *
 * Шлёт [Packet.Discover] на 255.255.255.255:5005, затем [Net.DISCOVERY_TIMEOUT_MS]
 * слушает входящие [Packet.Announce]. Каждый ТВ, получивший broadcast, отвечает
 * своим именем и разрешением экрана.
 *
 * Запуск выполняется в отдельном потоке; результат возвращается через [onResult]
 * в потоке вызывающего (обычно UI). Это блокирующая операция — НЕ вызывать из UI-потока.
 *
 * @param transport открытый [UdpTransport] с биндованным портом (для приёма ответов)
 * @param onResult коллбэк со списком найденных устройств
 */
class Discovery(private val transport: UdpTransport) {

    fun discover(onResult: (List<Device>) -> Unit) {
        Thread {
            val found = mutableListOf<Device>()
            val socket = transport.socket()

            if (socket == null) {
                onResult(found)
                return@Thread
            }

            // Рассылаем DISCOVER синхронно (не через очередь диспетчера),
            // иначе пакет может уйти уже ПОСЛЕ того, как мы начали слушать ответ.
            transport.sendBroadcastSync(packet = Packet.Discover)

            // Слушаем ответы с таймаутом.
            socket.soTimeout = Net.DISCOVERY_TIMEOUT_MS.toInt()
            val buf = ByteArray(PacketCodec.MAX_PACKET_BYTES)
            val deadline = System.currentTimeMillis() + Net.DISCOVERY_TIMEOUT_MS

            while (System.currentTimeMillis() < deadline) {
                val incoming = DatagramPacket(buf, buf.size)
                val ok = try {
                    socket.receive(incoming)
                    true
                } catch (_: java.net.SocketTimeoutException) {
                    false
                } catch (_: Throwable) {
                    false
                }
                if (!ok) break

                val packet = PacketCodec.decode(incoming.data.copyOfRange(0, incoming.length))
                if (packet is Packet.Announce) {
                    val host = incoming.address.hostAddress ?: continue
                    val device = Device(packet.name, host, packet.width, packet.height)
                    // Дедуп: один ТВ может ответить несколько раз.
                    if (found.none { it.host == host }) found.add(device)
                }
            }

            onResult(found)
        }.also { it.isDaemon = true }.start()
    }
}
