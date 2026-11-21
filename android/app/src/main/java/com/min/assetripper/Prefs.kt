package com.min.assetripper

import android.content.Context

/** User settings. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var port: Int
        get() = sp.getInt("port", 0)
        set(v) = sp.edit().putInt("port", v).apply()

    var heapMb: Int
        get() = sp.getInt("heap_mb", 0)
        set(v) = sp.edit().putInt("heap_mb", v).apply()

    var autoOpen: Boolean
        get() = sp.getBoolean("auto_open", true)
        set(v) = sp.edit().putBoolean("auto_open", v).apply()

    var wakeLock: Boolean
        get() = sp.getBoolean("wake_lock", true)
        set(v) = sp.edit().putBoolean("wake_lock", v).apply()

    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastCheck: Long
        get() = sp.getLong("last_check", 0L)
        set(v) = sp.edit().putLong("last_check", v).apply()
}
