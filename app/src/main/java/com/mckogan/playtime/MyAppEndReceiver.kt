package com.mckogan.playtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** "End now" on a "My app" notification: ends that session; the next open asks again. */
class MyAppEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(MyAppActivity.EXTRA_APP) ?: return
        Store(context).endMyApp(pkg)
    }
}
