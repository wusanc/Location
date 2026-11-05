package com.wusan.location.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import moe.wusan.xposed.hooks.sensor.StepSimulationContract as Contract

/**
 * Grant visibility of the two-field read-only configuration to an explicitly requesting client.
 * Android 11+ otherwise filters this provider for apps without a matching <queries> declaration.
 * This receiver neither accepts settings nor grants write access to the configuration.
 */
class StepConfigAccessReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getStringExtra(Contract.PACKAGE_EXTRA) ?: return
        if (target.isBlank() || target.length > 255) return
        runCatching {
            context.grantUriPermission(target, Uri.parse(Contract.URI), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.onFailure { Log.w("LocationSteps", "Could not grant step configuration read access", it) }
    }
}
