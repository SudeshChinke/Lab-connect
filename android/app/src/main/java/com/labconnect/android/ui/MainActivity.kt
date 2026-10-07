package com.labconnect.android.ui

import android.os.Bundle
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labconnect.android.network.ConnectionManager
import com.labconnect.android.network.DiscoveryManager
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val discoveryManager by lazy { createDiscoveryManager() }
    private val connectionManager by lazy { createConnectionManager() }
    private var discoveryStarted = false
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) startDiscoveryIfAllowed()
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabConnectTheme {
                MainScreen(viewModel = viewModel, discoveryManager = discoveryManager)
            }
        }
        startDiscoveryIfAllowed()
    }
    
    override fun onDestroy() {
        if (discoveryStarted) discoveryManager.stop()
        connectionManager.shutdown()
        super.onDestroy()
    }

    private fun startDiscoveryIfAllowed() {
        val requested = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (requested.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (!discoveryStarted) {
                discoveryManager.start()
                discoveryStarted = true
            }
        } else {
            permissionLauncher.launch(requested)
        }
    }

    private fun createDiscoveryManager(): DiscoveryManager {
        val prefs = getSharedPreferences("labconnect_prefs", MODE_PRIVATE)
        val localDeviceId = prefs.getString("device_id", null)
            ?: "ANDROID-${Random.nextInt(10000, 99999)}".also { prefs.edit().putString("device_id", it).apply() }
        val localDeviceName = prefs.getString("device_name", "Phone-${Random.nextInt(1000, 9999)}")!!
        val publicKey = prefs.getString("public_key", null)
            ?: "android-key-${Random.nextInt(10000)}".also { prefs.edit().putString("public_key", it).apply() }
        return DiscoveryManager(this, localDeviceId, localDeviceName, 50000, publicKey)
    }

    private fun createConnectionManager(): ConnectionManager {
        val prefs = getSharedPreferences("labconnect_prefs", MODE_PRIVATE)
        val localDeviceId = prefs.getString("device_id", null)
            ?: "ANDROID-${Random.nextInt(10000, 99999)}".also { prefs.edit().putString("device_id", it).apply() }
        return ConnectionManager(localDeviceId)
    }
}

class MainViewModel : ViewModel() {
    // ViewModel for UI state
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    discoveryManager: DiscoveryManager
) {
    val devices = remember { mutableStateListOf<DiscoveryManager.DiscoveredDevice>() }
    DisposableEffect(discoveryManager) {
        val listener: (List<DiscoveryManager.DiscoveredDevice>) -> Unit = { latest ->
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                devices.clear()
                devices.addAll(latest)
            }
        }
        discoveryManager.addListener(listener)
        onDispose { discoveryManager.removeListener(listener) }
    }
    
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LabConnect",
            fontSize = 32.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
        )
        
        Text(text = "Discovered Devices: ${devices.size}", fontSize = 16.sp)
        
        if (devices.isEmpty()) {
            Text(text = "No devices found", fontSize = 16.sp)
        } else {
            devices.forEach { device ->
                Card(modifier = Modifier.fillMaxWidth().padding(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "${device.deviceName} (${device.deviceType})", fontSize = 18.sp)
                        Text(text = "IP: ${device.ipAddress}:${device.tcpPort}", fontSize = 14.sp)
                        Text(text = "ID: ${device.deviceId}", fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LabConnectTheme(content: @Composable () -> Unit) {
    content()
}
