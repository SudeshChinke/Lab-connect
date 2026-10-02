package com.labconnect.android.network

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.util.Log

class NetworkChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        when (action) {
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = cm.activeNetwork
                val capabilities = activeNetwork?.let { cm.getNetworkCapabilities(it) }
                val hasInternet = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                
                Log.d("NetworkChangeReceiver", "Connectivity changed: hasInternet=$hasInternet")
                
                // Broadcast to app
                val broadcastIntent = Intent("com.labconnect.NETWORK_CHANGED")
                broadcastIntent.putExtra("hasInternet", hasInternet)
                broadcastIntent.putExtra("network", activeNetwork)
                context.sendBroadcast(broadcastIntent)
            }
            WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                val wifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, WifiManager.WIFI_STATE_UNKNOWN)
                Log.d("NetworkChangeReceiver", "Wi-Fi state changed: $wifiState")
                
                val broadcastIntent = Intent("com.labconnect.WIFI_STATE_CHANGED")
                broadcastIntent.putExtra("wifiState", wifiState)
                context.sendBroadcast(broadcastIntent)
            }
            WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                val networkInfo = intent.getParcelableExtra<android.net.NetworkInfo>(WifiManager.EXTRA_NETWORK_INFO)
                Log.d("NetworkChangeReceiver", "Network state changed: ${networkInfo?.state}")
                
                val broadcastIntent = Intent("com.labconnect.WIFI_NETWORK_CHANGED")
                broadcastIntent.putExtra("networkState", networkInfo?.state?.name)
                context.sendBroadcast(broadcastIntent)
            }
        }
    }
}