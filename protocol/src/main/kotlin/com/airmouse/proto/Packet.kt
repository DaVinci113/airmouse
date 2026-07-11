package com.airmouse.proto

/**
 * Проводной формат Air Mouse.
 *
 * На проводе каждый пакет = `[type:1u8][payload]`, little-endian.
 * Простой и компактный бинарный формат: быстрее и легче JSON,
 * что критично для потоковой передачи координат с минимальным пингом.
 *
 * Коды [Type.code] стабильны — на них завязан парсер [PacketCodec].
 */
sealed class Packet {

    /** Идентификатор типа пакета. Порядок значений фиксирует нумерацию кодов. */
    enum class Type(val code: Int) {
        MOVE(0x01),
        TAP(0x02),
        LONG_PRESS(0x03),
        BACK(0x04),
        HOME(0x05),
        SCROLL(0x06),
        CALIBRATE(0x07),
        DISCOVER(0x08),
        ANNOUNCE(0x09),
        PING(0x0A),
        PONG(0x0B);

        companion object {
            private val byCode = entries.associateBy { it.code }
            fun fromCode(code: Int): Type? = byCode[code]
        }
    }

    abstract val type: Type

    // --- Клиент → Сервер ---

    /** Относительное смещение курсора в пикселях (накапливается на сервере). */
    data class Move(val dx: Float, val dy: Float) : Packet() {
        override val type = Type.MOVE
    }

    /** Короткий клик (ЛКМ) в текущей точке курсора. */
    data object Tap : Packet() { override val type = Type.TAP }

    /** Длинное нажатие (ПКМ / контекстное меню) в текущей точке курсора. */
    data object LongPress : Packet() { override val type = Type.LONG_PRESS }

    /** Системная кнопка "Назад". */
    data object Back : Packet() { override val type = Type.BACK }

    /** Системная кнопка "Домой". */
    data object Home : Packet() { override val type = Type.HOME }

    /** Жест прокрутки на [dx], [dy] пикселей от текущей точки курсора. */
    data class Scroll(val dx: Float, val dy: Float) : Packet() {
        override val type = Type.SCROLL
    }

    /** Сброс курсора в центр экрана (компенсация накопленного дрейфа). */
    data object Calibrate : Packet() { override val type = Type.CALIBRATE }

    // --- Обнаружение ---

    /** Broadcast от клиента: "кто тут ТВ?". Payload пустой. */
    data object Discover : Packet() { override val type = Type.DISCOVER }

    /**
     * Ответ ТВ на [Discover].
     * @param name имя устройства (например Build.MODEL)
     * @param width ширина экрана в px (для клиентской калибровки)
     * @param height высота экрана в px
     */
    data class Announce(val name: String, val width: Int, val height: Int) : Packet() {
        override val type = Type.ANNOUNCE
    }

    // --- RTT-измерение ---

    /** Запрос пинга; клиентское значение времени для расчёта RTT. */
    data class Ping(val clientNanos: Long) : Packet() { override val type = Type.PING }

    /** Ответ на [Ping]; эхо clientNanos обратно клиенту. */
    data class Pong(val clientNanos: Long) : Packet() { override val type = Type.PONG }
}
