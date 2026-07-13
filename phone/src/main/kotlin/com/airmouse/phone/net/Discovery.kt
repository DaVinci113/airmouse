package com.airmouse.phone.net

import android.content.Context
import android.util.Log
import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.proto.PacketCodec
import java.net.DatagramPacket
import java.net.NetworkInterface

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
 * Шлёт [Packet.Discover] на 255.255.255.255:5005 и directed-broadcast'ы подсетей,
 * затем слушает входящие [Packet.Announce].
 *
 * Запуск выполняется в отдельном потоке; результат возвращается через [onResult]
 * в потоке вызывающего (обычно UI). Это блокирующая операция — НЕ вызывать из UI-потока.
 *
 * Важно: на Android Wi-Fi чип фильтрует broadcast в power-save режиме, поэтому
 * discovery захватывает [NetLocks] (MulticastLock + HIGH_PERF WifiLock) на время
 * приёма ответов.
 *
 * @param context контекст приложения (для получения NetLocks)
 * @param transport открытый [UdpTransport] с биндованным портом (для приёма ответов)
 * @param onResult коллбэк со списком найденных устройств
 */
class Discovery(
    private val context: Context,
    private val transport: UdpTransport,
) {

    private val buf = ByteArray(PacketCodec.MAX_PACKET_BYTES)
    private var lastSendTs = 0L

    fun discover(onResult: (List<Device>) -> Unit) {
        Thread {
            val found = mutableListOf<Device>()
            val socket = transport.socket()

            if (socket == null) {
                Log.w(TAG, "Transport socket is null — discovery impossible")
                onResult(found)
                return@Thread
            }

            // Захватываем сетевые lock'и, чтобы Wi-Fi чип не фильтровал ответы.
            val locks = NetLocks(context)
            locks.acquire()

            try {
                val bcasts = buildList {
                    add(Net.BROADCAST_ADDRESS) // 255.255.255.255
                    addAll(subnetBroadcasts())  // directed, напр. 192.168.1.255
                }
                Log.i(TAG, "Discovery started, broadcasting to $bcasts")

                socket.soTimeout = RECEIVE_POLL_MS
                val deadline = System.currentTimeMillis() + TOTAL_TIMEOUT_MS
                var attempt = 0

                while (System.currentTimeMillis() < deadline) {
                    // Периодически повторяем DISCOVER — первый пакет часто теряется,
                    // пока Wi-Fi «просыпается» после захвата lock'ов.
                    if (attempt == 0 || System.currentTimeMillis() - lastSendTs > RESend_INTERVAL_MS) {
                        for (bcast in bcasts) {
                            transport.sendSync(bcast, Net.DEFAULT_PORT, Packet.Discover)
                        }
                        lastSendTs = System.currentTimeMillis()
                        attempt++
                    }

                    val incoming = DatagramPacket(buf, buf.size)
                    val ok = try {
                        socket.receive(incoming)
                        true
                    } catch (_: java.net.SocketTimeoutException) {
                        false
                    } catch (e: Throwable) {
                        Log.w(TAG, "receive error: ${e.message}")
                        false
                    }
                    if (!ok) continue

                    val packet = PacketCodec.decode(incoming.data.copyOfRange(0, incoming.length))
                    if (packet is Packet.Announce) {
                        val host = incoming.address.hostAddress ?: continue
                        val device = Device(packet.name, host, packet.width, packet.height)
                        // Дедуп: один ТВ может ответить несколько раз.
                        if (found.none { it.host == host }) {
                            Log.i(TAG, "Found device: $device")
                            found.add(device)
                        }
                    }
                }

                Log.i(TAG, "Discovery finished, ${found.size} device(s) found")
            } finally {
                locks.release()
                onResult(found)
            }
        }.also { it.isDaemon = true }.start()
    }

    /**
     * Возвращает directed-broadcast-адреса (напр. "192.168.1.255") для всех
     * активных IPv4-интерфейсов. Вычисляется из локального адреса /24.
     */
    private fun subnetBroadcasts(): List<String> {
        val result = mutableListOf<String>()
        try {
            for (iface in NetworkInterface.getNetworkInterfaces() ?: return emptyList()) {
                try {
                    if (iface.isLoopback || !iface.isUp) continue
                } catch (_: Throwable) {
                    continue
                }
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    // Только IPv4.
                    if (host.contains(":")) continue
                    siteLocalBroadcast(host)?.let { result.add(it) }
                }
            }
        } catch (_: Throwable) {
            // Перечисление интерфейсов иногда падает на некоторых прошивках.
        }
        return result.distinct()
    }

    /** Считает directed broadcast для IPv4 site-local адреса /24. */
    private fun siteLocalBroadcast(ip: String): String? {
        val parts = ip.split(".")
        if (parts.size != 4) return null
        return try {
            "${parts[0]}.${parts[1]}.${parts[2]}.255"
        } catch (_: Throwable) {
            null
        }
    }

    private companion object {
        const val TAG = "AirMouse/Discovery"
        const val TOTAL_TIMEOUT_MS = 3000L
        const val RESend_INTERVAL_MS = 600L
        const val RECEIVE_POLL_MS = 200
    }
}

