package com.labconnect.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

class DiscoveryManager(
    private val context: Context,
    private val localDeviceId: String,
    private val localDeviceName: String,
    private val localTcpPort: Int,
    private val localPublicKey: String
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val discoveredDevices = java.util.concurrent.ConcurrentHashMap<String, DiscoveredDevice>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<DiscoveredDevice>) -> Unit>()
    private var multicastSocket: MulticastSocket? = null
    private var broadcastSocket: DatagramSocket? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val isRunning = AtomicBoolean(false)
    private val json = Json { ignoreUnknownKeys = true }
    private var currentNetwork: Network? = null
    
    data class DiscoveredDevice(
        val deviceId: String,
        val deviceName: String,
        val deviceType: String,
        val protocolVersion: String,
        val ipAddress: String,
        val tcpPort: Int,
        val capabilities: List<String>,
        val publicKey: String,
        val lastSeen: Long = System.currentTimeMillis()
    )
    
    fun start() {
        if (isRunning.getAndSet(true)) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("LabConnect:discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        setupNetworkMonitoring()
        startDiscovery()
    }
    
    fun stop() {
        isRunning.set(false)
        scope.cancel()
        multicastSocket?.close()
        broadcastSocket?.close()
        multicastSocket = null
        broadcastSocket = null
        
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = networkCallback
        if (callback != null) cm.unregisterNetworkCallback(callback)
        networkCallback = null
        currentNetwork = null
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }
    
    fun addListener(listener: (List<DiscoveredDevice>) -> Unit) {
        listeners.add(listener)
        listener(discoveredDevices.values.toList())
    }
    
    fun removeListener(listener: (List<DiscoveredDevice>) -> Unit) {
        listeners.remove(listener)
    }
    
    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        return discoveredDevices.values.toList()
    }
    
    private fun setupNetworkMonitoring() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                currentNetwork = network
                Log.d("DiscoveryManager", "Network available: $network")
                restartDiscovery()
            }
            
            override fun onLost(network: Network) {
                if (currentNetwork == network) {
                    currentNetwork = null
                    Log.d("DiscoveryManager", "Network lost: $network")
                    discoveredDevices.clear()
                    notifyListeners()
                }
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }
    
    private fun restartDiscovery() {
        stopSockets()
        startSockets()
    }
    
    private fun startDiscovery() {
        startSockets()
        scope.launch { announceLoop() }
        scope.launch { listenLoop() }
        scope.launch { cleanupLoop() }
    }
    
    private fun startSockets() {
        try {
            // Multicast socket
            multicastSocket = MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.DISCOVERY_PORT))
                soTimeout = 1000
                val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
                val networkInterface = NetworkInterface.getNetworkInterfaces().toList()
                    .firstOrNull { it.isUp && it.supportsMulticast() && it.inetAddresses.toList().any { addr -> !addr.isLoopbackAddress } }
                    ?: throw IllegalStateException("No multicast-capable network interface")
                setNetworkInterface(networkInterface)
                joinGroup(InetSocketAddress(group, Protocol.DISCOVERY_PORT), networkInterface)
                setTimeToLive(2)
            }
            
            // Broadcast socket
            broadcastSocket = DatagramSocket(0).apply {
                setBroadcast(true)
                setReuseAddress(true)
            }
            
            Log.d("DiscoveryManager", "Discovery sockets started")
        } catch (e: Exception) {
            Log.e("DiscoveryManager", "Failed to start sockets", e)
        }
    }
    
    private fun stopSockets() {
        multicastSocket?.close()
        broadcastSocket?.close()
        multicastSocket = null
        broadcastSocket = null
    }
    
    private suspend fun announceLoop() {
        val announcement = DeviceAnnounce(
            deviceId = localDeviceId,
            deviceName = localDeviceName,
            deviceType = Protocol.DEVICE_TYPE_MOBILE,
            tcpPort = localTcpPort,
            publicKey = localPublicKey
        )
        
        val jsonData = Json { ignoreUnknownKeys = true; encodeDefaults = true }.encodeToString(announcement)
        val data = jsonData.toByteArray()
        
        while (isRunning.get()) {
            try {
                // Send multicast
                multicastSocket?.let { socket ->
                    val group = InetAddress.getByName(Protocol.MULTICAST_GROUP)
                    val packet = DatagramPacket(data, data.size, group, Protocol.DISCOVERY_PORT)
                    socket.send(packet)
                }
                
                // Send broadcast
                broadcastSocket?.let { socket ->
                    val broadcastAddr = InetAddress.getByName(Protocol.BROADCAST_ADDRESS)
                    val packet = DatagramPacket(data, data.size, broadcastAddr, Protocol.DISCOVERY_PORT)
                    socket.send(packet)
                }
            } catch (e: Exception) {
                Log.e("DiscoveryManager", "Announce error", e)
            }
            
            delay(Protocol.ANNOUNCE_INTERVAL_MS.toLong())
        }
    }
    
    private fun listenLoop() {
        val buffer = ByteArray(4096)
        
        while (isRunning.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                // Try multicast first
                multicastSocket?.receive(packet)
                processPacket(packet)
                
            } catch (e: SocketTimeoutException) {
                // Try broadcast socket
                try {
                    broadcastSocket?.setSoTimeout(1000)
                    broadcastSocket?.receive(packet)
                    processPacket(packet)
                } catch (e2: SocketTimeoutException) {
                    // No data, continue
                } catch (e2: Exception) {
                    if (isRunning.get()) Log.e("DiscoveryManager", "Broadcast receive error", e2)
                }
            } catch (e: Exception) {
                if (isRunning.get()) Log.e("DiscoveryManager", "Listen error", e)
            }
        }
    }
    
    private fun processPacket(packet: DatagramPacket) {
        try {
            val jsonStr = String(packet.data, 0, packet.length, Charsets.UTF_8)
            val announcement = Json { ignoreUnknownKeys = true }.decodeFromString(DeviceAnnounce.serializer(), jsonStr)
            
            // Ignore our own announcements
            if (announcement.deviceId == localDeviceId) return
            
            val senderIp = packet.address?.hostAddress ?: return
            
            val device = DiscoveredDevice(
                deviceId = announcement.deviceId,
                deviceName = announcement.deviceName,
                deviceType = announcement.deviceType,
                protocolVersion = announcement.protocolVersion,
                ipAddress = senderIp,
                tcpPort = announcement.tcpPort,
                capabilities = announcement.capabilities,
                publicKey = announcement.publicKey,
                lastSeen = System.currentTimeMillis()
            )
            
            discoveredDevices[device.deviceId] = device
            notifyListeners()
            
        } catch (e: Exception) {
            Log.d("DiscoveryManager", "Failed to parse announcement", e)
        }
    }
    
    private suspend fun cleanupLoop() {
        while (isRunning.get()) {
            delay(10000)
            val now = System.currentTimeMillis()
            val toRemove = discoveredDevices.filter { now - it.value.lastSeen > 30000 }.keys
            toRemove.forEach { discoveredDevices.remove(it) }
            if (toRemove.isNotEmpty()) notifyListeners()
        }
    }
    
    private fun notifyListeners() {
        val devices = discoveredDevices.values.toList()
        listeners.forEach { it(devices) }
    }
    
}
