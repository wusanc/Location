package com.wusan.location.ui.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.wusan.location.R

/** Store appearance separately from motion settings and restore it before activities inflate. */
object AppearanceSettings {
    enum class Mode(val key: String, val nightMode: Int, val label: Int) {
        SYSTEM("system", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, R.string.appearance_system),
        LIGHT("light", AppCompatDelegate.MODE_NIGHT_NO, R.string.appearance_light),
        DARK("dark", AppCompatDelegate.MODE_NIGHT_YES, R.string.appearance_dark)
    }

    private const val PREFS = "appearance"

    fun mode(context: Context): Mode {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("mode", null)
        return Mode.entries.firstOrNull { it.key == saved } ?: Mode.SYSTEM
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(mode(context).nightMode)
    }

    fun save(context: Context, mode: Mode): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode", mode.key).commit()

    fun isDark(context: Context): Boolean =
        context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
