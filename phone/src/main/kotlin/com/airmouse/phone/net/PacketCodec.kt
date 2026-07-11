package com.airmouse.phone.net

import com.airmouse.proto.Packet
import java.nio.ByteBuffer

/**
 * Кодек для сериализации/десериализации пакетов.
 */
object PacketCodec {
    const val MAX_PACKET_BYTES = 1024

    fun encode(packet: Packet): ByteArray {
        // Упрощённая реализация — замените на реальную логику
        return when (packet) {
            is Packet.Move -> ByteBuffer.allocate(12).apply {
                put(1) // тип MOVE
                putFloat(packet.dx)
                putFloat(packet.dy)
            }.array()
            is Packet.Ping -> ByteBuffer.allocate(12).apply {
                put(2) // тип PING
                putLong(packet.clientNanos)
            }.array()
            else -> ByteArray(0)
        }
    }

    fun decode(data: ByteArray): Packet? {
        if (data.isEmpty()) return null
        val buffer = ByteBuffer.wrap(data)
        return when (buffer.get().toInt()) {
            3 -> Packet.Pong(buffer.getLong()) // тип PONG
            else -> null
        }
    }
}