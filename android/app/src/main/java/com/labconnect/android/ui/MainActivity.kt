package com.labconnect.android.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LabConnectTheme {
                MainScreen(viewModel = viewModel, discoveryManager = discoveryManager)
            }
        }
        discoveryManager.start()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stop()
    }
    
    private fun createDiscoveryManager(): DiscoveryManager {
        val localDeviceId = "ANDROID-${Random.nextInt(10000, 99999)}"
        val localDeviceName = "Phone-${Random.nextInt(1000, 9999)}"
        return DiscoveryManager(
            context = this,
            localDeviceId = localDeviceId,
            localDeviceName = localDeviceName,
            localTcpPort = 50000,
            localPublicKey = "android-key-${Random.nextInt(10000)}"
        )
    }
    
    private fun createConnectionManager(): ConnectionManager {
        val localDeviceId = "ANDROID-${Random.nextInt(10000, 99999)}"
        return ConnectionManager(localDeviceId)
    }
    
    override fun onDestroy() {
        super.onDestroy()
        discoveryManager.stop()
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
    val devices by remember { mutableStateOf(discoveryManager.getDiscoveredDevices()) }
    
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
                Card(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    elevation = 4.dp
                ) {
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