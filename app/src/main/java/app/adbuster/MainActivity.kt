package app.adbuster

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.util.Log

class MainActivity : Activity() {
    companion object {
        private val TAG = "MainActivity"
    }

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.form)

        findViewById(R.id.connect).setOnClickListener {
            val intent = VpnService.prepare(this)
            if (intent != null) {
                startActivityForResult(intent, 0)
            } else {
                onActivityResult(0, RESULT_OK, null)
            }
        }

        findViewById(R.id.disconnect).setOnClickListener {
            Log.i(TAG, "Attempting to disconnect")

            val intent = Intent(this, VpnService::class.java)
            intent.putExtra("COMMAND", Command.STOP.ordinal)
            startService(intent)
        }
    }

    override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        if (result == RESULT_OK) {
            val intent = Intent(this, VpnService::class.java)
            intent.putExtra("COMMAND", Command.START.ordinal)
            intent.putExtra("NOTIFICATION_INTENT",
                PendingIntent.getActivity(this, 0,
                        Intent(this, MainActivity::class.java), 0))
            startService(intent)
        }
    }
}
