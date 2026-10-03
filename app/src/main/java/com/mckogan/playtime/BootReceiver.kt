package com.mckogan.playtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Starts the guard again after the phone restarts or Play Time is updated. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            GuardService.start(context)
        }
    }
}
