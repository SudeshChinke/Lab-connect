package com.labconnect.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.labconnect.android.R
import com.labconnect.android.network.DiscoveryManager
import com.labconnect.android.network.Protocol
import com.labconnect.android.security.AndroidIdentityStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DiscoveryService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val identity by lazy { AndroidIdentityStore(this).loadOrCreate() }
    private var discoveryManager: DiscoveryManager? = null
    private val NOTIFICATION_ID = 1001
    private val CHANNEL_ID = "discovery_channel"
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        
        scope.launch {
            discoveryManager = DiscoveryManager(
                context = this@DiscoveryService,
                localDeviceId = getLocalDeviceId(),
                localDeviceName = getLocalDeviceName(),
                localTcpPort = Protocol.TCP_PORT,
                localPublicKey = getLocalPublicKey()
            )
            discoveryManager?.start()
        }
    }
    
    override fun onDestroy() {
        discoveryManager?.stop()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LabConnect Discovery",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Discovers nearby LabConnect devices"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LabConnect Discovery")
            .setContentText(getString(R.string.discovery_service_running))
            .setSmallIcon(android.R.drawable.sym_def_app_icon)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    private fun getLocalDeviceId(): String {
        return identity.deviceId
    }
    
    private fun getLocalDeviceName(): String {
        return getSharedPreferences("labconnect_prefs", MODE_PRIVATE)
            .getString("device_name", "Phone-${(1000..9999).random()}")!!
    }
    
    private fun getLocalPublicKey(): String {
        return identity.publicKeyBase64
    }
}
