package com.airmouse.tv.net

import android.os.Build
import android.util.Log
import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.proto.PacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Отвечает на служебные пакеты DISCOVER и PING от клиентов.
 *
 * Отправка выполняется синхронно в сетевом потоке [UdpServer]: ответы на
 * discovery/ping короткие, а открытие отдельного потока-диспетчера лишь
 * добавляло бы задержку. Используется тот же сокет, что и для приёма, —
 * ответ уходит с порта 5005 на порт отправителя [port].
 */
class DiscoveryResponder(
    val socket: () -> DatagramSocket?,
    private val width: () -> Int,
    private val height: () -> Int,
) {

    /** Шлёт [Packet.Announce] обратно клиенту, обнаружившему ТВ. */
    fun respondAnnounce(to: InetAddress, port: Int) {
        val s = socket() ?: return
        val name = Build.MODEL ?: "Android TV"
        val announce = Packet.Announce(name, width(), height())
        send(s, to, port, announce)
        Log.d(TAG, "ANNOUNCE → $to:$port ($name ${width()}x${height()})")
    }

    /** Шлёт [Packet.Pong] с эхом clientNanos (для расчёта RTT клиентом). */
    fun respondPong(to: InetAddress, port: Int, ping: Packet.Ping) {
        val s = socket() ?: return
        send(s, to, port, Packet.Pong(ping.clientNanos))
    }

    private fun send(s: DatagramSocket, to: InetAddress, port: Int, packet: Packet) {
        runCatching {
            val data = PacketCodec.encode(packet)
            s.send(DatagramPacket(data, data.size, to, port))
        }.onFailure { Log.w(TAG, "send failed: ${it.message}") }
    }

    private companion object { const val TAG = "AirMouse/Discovery" }
}
