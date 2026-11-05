package com.wusan.location.service

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import moe.wusan.xposed.hooks.sensor.StepSimulationContract as Contract

data class StepSimulationSettings(val enabled: Boolean = false, val cadence: Int = Contract.DEFAULT_CADENCE) {
    companion object {
        private const val PREFS = "step_simulation"

        fun read(context: Context): StepSimulationSettings {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return StepSimulationSettings(
                prefs.getBoolean(Contract.ENABLED, false),
                prefs.getInt(Contract.CADENCE, Contract.DEFAULT_CADENCE)
                    .coerceIn(Contract.MIN_CADENCE, Contract.MAX_CADENCE)
            )
        }

        fun save(context: Context, settings: StepSimulationSettings): Boolean {
            require(settings.cadence in Contract.MIN_CADENCE..Contract.MAX_CADENCE)
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(Contract.ENABLED, settings.enabled)
                .putInt(Contract.CADENCE, settings.cadence)
                .commit()
            if (saved) context.contentResolver.notifyChange(Uri.parse(Contract.URI), null)
            return saved
        }
    }
}

/** Read-only IPC: never expose the main preferences, coordinates, credentials or command key. */
class StepConfigProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        require(uri.authority == Contract.AUTHORITY && uri.path == "/config")
        val config = StepSimulationSettings.read(requireNotNull(context))
        val columns = projection ?: arrayOf(Contract.ENABLED, Contract.CADENCE)
        require(columns.all { it == Contract.ENABLED || it == Contract.CADENCE })
        return MatrixCursor(columns).apply {
            addRow(columns.map { if (it == Contract.ENABLED) if (config.enabled) 1 else 0 else config.cadence })
        }
    }

    override fun getType(uri: Uri) = "vnd.android.cursor.item/vnd.com.wusan.location.stepconfig"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?)
        : Int = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?)
        : Int = throw UnsupportedOperationException("Read only")
}
