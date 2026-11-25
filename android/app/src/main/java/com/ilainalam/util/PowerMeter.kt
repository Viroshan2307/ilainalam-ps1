package com.ilainalam.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Average power over a measured period. Phone must be UNPLUGGED, otherwise the charger hides the real draw. */
data class PowerReading(
    val avgMilliwatts: Double,
    val seconds: Double,
    val samples: Int,
    val charging: Boolean,
) {
    val joules: Double get() = avgMilliwatts * seconds / 1000.0
}

/**
 * Reads the phone's battery fuel gauge (current × voltage) every 200 ms.
 * Notes for the report: whole-phone power (screen included), gauge resolution varies by phone (some update only
 * about once a second), so short tasks are measured as a loop of many runs. Units are normalised: most phones
 * report microamps; a few report milliamps.
 */
class PowerMeter(private val context: Context) {
    private val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val samples = mutableListOf<Double>()
    private var job: Job? = null
    private var t0 = 0L

    fun charging(): Boolean {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun milliwattsNow(): Double? {
        val raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (raw == Long.MIN_VALUE || raw == 0L) return null
        val microAmps = abs(raw).let { if (it < 20_000) it * 1000 else it }   // some phones report mA
        val mv = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        if (mv <= 0) return null
        return microAmps / 1000.0 * mv / 1000.0      // mA × V = mW
    }

    fun start(scope: CoroutineScope) {
        stopJob(); synchronized(samples) { samples.clear() }; t0 = SystemClock.elapsedRealtime()
        job = scope.launch(Dispatchers.IO) {
            while (isActive) { milliwattsNow()?.let { synchronized(samples) { samples.add(it) } }; delay(200) }
        }
    }

    private fun stopJob() { job?.cancel(); job = null }

    fun stop(): PowerReading? {
        stopJob()
        val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val list = synchronized(samples) { samples.toList() }
        if (list.isEmpty()) return null
        return PowerReading(list.average(), secs, list.size, charging())
    }

    /** Measures [seconds] of idle (screen on, nothing running) as the baseline. */
    suspend fun idle(scope: CoroutineScope, seconds: Double = 3.0): PowerReading? {
        start(scope); delay((seconds * 1000).toLong()); return stop()
    }
}
