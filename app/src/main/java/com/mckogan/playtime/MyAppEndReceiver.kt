package com.mckogan.playtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * "End now" on a "My app" notification: ends that session as if its time ran out, so opening the app
 * again within 10 minutes goes through "Want more?" and the 30-second wait.
 */
class MyAppEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(MyAppActivity.EXTRA_APP) ?: return
        val store = Store(context)
        if (store.myAppActive(pkg)) store.startMyAppCooldown(pkg)
        store.endMyApp(pkg)
    }
}
