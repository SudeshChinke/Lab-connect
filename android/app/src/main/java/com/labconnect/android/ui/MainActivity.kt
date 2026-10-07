package com.labconnect.android.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.labconnect.android.network.ConnectionManager
import com.labconnect.android.network.DiscoveryManager
import com.labconnect.android.network.FileTransferManager
import com.labconnect.android.network.FileRequest
import com.labconnect.android.network.Protocol
import com.labconnect.android.network.TextMessage
import com.labconnect.android.security.AndroidIdentityStore
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    private val identity by lazy { AndroidIdentityStore(this).loadOrCreate() }
    private val localDeviceName by lazy {
        getSharedPreferences("labconnect_prefs", MODE_PRIVATE).let { prefs ->
            prefs.getString("device_name", null) ?: "Android-${Build.MODEL ?: "Device"}".also {
                prefs.edit().putString("device_name", it).apply()
            }
        }
    }
    private val discoveryManager by lazy {
        DiscoveryManager(this, identity.deviceId, localDeviceName, Protocol.TCP_PORT, identity.publicKeyBase64)
    }
    private val connectionManager by lazy { ConnectionManager(identity, localDeviceName) }
    private val fileTransferManager by lazy { FileTransferManager(this, identity, connectionManager) }
    private var discoveryStarted = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants -> if (grants.values.all { it }) startDiscoveryIfAllowed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        connectionManager.startServer()
        connectionManager.setOnConnectionChanged { deviceId, connected ->
            Handler(Looper.getMainLooper()).post { viewModel.connectionChanged(deviceId, connected) }
        }
        connectionManager.setOnMessageReceived { message ->
            Handler(Looper.getMainLooper()).post { viewModel.addMessage(message) }
        }
        fileTransferManager.onIncomingRequest = { request ->
            Handler(Looper.getMainLooper()).post { viewModel.incomingRequests.add(request) }
        }
        fileTransferManager.onTransferUpdate = { update ->
            Handler(Looper.getMainLooper()).post { viewModel.transferStatus = update }
        }
        setContent {
            LabConnectTheme {
                MainScreen(viewModel, discoveryManager, connectionManager, fileTransferManager)
            }
        }
        startDiscoveryIfAllowed()
    }

    override fun onDestroy() {
        if (discoveryStarted) discoveryManager.stop()
        connectionManager.shutdown()
        fileTransferManager.shutdown()
        super.onDestroy()
    }

    private fun startDiscoveryIfAllowed() {
        val permissions = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            if (!discoveryStarted) {
                discoveryManager.start()
                discoveryStarted = true
            }
        } else permissionLauncher.launch(permissions)
    }
}

class MainViewModel : ViewModel() {
    val connectedIds = mutableStateListOf<String>()
    val messages = mutableStateListOf<TextMessage>()
    val incomingRequests = mutableStateListOf<FileRequest>()
    var draft by mutableStateOf("")
    var selectedPeer by mutableStateOf<String?>(null)
    var status by mutableStateOf("Looking for devices on this network")
    var transferStatus by mutableStateOf("")

    fun connectionChanged(deviceId: String, connected: Boolean) {
        if (connected && deviceId !in connectedIds) connectedIds.add(deviceId)
        if (!connected) {
            connectedIds.remove(deviceId)
            if (selectedPeer == deviceId) selectedPeer = null
        }
        status = if (connectedIds.isEmpty()) "No connected devices" else "${connectedIds.size} connected device(s)"
    }

    fun addMessage(message: TextMessage) { messages.add(message) }
}

@Composable
fun MainScreen(viewModel: MainViewModel, discoveryManager: DiscoveryManager, connectionManager: ConnectionManager, fileTransfers: FileTransferManager) {
    val devices = rememberDevices(discoveryManager)
    val scope = rememberCoroutineScope()
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            val peer = viewModel.selectedPeer
            if (uri != null && peer != null) scope.launch { fileTransfers.sendFile(peer, uri) }
        }
    )
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("LabConnect", style = MaterialTheme.typography.headlineMedium)
        Text(viewModel.status)
        Text("Devices on this Wi-Fi network", style = MaterialTheme.typography.titleMedium)
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.deviceId }) { device ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(device.deviceName, style = MaterialTheme.typography.titleSmall)
                            Text("${device.deviceType} • ${device.ipAddress}:${device.tcpPort}")
                        }
                        if (device.deviceId in viewModel.connectedIds) {
                            Button(onClick = { viewModel.selectedPeer = device.deviceId }) { Text("Chat") }
                        } else {
                            Button(onClick = {
                                viewModel.status = "Connecting to ${device.deviceName}…"
                                viewModel.viewModelScope.launch {
                                    val connection = connectionManager.connect(device.ipAddress, device.tcpPort)
                                    if (connection == null) viewModel.status = "Could not connect to ${device.deviceName}"
                                    else viewModel.selectedPeer = device.deviceId
                                }
                            }) { Text("Connect") }
                        }
                    }
                }
            }
        }
        viewModel.selectedPeer?.let { peer ->
            Text("Chat with ${devices.firstOrNull { it.deviceId == peer }?.deviceName ?: peer}", style = MaterialTheme.typography.titleMedium)
            LazyColumn(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                items(viewModel.messages.filter { it.senderId == peer || it.chatId == peer }) { message ->
                    Text("${if (message.senderId == peer) "Peer" else "You"}: ${message.content}")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = viewModel.draft,
                    onValueChange = { viewModel.draft = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Message") }
                )
                Button(onClick = {
                    val text = viewModel.draft.trim()
                    val sent = if (text.isNotEmpty()) connectionManager.sendMessage(peer, text) else null
                    if (sent != null) {
                        viewModel.addMessage(sent)
                        viewModel.draft = ""
                    }
                }) { Text("Send") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("Send file") }
                if (viewModel.transferStatus.isNotBlank()) Text(viewModel.transferStatus, modifier = Modifier.padding(8.dp))
            }
        }
    }
    viewModel.incomingRequests.firstOrNull()?.let { request ->
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Incoming file") },
            text = { Text("${request.senderId} wants to send ${request.fileName} (${request.fileSize} bytes).") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.incomingRequests.remove(request)
                    fileTransfers.acceptIncoming(request.transferId)
                }) { Text("Accept") }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.incomingRequests.remove(request)
                    fileTransfers.rejectIncoming(request.transferId)
                }) { Text("Decline") }
            }
        )
    }
}

@Composable
private fun rememberDevices(manager: DiscoveryManager): List<DiscoveryManager.DiscoveredDevice> {
    val devices = androidx.compose.runtime.remember { mutableStateListOf<DiscoveryManager.DiscoveredDevice>() }
    DisposableEffect(manager) {
        val listener: (List<DiscoveryManager.DiscoveredDevice>) -> Unit = { latest ->
            Handler(Looper.getMainLooper()).post { devices.apply { clear(); addAll(latest) } }
        }
        manager.addListener(listener)
        onDispose { manager.removeListener(listener) }
    }
    return devices
}

@Composable
fun LabConnectTheme(content: @Composable () -> Unit) { MaterialTheme(content = content) }
