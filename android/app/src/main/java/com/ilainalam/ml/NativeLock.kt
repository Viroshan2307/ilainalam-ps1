package com.ilainalam.ml

import android.util.Log
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ExecuTorch's native runtime is shared by the vision models and Llama. Running both at the same moment
 * (loading, classifying, generating) can stall the app, so EVERY native call holds this lock —
 * they always happen one after another, never together.
 *
 * It is a semaphore with "tickets" rather than a plain monitor, so that if a vision call ever freezes inside
 * native code, the watchdog in [VisionClassifier] can hand its ticket back and let the app carry on.
 * A ticket can only be returned once, whoever returns it.
 */
object NativeLock {
    private val sem = Semaphore(1, true)

    class Ticket internal constructor() {
        private val returned = AtomicBoolean(false)
        fun release() { if (returned.compareAndSet(false, true)) sem.release() }
    }

    fun acquire(): Ticket { sem.acquire(); return Ticket() }

    inline fun <T> withLock(block: () -> T): T {
        val t = acquire()
        try { return block() } finally { t.release() }
    }
}

/** Step logging — filter Logcat by the tag "IlaiNalam" to see exactly where a scan or load stops. */
object Trace {
    private const val TAG = "IlaiNalam"
    fun i(msg: String) { Log.i(TAG, "[${Thread.currentThread().name}] $msg") }
}
