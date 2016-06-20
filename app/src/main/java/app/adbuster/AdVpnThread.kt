package app.adbuster

import android.app.PendingIntent
import android.content.Intent
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import net.hockeyapp.android.ExceptionHandler
import org.pcap4j.packet.IpV4Packet
import org.pcap4j.packet.UdpPacket
import org.pcap4j.packet.UnknownPacket
import org.xbill.DNS.ARecord
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Section
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val TAG = "AdVpnThread"

class AdVpnThread(vpnService: AdVpnService): Runnable {
    private var vpnService = vpnService
    private var dnsServer: InetAddress? = null
    private var vpnFileDescriptor: ParcelFileDescriptor? = null
    private var thread: Thread? = null
    private var interruptible: InterruptibleFileInputStream? = null

    fun startThread() {
        Log.i(TAG, "Starting Vpn Thread")
        thread = Thread(this, "AdBusterVpnThread").apply { start() }
        Log.i(TAG, "Vpn Thread started")
    }

    fun stopThread() {
        Log.i(TAG, "Stopping Vpn Thread")
        thread?.interrupt()
        interruptible?.interrupt()
        thread?.join(2000)
        if (thread?.isAlive ?: false) {
            Log.w(TAG, "Couldn't kill Vpn Thread")
        }
        thread = null
        Log.i(TAG, "Vpn Thread stopped")

    }

