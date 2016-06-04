package app.adbuster

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootComplete : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        checkStartVpnOnBoot(context)
    }
}
