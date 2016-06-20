package app.adbuster

import android.util.Log
import java.nio.ByteBuffer

fun toHexString(ba: ByteArray) : String {
    val str = StringBuilder()
    for(c in ba) {
        str.append(String.format("%x", c))
    }
    return str.toString()
}

private fun logPacket(TAG: String, packet: ByteArray) = logPacket(TAG, packet, 0, packet.size)

private fun logPacket(TAG: String, packet: ByteArray, size: Int) = logPacket(TAG, packet, 0, size)

private fun logPacket(TAG: String, packet: ByteArray, offset: Int, size: Int) {
    var logLine = "PACKET: <"
    for (index in (offset..(size-1))) {
        logLine += String.format("%02x", packet[index])
    }

    Log.i(TAG, logLine + ">")
}

private fun logPacketNice(TAG: String, packet: ByteBuffer) {
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