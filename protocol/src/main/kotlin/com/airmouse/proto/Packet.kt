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
        PONG(0x0B),
        DPAD_UP(0x0C),
        DPAD_DOWN(0x0D),
        DPAD_LEFT(0x0E),
        DPAD_RIGHT(0x0F),
        DPAD_CENTER(0x10);

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

    /** Стрелка пульта "Вверх" (performGlobalAction DPAD_UP). */
    data object DpadUp : Packet() { override val type = Type.DPAD_UP }

    /** Стрелка пульта "Вниз" (performGlobalAction DPAD_DOWN). */
    data object DpadDown : Packet() { override val type = Type.DPAD_DOWN }

    /** Стрелка пульта "Влево" (performGlobalAction DPAD_LEFT). */
    data object DpadLeft : Packet() { override val type = Type.DPAD_LEFT }

    /** Стрелка пульта "Вправо" (performGlobalAction DPAD_RIGHT). */
    data object DpadRight : Packet() { override val type = Type.DPAD_RIGHT }

    /** Кнопка "ОК" пульта (performGlobalAction DPAD_CENTER). */
    data object DpadCenter : Packet() { override val type = Type.DPAD_CENTER }

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
