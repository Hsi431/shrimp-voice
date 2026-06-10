package com.shrimp.voice.wakelocks

import android.annotation.SuppressLint
import android.content.Context
import android.content.Context.POWER_SERVICE
import android.content.Context.WIFI_SERVICE
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

class WifiWakeLock {
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var wifiLock: WifiManager.WifiLock

    fun create(context: Context, tag: String) {
        check(!::wakeLock.isInitialized || !::wifiLock.isInitialized) {
            "create called multiple times"
        }

        wakeLock = (context.getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$tag::Wakelock")

        @Suppress("DEPRECATION")
        val wifiLockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        else WifiManager.WIFI_MODE_FULL_HIGH_PERF

        wifiLock = (context.getSystemService(WIFI_SERVICE) as WifiManager).createWifiLock(
            wifiLockType,
            "$tag::WifiLock"
        )
    }

    @SuppressLint("WakelockTimeout")
    fun acquire() {
        check(::wakeLock.isInitialized && ::wifiLock.isInitialized) {
            "acquire called before create"
        }
        wakeLock.acquire()
        wifiLock.acquire()
        Timber.d("Acquired wake locks")
    }

    fun release() {
        check(::wakeLock.isInitialized && ::wifiLock.isInitialized) {
            "release called before create"
        }
        if (wakeLock.isHeld)
            wakeLock.release()
        if (wifiLock.isHeld)
            wifiLock.release()
        Timber.d("Released wake locks")
    }
}
