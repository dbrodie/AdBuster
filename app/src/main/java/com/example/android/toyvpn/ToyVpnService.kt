/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.toyvpn

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.VpnService
import android.os.Handler
import android.os.Message
import android.os.ParcelFileDescriptor
import android.util.Log
import android.widget.Toast

import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel

class ToyVpnService : VpnService(), Handler.Callback, Runnable {

    private var mServerAddress: String? = null
    private var mServerPort: String? = null
    private var mSharedSecret: ByteArray? = null
    private val mConfigureIntent: PendingIntent? = null

    private var mHandler: Handler? = null
    private var mThread: Thread? = null

    private var mInterface: ParcelFileDescriptor? = null
    private var mParameters: String? = null

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        // The handler is only used to show messages.
        if (mHandler == null) {
            mHandler = Handler(this)
        }

        // Stop the previous session by interrupting the thread.
        mThread?.interrupt()

        // Start a new session by creating a new thread.
        mThread = Thread(this, "ToyVpnThread")
        mThread?.start()
        return Service.START_STICKY
    }

    override fun onDestroy() {
        mThread?.interrupt()
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

            // If anything needs to be obtained using the network, get it now.
            // This greatly reduces the complexity of seamless handover, which
            // tries to recreate the tunnel without shutting down everything.
            // In this demo, all we need to know is the server address.
//            val server = InetSocketAddress(
//                    mServerAddress, Integer.parseInt(mServerPort))

            // We try to create the tunnel for several times. The better way
            // is to work with ConnectivityManager, such as trying only when
            // the network is avaiable. Here we just use a counter to keep
            // things simple.
            var attempt = 0
//            while (attempt < 10) {
                mHandler!!.sendEmptyMessage(R.string.connecting)

                runVpn()

                // Reset the counter if we were connected.
//                if (run(server)) {
//                    attempt = 0
//                }

                // Sleep for a while. This also checks if we got interrupted.
                Thread.sleep(3000)
                ++attempt
//            }
            Log.i(TAG, "Giving up")
        } catch (e: Exception) {
            Log.e(TAG, "Got " + e.toString())
        } finally {
            try {
                mInterface!!.close()
            } catch (e: Exception) {
                // ignore
            }

            mInterface = null
            mParameters = null

            mHandler!!.sendEmptyMessage(R.string.disconnected)
            Log.i(TAG, "Exiting")
        }
    }

    @Throws(Exception::class)
    private fun runVpn(): Boolean {
        var connected = false

//        val tunnel = DatagramChannel.open()

        try {
//            // Protect the tunnel before connecting to avoid loopback.
//            if (!protect(tunnel.socket())) {
//                throw IllegalStateException("Cannot protect the tunnel")
//            }
//
//            // Connect to the server.
//            tunnel.connect(server)
//
//            // For simplicity, we use the same thread for both reading and
//            // writing. Here we put the tunnel into non-blocking mode.
//            tunnel.configureBlocking(false)

            // Authenticate and configure the virtual network interface.
            configure()

            // Now we are connected. Set the flag and show the message.
//            connected = true
            mHandler!!.sendEmptyMessage(R.string.connected)

            // Packets to be sent are queued in this input stream.
            val in_fd = FileInputStream(mInterface!!.fileDescriptor)

            // Packets received need to be written to this output stream.
            val out_fd = FileOutputStream(mInterface!!.fileDescriptor)

            // Allocate the buffer for a single packet.
            val packet = ByteBuffer.allocate(32767)

            // We use a timer to determine the status of the tunnel. It
            // works on both sides. A positive value means sending, and
            // any other means receiving. We start with receiving.
            var timer = 0

            // We keep forwarding packets till something goes wrong.
            while (true) {
                // Assume that we did not make any progress in this iteration.
                var idle = true

                // Read the outgoing packet from the input stream.
                var length = in_fd.read(packet.array())
                if (length > 0) {
                    // Write the outgoing packet to the tunnel.
                    packet.limit(length)
                    Log.i(TAG, "Got packet!!")
                    logPacket(packet)
                    packet.clear()

                    // There might be more outgoing packets.
                    idle = false

                    // If we were receiving, switch to sending.
                    if (timer < 1) {
                        timer = 1
                    }
                }
//
//                // Read the incoming packet from the tunnel.
//                length = tunnel.read(packet)
//                if (length > 0) {
//                    // Ignore control messages, which start with zero.
//                    if (packet.get(0).toInt() != 0) {
//                        // Write the incoming packet to the output stream.
//                        out_fd.write(packet.array(), 0, length)
//                    }
//                    packet.clear()
//
//                    // There might be more incoming packets.
//                    idle = false
//
//                    // If we were sending, switch to receiving.
//                    if (timer > 0) {
//                        timer = 0
//                    }
//                }

//                // If we are idle or waiting for the network, sleep for a
//                // fraction of time to avoid busy looping.
//                if (idle) {
//                    Thread.sleep(100)
//
//                    // Increase the timer. This is inaccurate but good enough,
//                    // since everything is operated in non-blocking mode.
//                    timer += if (timer > 0) 100 else -100
//
//                    // We are receiving for a long time but not sending.
//                    if (timer < -15000) {
//                        // Send empty control messages.
//                        packet.put(0.toByte()).limit(1)
//                        for (i in 0..2) {
//                            packet.position(0)
//                            tunnel.write(packet)
//                        }
//                        packet.clear()
//
//                        // Switch to sending.
//                        timer = 1
//                    }
//
//                    // We are sending for a long time but not receiving.
//                    if (timer > 20000) {
//                        throw IllegalStateException("Timed out")
//                    }
//                }
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Got " + e.toString())
        } finally {
            try {
//                tunnel.close()
            } catch (e: Exception) {
                // ignore
            }

        }
        return connected
    }

    private fun logPacket(packet: ByteBuffer) {
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
    private fun configure() {

        Log.i(TAG, "Configuring")

        // Configure a builder while parsing the parameters.
        val builder = this.Builder()
        builder.addAddress("192.168.50.1", 24)
        builder.addDnsServer("192.168.50.5")
        builder.addRoute("192.168.50.0", 24)

        // Close the old interface since the parameters have been changed.
        try {
            mInterface?.close()
        } catch (e: Exception) {
            // ignore
        }

        // Create a new interface using the builder and save the parameters.
        mInterface = builder.setSession("BLAHBLAH").setConfigureIntent(mConfigureIntent).establish()
        Log.i(TAG, "Configured")
    }

    companion object {
        private val TAG = "ToyVpnService"
    }
}
