package com.airmouse.phone.net

import com.airmouse.proto.Net
import com.airmouse.proto.Packet
import com.airmouse.proto.PacketCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Тонкая обёртка над [DatagramSocket] для отправки [Packet]'ов.
 *
 * Отправка выполняется в отдельном потоке-диспетчере, чтобы блокирующий
 * I/O не мешал UI-потоку и потоку сенсоров. На практике send() на UDP
 * почти не блокируется, но даже короткие паузы могут давать jitter курсора,
 * поэтому изоляция потока оправдана.
 *
 * Сокет открывается с известным портом отправителя только при необходимости
 * получать ответы (DISCOVER/PING); для чистой отправки MOVE достаточно
 * любого свободного локального порта.
 */
class UdpTransport {

    private var socket: DatagramSocket? = null
    private val running = AtomicBoolean(false)
    private val lock = Any()

    // Одиночный отправляющий поток — отправки упорядочены, без гонок на сокете.
    private val dispatcher = Thread {
        while (running.get()) {
            val task: (() -> Unit)? = synchronized(lock) {
                if (queue.isEmpty()) null else queue.removeAt(0)
            }
            if (task != null) {
                try {
                    task.invoke()
                } catch (e: InterruptedException) {
                    return@Thread
                } catch (_: Throwable) {
                    // Одиночный сбой отправки не должен ронять цикл (UDP ненадёжен по природе).
                }
            } else {
                try {
                    Thread.sleep(1)
                } catch (_: InterruptedException) {
                    return@Thread
                }
            }
        }
    }.also { it.isDaemon = true }

    private val queue: MutableList<() -> Unit> = mutableListOf()

    /**
     * Открывает сокет. Если [bindLocalPort] > 0 — биндится на него
     * (нужно для приёма ANNOUNCE/PONG), иначе используется случайный порт.
     */
    @Synchronized
    fun open(bindLocalPort: Int = 0): Boolean {
        if (socket != null) return true
        return try {
            val s = if (bindLocalPort > 0) DatagramSocket(bindLocalPort) else DatagramSocket()
            s.broadcast = true
            socket = s
            running.set(true)
            if (!dispatcher.isAlive) dispatcher.start()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /** Сокет для приёма (DISCOVER-ответы, PONG). null, если не открыт. */
    fun socket(): DatagramSocket? = socket

    /**
     * Синхронная отправка пакета (минуя очередь диспетчера).
     * Используется для discovery/ping, где критично, чтобы пакет ушёл
     * НЕМЕДЛЕННО перед началом приёма ответа.
     */
    fun sendSync(host: String, port: Int = Net.DEFAULT_PORT, packet: Packet) {
        val s = socket ?: return
        try {
            val data = PacketCodec.encode(packet)
            val addr = InetAddress.getByName(host)
            s.send(DatagramPacket(data, data.size, addr, port))
        } catch (_: Throwable) { /* UDP */ }
    }

    /** Синхронная отправка broadcast (минуя очередь диспетчера). */
    fun sendBroadcastSync(port: Int = Net.DEFAULT_PORT, packet: Packet) {
        val s = socket ?: return
        try {
            val data = PacketCodec.encode(packet)
            val addr = InetAddress.getByName(Net.BROADCAST_ADDRESS)
            s.send(DatagramPacket(data, data.size, addr, port))
        } catch (_: Throwable) { /* UDP */ }
    }

    /** Отправляет [packet] по адресу [host]:[port]. */
    fun send(host: String, port: Int = Net.DEFAULT_PORT, packet: Packet) {
        val s = socket ?: return
        val data = PacketCodec.encode(packet)
        synchronized(lock) {
            queue.add {
                val addr = InetAddress.getByName(host)
                s.send(DatagramPacket(data, data.size, addr, port))
            }
        }
    }

    /** Шлёт broadcast-пакет (для DISCOVER). */
    fun sendBroadcast(port: Int = Net.DEFAULT_PORT, packet: Packet) {
        val s = socket ?: return
        val data = PacketCodec.encode(packet)
        synchronized(lock) {
            queue.add {
                val addr = InetAddress.getByName(Net.BROADCAST_ADDRESS)
                s.send(DatagramPacket(data, data.size, addr, port))
            }
        }
    }

    @Synchronized
    fun close() {
        running.set(false)
        dispatcher.interrupt()
        socket?.runCatching { close() }
        socket = null
        synchronized(lock) { queue.clear() }
    }
}
