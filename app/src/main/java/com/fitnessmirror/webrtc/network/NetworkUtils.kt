package com.fitnessmirror.webrtc.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    fun getLocalIpAddress(context: Context): String? {
        return try {
            // Try WiFi first
            getWifiIpAddress(context) ?: getNetworkInterfaceIpAddress()
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting IP address", ex)
            null
        }
    }

    private fun getWifiIpAddress(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifiInfo = wifiManager.connectionInfo
        val ipInt = wifiInfo.ipAddress
        
        return if (ipInt != 0) {
            String.format(
                "%d.%d.%d.%d",
                (ipInt and 0xff),
                (ipInt shr 8 and 0xff),
                (ipInt shr 16 and 0xff),
                (ipInt shr 24 and 0xff)
            )
        } else {
            null
        }
    }

    private fun getNetworkInterfaceIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                if (intf.name.contains("wlan") || intf.name.contains("eth")) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                            return addr.hostAddress
                        }
                    }
                }
            }
        } catch (ex: Exception) {
            Log.e(TAG, "Error getting network interface IP", ex)
        }
        return null
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return false
        
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> true
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> true
            else -> false
        }
    }

    fun getConnectionType(context: Context): ConnectionType {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return ConnectionType.NONE
        val activeNetwork = connectivityManager.getNetworkCapabilities(network) ?: return ConnectionType.NONE
        
        return when {
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.WIFI
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.ETHERNET
            activeNetwork.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.CELLULAR
            else -> ConnectionType.UNKNOWN
        }
    }

    enum class ConnectionType {
        WIFI, ETHERNET, CELLULAR, UNKNOWN, NONE
    }

    fun formatServerAddress(ipAddress: String?, port: Int): String {
        return if (ipAddress != null) {
            "http://$ipAddress:$port"
        } else {
            "No network connection"
        }
    }

    fun isValidPort(port: Int): Boolean {
        return port in 1024..65535
    }
}