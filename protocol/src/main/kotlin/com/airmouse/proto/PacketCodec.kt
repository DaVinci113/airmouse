package com.airmouse.proto

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Кодирование/декодирование [Packet] в линейный байтовый формат.
 *
 * Формат (little-endian): `[type:1u8][payload]`.
 *
 * Этот формат — единственный источник правды для клиентской и серверной стороны:
 * обе зависят от этого модуля, поэтому проводной формат не может разойтись.
 */
object PacketCodec {

    /** Максимальный размер пакета (ограничивает длину имени устройства в ANNOUNCE). */
    const val MAX_PACKET_BYTES = 256

    /**
     * Сериализует [packet] в новый byte-массив.
     * Не выбрасывает исключения для корректных входов.
     */
    fun encode(packet: Packet): ByteArray {
        val buf = ByteBuffer.allocate(MAX_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        write(buf, packet)
        return buf.array().copyOfRange(0, buf.position())
    }

    private fun write(buf: ByteBuffer, packet: Packet) {
        buf.put(packet.type.code.toByte())
        when (packet) {
            is Packet.Move -> {
                buf.putFloat(packet.dx)
                buf.putFloat(packet.dy)
            }
            is Packet.Scroll -> {
                buf.putFloat(packet.dx)
                buf.putFloat(packet.dy)
            }
            is Packet.Announce -> {
                val nameBytes = packet.name.toByteArray(Charsets.UTF_8)
                // Длина имени ограничена, чтобы пакет влез в MAX_PACKET_BYTES.
                val limited = if (nameBytes.size > 64) nameBytes.copyOf(64) else nameBytes
                buf.putShort(packet.width.toShort())
                buf.putShort(packet.height.toShort())
                buf.put(limited.size.toByte())
                buf.put(limited)
            }
            is Packet.Ping -> buf.putLong(packet.clientNanos)
            is Packet.Pong -> buf.putLong(packet.clientNanos)
            // Пакеты без payload.
            Packet.Tap, Packet.LongPress, Packet.Back,
            Packet.Home, Packet.Calibrate, Packet.Discover -> Unit
        }
    }

    /**
     * Десериализует пакет из [data] (используются байты с 0 по length).
     * @return пакет или null, если данные не распознаны/повреждены.
     */
    fun decode(data: ByteArray): Packet? {
        if (data.isEmpty()) return null
        val type = Packet.Type.fromCode(data[0].toInt() and 0xFF) ?: return null
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.get() // пропускаем байт типа
        return try {
            read(buf, type)
        } catch (_: Throwable) {
            // Буфер короче ожидаемого или битая кодировка UTF-8 — просто игнорируем.
            null
        }
    }

    private fun read(buf: ByteBuffer, type: Packet.Type): Packet = when (type) {
        Packet.Type.MOVE -> Packet.Move(buf.getFloat(), buf.getFloat())
        Packet.Type.SCROLL -> Packet.Scroll(buf.getFloat(), buf.getFloat())
        Packet.Type.TAP -> Packet.Tap
        Packet.Type.LONG_PRESS -> Packet.LongPress
        Packet.Type.BACK -> Packet.Back
        Packet.Type.HOME -> Packet.Home
        Packet.Type.CALIBRATE -> Packet.Calibrate
        Packet.Type.DISCOVER -> Packet.Discover
        Packet.Type.ANNOUNCE -> {
            val width = buf.getShort().toInt() and 0xFFFF
            val height = buf.getShort().toInt() and 0xFFFF
            val nameLen = buf.get().toInt() and 0xFF
            val nameBytes = ByteArray(nameLen)
            buf.get(nameBytes)
            Packet.Announce(String(nameBytes, Charsets.UTF_8), width, height)
        }
        Packet.Type.PING -> Packet.Ping(buf.getLong())
        Packet.Type.PONG -> Packet.Pong(buf.getLong())
    }
}
