package com.min.assetripper

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/** Server state shared by ServerService and the UI. */
object ServerState {
    enum class Phase { STOPPED, INSTALLING, STARTING, RUNNING, ERROR }

    @Volatile var phase: Phase = Phase.STOPPED
    @Volatile var port: Int = 0
    @Volatile var progress: Int = 0
    @Volatile var error: String = ""

    private val lines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun addListener(r: Runnable) {
        listeners.add(r)
    }

    fun removeListener(r: Runnable) {
        listeners.remove(r)
    }

    private fun notifyChanged() {
        main.post { for (l in listeners) l.run() }
    }

    fun set(newPhase: Phase, message: String = "") {
        phase = newPhase
        error = message
        notifyChanged()
    }

    fun updateProgress(p: Int) {
        progress = p
        notifyChanged()
    }

    fun log(line: String) {
        synchronized(lines) {
            if (lines.size >= 2000) lines.removeFirst()
            lines.addLast(line)
        }
    }

    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }

    fun clearLog() {
        synchronized(lines) { lines.clear() }
    }
}
