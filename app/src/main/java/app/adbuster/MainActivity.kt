package app.adbuster

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Bundle
import android.support.v4.content.LocalBroadcastManager
import android.util.Log
import android.widget.TextView
import net.hockeyapp.android.CrashManager
import kotlinx.android.synthetic.main.form.*

class MainActivity : Activity() {
    companion object {
        private val TAG = "MainActivity"
    }

    var mVpnServiceBroadcastReceiver : BroadcastReceiver? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.form)

        start.setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 0)
            } else {
                onActivityResult(0, RESULT_OK, null)
            }
        }

        stop.setOnClickListener {
            Log.i(TAG, "Attempting to disconnect")

            val intent = Intent(this, AdVpnService::class.java)
            intent.putExtra("COMMAND", Command.STOP.ordinal)
            startService(intent)
        }

        mVpnServiceBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val str_id = intent.getIntExtra(VPN_UPDATE_STATUS_EXTRA, R.string.notification_stopped)
                updateStatus(str_id)
            }
        }
    }

    private fun updateStatus(textId: Int) {
        text_status.text = getString(textId)
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (result == RESULT_OK) {
            val intent = Intent(this, AdVpnService::class.java)
            intent.putExtra("COMMAND", Command.START.ordinal)
            intent.putExtra("NOTIFICATION_INTENT",
                PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java), 0))
            startService(intent)
        }
    }

    override fun onPause() {
        super.onPause()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(mVpnServiceBroadcastReceiver)
    }

    override fun onResume() {
        super.onResume()
        CrashManager.register(this)

        updateStatus(AdVpnService.vpnStatusTextId)
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(mVpnServiceBroadcastReceiver, IntentFilter(VPN_UPDATE_STATUS_INTENT))
    }
}
