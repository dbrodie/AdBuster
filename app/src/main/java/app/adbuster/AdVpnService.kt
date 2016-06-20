package app.adbuster

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Message
import android.os.ParcelFileDescriptor
import android.support.v4.app.NotificationCompat
import android.support.v4.content.LocalBroadcastManager
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.widget.Toast
import net.hockeyapp.android.ExceptionHandler
import org.pcap4j.packet.*
import org.xbill.DNS.*

import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

enum class Command {
    START, STOP
}

class VpnNetworkException(msg: String) : RuntimeException(msg)

const val VPN_STATUS_STARTING = 0
const val VPN_STATUS_RUNNING = 1
const val VPN_STATUS_STOPPING = 2
const val VPN_STATUS_WAITING_FOR_NETWORK = 3
const val VPN_STATUS_RECONNECTING = 4
const val VPN_STATUS_RECONNECTING_NETWORK_ERROR = 5
const val VPN_STATUS_STOPPED = 6

fun vpnStatusToTextId(status: Int): Int = when(status) {
    VPN_STATUS_STARTING -> R.string.notification_starting
    VPN_STATUS_RUNNING -> R.string.notification_running
    VPN_STATUS_STOPPING -> R.string.notification_stopping
    VPN_STATUS_WAITING_FOR_NETWORK -> R.string.notification_waiting_for_net
    VPN_STATUS_RECONNECTING -> R.string.notification_reconnecting
    VPN_STATUS_RECONNECTING_NETWORK_ERROR -> R.string.notification_reconnecting_error
    VPN_STATUS_STOPPED -> R.string.notification_stopped
    else -> throw IllegalArgumentException("Invalid vpnStatus value ($status)")
}

const val VPN_MSG_STATUS_UPDATE = 0

const val VPN_UPDATE_STATUS_INTENT = "app.adbuster.VPN_UPDATE_STATUS"
const val VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS"

const val MIN_RETRY_TIME = 5
const val MAX_RETRY_TIME = 2*60

fun checkStartVpnOnBoot(context: Context) {
    Log.i("BOOT", "Checking whether to start ad buster on boot")

    val pref = context.getSharedPreferences(context.getString(R.string.preferences_file_key), Context.MODE_PRIVATE)
    if (!pref.getBoolean(context.getString(R.string.vpn_enabled_key), false)) {
        return
    }

    if (VpnService.prepare(context) != null) {
        Log.i("BOOT", "VPN preparation not confirmed by user, changing enabled to false")
        pref.edit().putBoolean(context.getString(R.string.vpn_enabled_key), false).apply()
    }

    Log.i("BOOT", "Starting ad buster from boot")

    val intent = Intent(context, AdVpnService::class.java)
    intent.putExtra("COMMAND", Command.START.ordinal)
    intent.putExtra("NOTIFICATION_INTENT",
            PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java), 0))
    context.startService(intent)

}

class AdVpnService : VpnService(), Handler.Callback {
    companion object {
        private val TAG = "VpnService"
        // TODO: Temporary Hack til refactor is done
        var vpnStatus: Int = VPN_STATUS_STOPPED
    }

    // TODO: There must be a better way in kotlin to do this
    private val commandValue = mapOf(
        Pair(Command.START.ordinal, Command.START),
        Pair(Command.STOP.ordinal, Command.STOP)
    )

    private var mConnectivityChangedReceiver : BroadcastReceiver? = null

    // TODO: This public is temporary
    var mHandler: Handler? = null
    private var vpnThread: AdVpnThread = AdVpnThread(this)
    private var mNotificationIntent: PendingIntent? = null


    // TODO: This public is temporary
    var mBlockedHosts: Set<String>? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (commandValue[intent.getIntExtra("COMMAND", Command.START.ordinal)]) {
            Command.START -> startVpn(intent.getParcelableExtra<PendingIntent>("NOTIFICATION_INTENT"))
            Command.STOP -> stopVpn()
        }

