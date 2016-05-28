package app.adbuster

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Handler
import android.os.Message
import android.os.ParcelFileDescriptor
import android.support.v4.app.NotificationCompat
import android.util.Log
import android.widget.Toast
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

class VpnService : VpnService(), Handler.Callback, Runnable {
    companion object {
        private val TAG = "VpnService"
    }

    // TODO: There must be a better way in kotlin to do this
    private val commandValue = mapOf(
        Pair(Command.START.ordinal, Command.START),
        Pair(Command.STOP.ordinal, Command.STOP)
    )

    private var mHandler: Handler? = null
    private var mThread: Thread? = null
    private var mNotification: Notification? = null
    private var mInterface: ParcelFileDescriptor? = null
    private var m_in_fd: InterruptibleFileInputStream? = null

    private var mDnsServers: List<InetAddress> = listOf()
    private var mBlockedHosts: Set<String> = setOf()

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand")
        when (commandValue[intent.getIntExtra("COMMAND", Command.START.ordinal)]) {
            Command.START -> startVpn(intent.getParcelableExtra<PendingIntent>("NOTIFICATION_INTENT"))
            Command.STOP -> stopVpn()
        }

        return Service.START_STICKY
    }

    private fun startVpn(notificationIntent: PendingIntent) {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = Handler(this)
        }

        mNotification = NotificationCompat.Builder(this)
            .setSmallIcon(R.drawable.ic_vpn_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_content))
            .setContentIntent(notificationIntent)
            .build()

        startForeground(10, mNotification)

        // Stop the previous session by interrupting the thread.
        mThread?.interrupt()

        // Start a new session by creating a new thread.
        mThread = Thread(this, "AdBusterThread")
        mThread?.start()
    }

    private fun stopVpn() {
        Log.i(TAG, "Stopping VPN!")
        m_in_fd!!.interrupt()
        stopSelf()
    }


    override fun onDestroy() {
        Log.i(TAG, "Destroyed, shutting down")
        stopVpn()
    }

    override fun handleMessage(message: Message?): Boolean {
        if (message != null) {
            Toast.makeText(this, message.what, Toast.LENGTH_SHORT).show()
        }
        return true
    }

    @Synchronized override fun run() {
        try {
            Log.i(TAG, "Starting")

            // Load the block list
            loadBlockedHosts()

            // Get the current DNS servers before starting the VPN
            getDnsServers()

            mHandler!!.sendEmptyMessage(R.string.connecting)

            runVpn()

            Log.i(TAG, "Stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Exception in run() ", e)
        } finally {
            mHandler!!.sendEmptyMessage(R.string.disconnected)
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun runVpn() {
        // Authenticate and configure the virtual network interface.
        val pfd = configure()
        mInterface = pfd

        Log.i(TAG, "FD = " + mInterface!!.fd)

        try {
            // Now we are connected. Set the flag and show the message.
            mHandler!!.sendEmptyMessage(R.string.connected)

            // Packets to be sent are queued in this input stream.
            m_in_fd = InterruptibleFileInputStream(pfd.fileDescriptor)

            // Allocate the buffer for a single packet.
            val packet = ByteArray(32767)

            // Like this `Executors.newCachedThreadPool()`, except with an upper limit
            val executor = ThreadPoolExecutor(0, 16, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                Log.i(TAG, "WAITING FOR PACKET!")
                val length = m_in_fd!!.read(packet)
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

                // Start a new thread to handle the DNS request
                executor.execute {
                    handleDnsRequest(read_packet, dns_socket, out_fd)
                }
            }
        } catch (e: InterruptibleFileInputStream.InterruptedStreamException) {
            Log.i(TAG, "Told to stop VPN")
        } catch (e: Exception) {
            Log.e(TAG, "Got Exception", e)
            throw e
        } finally {
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

            if (!mBlockedHosts.contains(dns_query_name)) {
                Log.i(TAG, "    PERMITTED!")
                val out_pkt = DatagramPacket(dns_data, 0, dns_data.size, mDnsServers[0], 53)
                Log.i(TAG, "SENDING TO REAL DNS SERVER!")
                dnsSocket.send(out_pkt)
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
            outFd.write(out_packet.rawData)
        } catch (e: Exception) {
            Log.e(TAG, "Got expcetion", e)
        } finally {
            dnsSocket.close()
            outFd.close()
        }

    }

    private fun getDnsServers() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        // Seriously, Android? Seriously?
        val activeInfo = cm.activeNetworkInfo
        mDnsServers = cm.getLinkProperties(
                cm.allNetworks.filter { val ni = cm.getNetworkInfo(it);
                    ni.isConnected && ni.type == activeInfo.type && ni.subtype == activeInfo.subtype
                }.first()
        ).dnsServers
        Log.i(TAG, "Got DNS servers = $mDnsServers")
    }

    private fun loadBlockedHosts() {
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
        Log.i(TAG, "Loaded ${mBlockedHosts.size} blocked hosts")
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