    @Synchronized override fun run() {
        try {
            Log.i(TAG, "Starting")

            // Load the block list
            loadBlockedHosts()

            vpnService.mHandler!!.sendMessage(vpnService.mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_STARTING, 0))

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
                    vpnService.mHandler!!.sendMessage(vpnService.mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RECONNECTING_NETWORK_ERROR, 0))
                } catch (e: Exception) {
                    Log.e(TAG, "Network exception in vpn thread, reconnecting", e)
                    ExceptionHandler.saveException(e, Thread.currentThread(), null)
                    vpnService.mHandler!!.sendMessage(vpnService.mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RECONNECTING_NETWORK_ERROR, 0))
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
            vpnService.mHandler!!.sendMessage(vpnService.mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_STOPPING, 0))
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun runVpn() {
        // Authenticate and configure the virtual network interface.
        val pfd = configure()
        vpnFileDescriptor = pfd

        Log.i(TAG, "FD = " + vpnFileDescriptor!!.fd)

        // Packets to be sent are queued in this input stream.
        val inputStream = InterruptibleFileInputStream(pfd.fileDescriptor)
        interruptible = inputStream

        // Allocate the buffer for a single packet.
        val packet = ByteArray(32767)

        // Like this `Executors.newCachedThreadPool()`, except with an upper limit
        val executor = ThreadPoolExecutor(0, 32, 60L, TimeUnit.SECONDS, SynchronousQueue<Runnable>())

        try {
            // Now we are connected. Set the flag and show the message.
            vpnService.mHandler!!.sendMessage(vpnService.mHandler!!.obtainMessage(VPN_MSG_STATUS_UPDATE, VPN_STATUS_RUNNING, 0))

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Read the outgoing packet from the input stream.
                Log.i(TAG, "WAITING FOR PACKET!")
                val length: Int
                try {
                    length = inputStream.read(packet)
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
                vpnService.protect(dns_socket)

                Log.i(TAG, "Starting new thread to handle dns request")
                Log.i(TAG, "Executing: ${executor.activeCount}")
                Log.i(TAG, "Backlog: ${executor.queue.size}")
                // Start a new thread to handle the DNS request
                try {
                    executor.execute {
                        handleDnsRequest(read_packet, dns_socket, out_fd)
                    }
                } catch (e: RejectedExecutionException) {
                    VpnNetworkException("High backlog in dns thread pool executor, network probably stalled")
                }
            }
        } finally {
            executor.shutdownNow()
            pfd.close()
            vpnFileDescriptor = null
        }
    }

    private fun handleDnsRequest(packet: ByteArray, dnsSocket: DatagramSocket, outFd: FileOutputStream) {
        try {
            val parsed_pkt = IpV4Packet.newPacket(packet, 0, packet.size)
            // Log.i(TAG, "PARSED_PACKET = " + parsed_pkt)

            if (parsed_pkt.payload !is UdpPacket) {
                Log.i(TAG, "Ignoring Unknown packet ${parsed_pkt.payload}")
                return
            }

            val dns_data = (parsed_pkt.payload as UdpPacket).payload.rawData
            val msg = Message(dns_data)
            if (msg.question == null) {
                Log.i(TAG, "Ignoring DNS packet with no query $msg")
                return
            }
            val dns_query_name = msg.question.name.toString(true)
            // Log.i(TAG, "DNS Name = " + dns_query_name)

            val response: ByteArray
            Log.i(TAG, "DNS Name = $dns_query_name")

            if (!vpnService.mBlockedHosts!!.contains(dns_query_name)) {
                Log.i(TAG, "    PERMITTED!")
                val out_pkt = DatagramPacket(dns_data, 0, dns_data.size, dnsServer!!, 53)
                Log.i(TAG, "SENDING TO REAL DNS SERVER!")
                try {
                    dnsSocket.send(out_pkt)
                } catch (e: ErrnoException) {
                    if ((e.errno == OsConstants.ENETUNREACH) || (e.errno == OsConstants.EPERM)) {
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
            } catch (e: IOException) {
                // TODO: Make this more specific, only for: "File descriptor closed"
                throw VpnNetworkException("Outgoing VPN output stream closed")
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
        val cm = vpnService.getSystemService(VpnService.CONNECTIVITY_SERVICE) as ConnectivityManager
        // Seriously, Android? Seriously?
        val activeInfo = cm.activeNetworkInfo ?: throw VpnNetworkException("No DNS Server")
        val servers = cm.allNetworks.filter { val ni = cm.getNetworkInfo(it);
            ni != null && ni.isConnected && ni.type == activeInfo.type && ni.subtype == activeInfo.subtype
        }.elementAtOrNull(0)?.let { cm.getLinkProperties(it).dnsServers }
        dnsServer = servers?.first() ?: throw VpnNetworkException("No DNS Server")
        Log.i(TAG, "Got DNS server = $dnsServer")
    }

    private fun loadBlockedHosts() {
        // Don't load the hosts more than once (temporary til we have dynamic lists)
        if (vpnService.mBlockedHosts != null) {
            Log.i(TAG, "Block list already loaded")
            return
        }

        Log.i(TAG, "Loading block list")
        val blockedHosts : MutableSet<String> = mutableSetOf()

        for (fileName in listOf("adaway_hosts.txt", "ad_servers.txt")) {
            val reader = vpnService.assets.open(fileName)
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

        vpnService.mBlockedHosts = blockedHosts
        Log.i(TAG, "Loaded ${vpnService.mBlockedHosts!!.size} blocked hosts")
    }

    @Throws(Exception::class)
    private fun configure(): ParcelFileDescriptor {

        Log.i(TAG, "Configuring")

        // Get the current DNS servers before starting the VPN
        getDnsServers()

        // Configure a builder while parsing the parameters.
        // TODO: Make this dynamic
        val builder = vpnService.Builder()
        builder.addAddress("192.168.50.1", 24)
        builder.addDnsServer("192.168.50.5")
        builder.addRoute("192.168.50.0", 24)
        builder.setBlocking(true)

        // Create a new interface using the builder and save the parameters.
        val pfd = builder
                .setSession("Ad Buster")
                .setConfigureIntent(
                        PendingIntent.getActivity(vpnService, 1, Intent(vpnService, MainActivity::class.java),
                                PendingIntent.FLAG_CANCEL_CURRENT)
                ).establish()
        Log.i(TAG, "Configured")
        return pfd
    }

}