        return Service.START_STICKY
    }

    private fun updateVpnStatus(status: Int) {
        vpnStatus = status
        val text_id = vpnStatusToTextId(status)
        val notification = NotificationCompat.Builder(this)
                .setSmallIcon(R.drawable.ic_vpn_notification)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(text_id))
                .setContentIntent(mNotificationIntent)
                .setPriority(Notification.PRIORITY_MIN)
                .build()

        startForeground(10, notification)

        val intent = Intent(VPN_UPDATE_STATUS_INTENT)
        intent.putExtra(VPN_UPDATE_STATUS_EXTRA, status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    private fun startVpn(notificationIntent: PendingIntent) {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = Handler(this)
        }

        // TODO: Should this be in the activity instead?
        val edit_pref = getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        edit_pref.putBoolean(getString(R.string.vpn_enabled_key), true)
        edit_pref.apply()

        mNotificationIntent = notificationIntent
        updateVpnStatus(VPN_STATUS_STARTING)

        mConnectivityChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getIntExtra(ConnectivityManager.EXTRA_NETWORK_TYPE, 0) == ConnectivityManager.TYPE_VPN) {
                    Log.i(TAG, "Ignoring connectivity changed for our own network")
                    return
                }

                if (intent.action != ConnectivityManager.CONNECTIVITY_ACTION) {
                    Log.e(TAG, "Got bad intent on connectivity changed " + intent.action)
                }
                if (intent.getBooleanExtra(ConnectivityManager.EXTRA_NO_CONNECTIVITY, false)) {
                    Log.i(TAG, "Connectivity changed to no connectivity, wait for a network")
                    waitForNetVpn()
                } else {
                    Log.i(TAG, "Network changed, try to reconnect")
                    reconnect()
                }


            }
        }
        registerReceiver(mConnectivityChangedReceiver, IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION))

        restartVpnThread()
    }

    private fun restartVpnThread() {
        vpnThread.stopThread()
        vpnThread.startThread()
    }

    private fun stopVpnThread() {
        vpnThread.stopThread()
    }

    private fun waitForNetVpn() {
        stopVpnThread()
        updateVpnStatus(VPN_STATUS_WAITING_FOR_NETWORK)
    }

    private fun reconnect() {
        updateVpnStatus(VPN_STATUS_RECONNECTING)
        restartVpnThread()
    }

    private fun stopVpn() {
        // TODO: Should this be in the activity instead?
        val edit_pref = getSharedPreferences(getString(R.string.preferences_file_key), MODE_PRIVATE).edit()
        edit_pref.putBoolean(getString(R.string.vpn_enabled_key), false)
        edit_pref.apply()

        Log.i(TAG, "Stopping Service")
        stopVpnThread()
        mConnectivityChangedReceiver?.let { unregisterReceiver(it) }
        mConnectivityChangedReceiver = null
        updateVpnStatus(VPN_STATUS_STOPPED)
        stopSelf()
    }

    override fun onDestroy() {
        Log.i(TAG, "Destroyed, shutting down")
        stopVpn()
    }

    override fun handleMessage(message: Message?): Boolean {
        if (message == null) {
            return true
        }

        when (message.what) {
            VPN_MSG_STATUS_UPDATE -> updateVpnStatus(message.arg1)
            else -> throw IllegalArgumentException("Invalid message with what = ${message.what}")
        }
        return true
    }


    private fun logPacket(packet: ByteArray) = logPacket(packet, 0, packet.size)

    private fun logPacket(packet: ByteArray, size: Int) = logPacket(packet, 0, size)

    private fun logPacket(packet: ByteArray, offset: Int, size: Int) {
        var logLine = "PACKET: <"
        for (index in (offset..(size-1))) {
            logLine += String.format("%02x", packet[index])
        }

        Log.i(TAG, logLine + ">")
    }

    private fun logPacketNice(packet: ByteBuffer) {
        Log.i(TAG, "=============== PACKET ===============")
        var logLine = String.format("%04x: ", 0)
        for ((index, value) in packet.array().withIndex()) {
            if (index != 0 && index % 16 == 0) {
                Log.i(TAG, logLine)
                logLine = String.format("%04x: ", index)
            }

            if (index == packet.limit()) {
                break
            }

            logLine += String.format("%02x ", value)
        }

        Log.i(TAG, logLine)
    }
}
