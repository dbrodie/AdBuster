package com.example.android.toyvpn

fun toHexString(ba: ByteArray) : String {
    val str = StringBuilder()
    for(c in ba) {
        str.append(String.format("%x", c))
    }
    return str.toString()
}