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
import java.io.InputStreamReader
import java.net.*
import java.nio.ByteBuffer
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

const private val VPN_MSG_STATUS_UPDATE = 0

const val VPN_UPDATE_STATUS_INTENT = "app.adbuster.VPN_UPDATE_STATUS"
const val VPN_UPDATE_STATUS_EXTRA = "VPN_STATUS"

const val MIN_RETRY_TIME = 5
const val MAX_RETRY_TIME = 2*60

class AdVpnService : VpnService(), Handler.Callback, Runnable {
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

    private var mHandler: Handler? = null
    private var mThread: Thread? = null
    private var mNotificationIntent: PendingIntent? = null
    private var mInterface: ParcelFileDescriptor? = null
    private var m_in_fd: InterruptibleFileInputStream? = null

    private var mDnsServer: InetAddress? = null
    private var mBlockedHosts: Set<String>? = null

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
        Log.i(TAG, "Restarting Vpn Thread")
        stopVpnThread()
        mThread = Thread(this, "AdBusterThread")
        mThread?.start()
        Log.i(TAG, "Vpn Thread started")
    }

    private fun stopVpnThread() {
        Log.i(TAG, "Stopping Vpn Thread")
        mThread?.interrupt()
        m_in_fd?.interrupt()
        mThread?.join(2000)
        if (mThread?.isAlive ?: false) {
            Log.w(TAG, "Couldn't kill Vpn Thread")
        }
        mThread = null
        Log.i(TAG, "Vpn Thread stopped")
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

    @Synchronized override fun run() {
        try {
            Log.i(TAG, "Starting")

            // Load the block list
            loadBlockedHosts()

            mHandler!!.sendMessage(mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_STARTING, 0))

            var retryTimeout = MIN_RETRY_TIME
            // Try connecting the vpn continuously
            while (true) {
                try {
                    // If the function returns, that means it was interrupted
                    runVpn()

                    Log.i(TAG, "Told to stop")
                    break
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: VpnNetworkException) {
                    // We want to filter out VpnNetworkException from out crash analytics as these
                    // are exceptions that we expect to happen from network errors
                    Log.w(TAG, "Network exception in vpn thread, ignoring and reconnecting", e)
                    // If an exception was thrown, show to the user and try again
                    mHandler!!.sendMessage(mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RECONNECTING_NETWORK_ERROR, 0))
                } catch (e: Exception) {
                    Log.e(TAG, "Network exception in vpn thread, reconnecting", e)
                    ExceptionHandler.saveException(e, Thread.currentThread(), null)
                    mHandler!!.sendMessage(mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RECONNECTING_NETWORK_ERROR, 0))
                }

                // ...wait for 2 seconds and try again
                Log.i(TAG, "Retrying to connect in $retryTimeout seconds...")
                Thread.sleep(retryTimeout.toLong() * 1000)
                retryTimeout = if (retryTimeout < MAX_RETRY_TIME) {
                    retryTimeout * 2
                } else {
                    retryTimeout
                }
            }

            Log.i(TAG, "Stopped")
        } catch (e: InterruptedException) {
            Log.i(TAG, "Vpn Thread interrupted")
        } catch (e: Exception) {
            ExceptionHandler.saveException(e, Thread.currentThread(), null)
            Log.e(TAG, "Exception in run() ", e)
        } finally {
            mHandler!!.sendMessage(mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_STOPPING, 0))
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun runVpn() {
        // Authenticate and configure the virtual network interface.
        val pfd = configure()
        mInterface = pfd

        Log.i(TAG, "FD = " + mInterface!!.fd)

        // Packets to be sent are queued in this input stream.
        m_in_fd = InterruptibleFileInputStream(pfd.fileDescriptor)

        // Allocate the buffer for a single packet.
        val packet = ByteArray(32767)

        // Like this `Executors.newCachedThreadPool()`, except with an upper limit
        val executor = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

        try {
            // Now we are connected. Set the flag and show the message.
            mHandler!!.sendMessage(mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RUNNING, 0))

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                Log.i(TAG, "WAITING FOR PACKET!")
                val length: Int
                try {
                    length = m_in_fd!!.read(packet)
                } catch (e: InterruptibleFileInputStream.InterruptedStreamException) {
                    Log.i(TAG, "Told to stop VPN")
                    return
                }
                Log.i(TAG, "DONE WAITING FOR PACKET!")
                if (length == 0) {
                    // TODO: Possibly change to exception
                    Log.w(TAG, "Got empty packet!")
                }

                val read_packet = packet.copyOfRange(0, length)

                // Packets received need to be written to this output stream.
                val out_fd = FileOutputStream(pfd.fileDescriptor)

                // Packets to be sent to the real DNS server will need to be protected from the VPN
                val dns_socket = DatagramSocket()
                protect(dns_socket)

                Log.i(TAG, "Starting new thread to handle dns request")
                Log.i(TAG, "Executing: ${executor.activeCount}")
                Log.i(TAG, "Backlog: ${executor.queue.size}")
                // Start a new thread to handle the DNS request
                executor.execute {
                    handleDnsRequest(read_packet, dns_socket, out_fd)
                }
            }
        } finally {
            executor.shutdownNow()
            pfd.close()
            mInterface = null
        }
    }

    private fun handleDnsRequest(packet: ByteArray, dnsSocket: DatagramSocket, outFd: FileOutputStream) {
        try {
            val parsed_pkt = IpV4Packet.newPacket(packet, 0, packet.size)
            // Log.i(TAG, "PARSED_PACKET = " + parsed_pkt)

            val dns_data = (parsed_pkt.payload as UdpPacket).payload.rawData
            val msg = Message(dns_data)
            val dns_query_name = msg.question.name.toString(true)
            // Log.i(TAG, "DNS Name = " + dns_query_name)

            val response: ByteArray
            Log.i(TAG, "DNS Name = $dns_query_name")

            if (!mBlockedHosts!!.contains(dns_query_name)) {
                Log.i(TAG, "    PERMITTED!")
                val out_pkt = DatagramPacket(dns_data, 0, dns_data.size, mDnsServer!!, 53)
                Log.i(TAG, "SENDING TO REAL DNS SERVER!")
                try {
                    dnsSocket.send(out_pkt)
                } catch (e: ErrnoException) {
                    if (e.errno == OsConstants.ENETUNREACH) {
                        throw VpnNetworkException("Network unreachable, can't send DNS packet")
                    } else {
                        throw e
                    }
                }
                Log.i(TAG, "RECEIVING FROM REAL DNS SERVER!")

                val datagram_data = ByteArray(1024)
                val reply_pkt = DatagramPacket(datagram_data, datagram_data.size)
                dnsSocket.receive(reply_pkt)
                // Log.i(TAG, "IN = " + reply_pkt)
                // Log.i(TAG, "adderess = " + reply_pkt.address + " port = " + reply_pkt.port)
                // logPacket(datagram_data)
                response = datagram_data
            } else {
                Log.i(TAG, "    BLOCKED!")
                msg.header.setFlag(Flags.QR.toInt())
                msg.addRecord(ARecord(msg.question.name,
                        msg.question.dClass,
                        10.toLong(),
                        Inet4Address.getLocalHost()), Section.ANSWER)
                response = msg.toWire()
            }


            val udp_packet = parsed_pkt.payload as UdpPacket
            val out_packet = IpV4Packet.Builder(parsed_pkt)
                    .srcAddr(parsed_pkt.header.dstAddr)
                    .dstAddr(parsed_pkt.header.srcAddr)
                    .correctChecksumAtBuild(true)
                    .correctLengthAtBuild(true)
                    .payloadBuilder(
                            UdpPacket.Builder(udp_packet)
                                    .srcPort(udp_packet.header.dstPort)
                                    .dstPort(udp_packet.header.srcPort)
                                    .srcAddr(parsed_pkt.header.dstAddr)
                                    .dstAddr(parsed_pkt.header.srcAddr)
                                    .correctChecksumAtBuild(true)
                                    .correctLengthAtBuild(true)
                                    .payloadBuilder(
                                            UnknownPacket.Builder()
                                                    .rawData(response)
                                    )
                    ).build()

            Log.i(TAG, "WRITING PACKET!" )
            try {
                outFd.write(out_packet.rawData)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EBADF) {
                    throw VpnNetworkException("Outgoing VPN socket closed")
                } else {
                    throw e
                }
            }
        } catch (e: VpnNetworkException) {
            Log.w(TAG, "Ignoring exception, stopping thread", e)
        } catch (e: Exception) {
            Log.e(TAG, "Got exception", e)
            ExceptionHandler.saveException(e, Thread.currentThread(), null)
        } finally {
            dnsSocket.close()
            outFd.close()
        }

    }

    private fun getDnsServers() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Seriously, Android? Seriously?
        val activeInfo = cm.activeNetworkInfo ?: throw VpnNetworkException("No DNS Server")
        val servers = cm.allNetworks.filter { val ni = cm.getNetworkInfo(it);
                    ni != null && ni.isConnected && ni.type == activeInfo.type && ni.subtype == activeInfo.subtype
                }.elementAtOrNull(0)?.let { cm.getLinkProperties(it).dnsServers }
        mDnsServer = servers?.first() ?: throw VpnNetworkException("No DNS Server")
        Log.i(TAG, "Got DNS server = $mDnsServer")
    }

    private fun loadBlockedHosts() {
        // Don't load the hosts more than once (temporary til we have dynamic lists)
        if (mBlockedHosts != null) {
            Log.i(TAG, "Block list already loaded")
            return
        }

        Log.i(TAG, "Loading block list")
        val blockedHosts : MutableSet<String> = mutableSetOf()

        for (fileName in listOf("adaway_hosts.txt", "ad_servers.txt")) {
            val reader = assets.open(fileName)
            var count = 0
            try {
                InputStreamReader(reader.buffered()).forEachLine {
                    val s = it.removeSurrounding(" ")
                    if (s.length != 0 && s[0] != '#') {
                        val split = s.split(" ", "\t")
                        if (split.size == 2 && split[0] == "127.0.0.1") {
                            count += 1
                            blockedHosts.add(split[1].toLowerCase())
                        }
                    }
                }
            } finally {
                reader.close()
            }

            Log.i(TAG, "From file $fileName loaded $count  entires")
        }

        mBlockedHosts = blockedHosts
        Log.i(TAG, "Loaded ${mBlockedHosts!!.size} blocked hosts")
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

    @Throws(Exception::class)
    private fun configure(): ParcelFileDescriptor {

        Log.i(TAG, "Configuring")

        // Get the current DNS servers before starting the VPN
        getDnsServers()

        // Configure a builder while parsing the parameters.
        // TODO: Make this dynamic
        val builder = this.Builder()
        builder.addAddress("192.168.50.1", 24)
        builder.addDnsServer("192.168.50.5")
        builder.addRoute("192.168.50.0", 24)
        builder.setBlocking(true)

        // Create a new interface using the builder and save the parameters.
        val pfd = builder.setSession("@@AdBlockVpn").setConfigureIntent(
                PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_CANCEL_CURRENT)
            ).establish()
        Log.i(TAG, "Configured")
        return pfd
    }
}
