package com.airmouse.proto

/**
 * Сетевые константы, общие для клиента и сервера.
 */
object Net {
    /** UDP-порт по умолчанию для всей коммуникации (MOVE, DISCOVER, ANNOUNCE, PING...). */
    const val DEFAULT_PORT = 5005

    /**
     * Адрес для UDP-broadcast автообнаружения.
     * 255.255.255.255 работает на большинстве роутеров без доп. настройки.
     */
    const val BROADCAST_ADDRESS = "255.255.255.255"

    /** Сколько секунд клиент ждёт ответов ANNOUNCE после отправки DISCOVER. */
    const val DISCOVERY_TIMEOUT_MS = 1500L
}
