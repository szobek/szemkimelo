package hu.szobek.szemkimelo

import android.content.Context

/** Az app beállításai és az időzítő állapota (SharedPreferences). */
class Prefs(context: Context) {
    private val sp = context.applicationContext
        .getSharedPreferences("szemkimelo", Context.MODE_PRIVATE)

    private fun int(key: String, def: Int) = sp.getInt(key, def)
    private fun bool(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun long(key: String, def: Long) = sp.getLong(key, def)

    /** Be van-e kapcsolva az emlékeztető (fut-e a háttérszolgáltatás). */
    var running: Boolean
        get() = bool("running", false)
        set(v) = sp.edit().putBoolean("running", v).apply()

    var workMinutes: Int
        get() = int("work_minutes", 20)
        set(v) = sp.edit().putInt("work_minutes", v).apply()

    var breakSeconds: Int
        get() = int("break_seconds", 20)
        set(v) = sp.edit().putInt("break_seconds", v).apply()

    var lunchEnabled: Boolean
        get() = bool("lunch_enabled", true)
        set(v) = sp.edit().putBoolean("lunch_enabled", v).apply()

    var lunchHour: Int
        get() = int("lunch_hour", 12)
        set(v) = sp.edit().putInt("lunch_hour", v).apply()

    var lunchMinute: Int
        get() = int("lunch_minute", 0)
        set(v) = sp.edit().putInt("lunch_minute", v).apply()

    var waterEnabled: Boolean
        get() = bool("water_enabled", true)
        set(v) = sp.edit().putBoolean("water_enabled", v).apply()

    var waterMinutes: Int
        get() = int("water_minutes", 60)
        set(v) = sp.edit().putInt("water_minutes", v).apply()

    var sound: Boolean
        get() = bool("sound", true)
        set(v) = sp.edit().putBoolean("sound", v).apply()

    var vibrate: Boolean
        get() = bool("vibrate", true)
        set(v) = sp.edit().putBoolean("vibrate", v).apply()

    /** Diagnosztika: az utolsó szünet tervezett és tényleges indulása (ms), valamint a megjelenítés módja. */
    var lastBreakPlanned: Long
        get() = long("last_break_planned", 0L)
        set(v) = sp.edit().putLong("last_break_planned", v).apply()

    var lastBreakFired: Long
        get() = long("last_break_fired", 0L)
        set(v) = sp.edit().putLong("last_break_fired", v).apply()

    var lastBreakMode: String
        get() = sp.getString("last_break_mode", "") ?: ""
        set(v) = sp.edit().putString("last_break_mode", v).apply()

    /** A következő szünet időpontja (ms, óra szerint); 0 = éppen szünet van / nincs tervezve. */
    var nextBreakAt: Long
        get() = long("next_break_at", 0L)
        set(v) = sp.edit().putLong("next_break_at", v).apply()
}
