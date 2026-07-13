package com.airmouse.phone.net

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log

/**
 * Удерживает Wi-Fi в режиме приёма broadcast/multicast и без power-save.
 *
 * На Android Wi-Fi чип в энергосберегающем режиме аппаратно фильтрует
 * входящие broadcast/multicast датаграммы — ответы discovery (ANNOUNCE)
 * просто не доходят до сокета, даже если они пришли на интерфейс.
 *
 * - [MulticastLock] отключает эту фильтрацию для нашего приложения;
 * - [WifiManager.createWifiLock] с HIGH_PERF_TX отключает power-save,
 *   чтобы сокет стабильно принимал пакеты во время discovery.
 *
 * Оба lock'а должны захватываться на время discovery и отпускаться после.
 */
class NetLocks(context: Context) {

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /** Захватывает оба lock'а. Повторный вызов безопасен. */
    fun acquire() {
        try {
            if (multicastLock == null) {
                val ml = wifiManager?.createMulticastLock(TAG)?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                multicastLock = ml
            }
            if (wifiLock == null) {
                val wl = wifiManager?.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG
                )?.apply {
                    setReferenceCounted(false)
                    acquire()
                }
                wifiLock = wl
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to acquire network locks: ${e.message}")
        }
    }

    /** Отпускает оба lock'а. Повторный вызов безопасен. */
    fun release() {
        try { multicastLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        try { wifiLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        multicastLock = null
        wifiLock = null
    }
}

private const val TAG = "AirMouse/NetLocks"
