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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

class TransferService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var activeTransfers = 0
    private val NOTIFICATION_ID = 1002
    private val CHANNEL_ID = "transfer_channel"
    
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent.action == "START_TRANSFER") {
            startTransfer(intent.getStringExtra("file_path")!!, intent.getStringExtra("target_device")!!)
        } else if (intent.action == "CANCEL_TRANSFER") {
            cancelTransfer(intent.getStringExtra("transfer_id")!!)
        }
        return START_STICKY
    }
    
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
    
    override fun onBind(intent: Intent): IBinder? = null
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LabConnect Transfers",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File transfer progress notifications"
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun startTransfer(filePath: String, targetDevice: String) {
        activeTransfers++
        updateNotification()
        // TODO: Implement actual transfer
    }
    
    private fun cancelTransfer(transferId: String) {
        // TODO: Implement cancel
    }
    
    private fun updateNotification() {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LabConnect Transfers")
            .setContentText(if (activeTransfers > 0) "$activeTransfers active transfer(s)" else "No active transfers")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(activeTransfers > 0)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
        
        if (activeTransfers == 0) {
            stopForeground(true)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
