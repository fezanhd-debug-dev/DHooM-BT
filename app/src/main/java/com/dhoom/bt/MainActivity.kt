package com.dhoom.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.ParcelUuid
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.UUID

data class BleDevice(val name: String, val address: String, val rssi: Int)

@SuppressLint("MissingPermission")
class MainActivity : ComponentActivity() {

    private val scanResults = mutableStateMapOf<String, BleDevice>()
    private val rawDevices = mutableMapOf<String, BluetoothDevice>()
    private var scanning by mutableStateOf(false)

    private var bondedList by mutableStateOf<List<BluetoothDevice>>(emptyList())

    private var bluetoothGatt: BluetoothGatt? = null
    private var connectionStatus by mutableStateOf("Disconnected")
    private var gattServices by mutableStateOf<List<BluetoothGattService>>(emptyList())
    private val characteristicValues = mutableStateMapOf<String, String>()

    private var connectRetryCount = 0
    private var pendingDevice: BluetoothDevice? = null

    private var advertising by mutableStateOf(false)
    private var advertiseCallback: AdvertiseCallback? = null

    private var selectedTab by mutableStateOf(0)

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            try {
                val device = result.device
                val name = device.name ?: "Unknown Device"
                scanResults[device.address] = BleDevice(name, device.address, result.rssi)
                rawDevices[device.address] = device
            } catch (e: Exception) {
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Scan failed (code $errorCode)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            runOnUiThread {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectionStatus = "Connected"
                        connectRetryCount = 0
                        Handler(mainLooper).postDelayed({
                            try {
                                gatt.discoverServices()
                            } catch (e: Exception) {
                            }
                        }, 600)
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        gattServices = emptyList()
                        if (status == 133 && connectRetryCount < 2) {
                            connectRetryCount++
                            connectionStatus = "Retrying connection... ($connectRetryCount/2)"
                            Handler(mainLooper).postDelayed({
                                try {
                                    pendingDevice?.let {
                                        bluetoothGatt?.close()
                                        bluetoothGatt = it.connectGatt(
                                            this@MainActivity, false, gattCallback, BluetoothDevice.TRANSPORT_LE
                                        )
                                    }
                                } catch (e: Exception) {
                                }
                            }, 900)
                        } else {
                            connectionStatus = "Disconnected (status=$status)"
                            connectRetryCount = 0
                        }
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    gattServices = gatt.services
                    if (gatt.services.isEmpty()) {
                        Toast.makeText(this@MainActivity, "Connected, but device reported no services", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Service discovery failed (status=$status)", Toast.LENGTH_LONG).show()
                }
            }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            runOnUiThread {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    val bytes = characteristic.value
                    val hex = bytes?.joinToString(" ") { String.format("%02X", it) } ?: ""
                    characteristicValues[characteristic.uuid.toString()] = hex
                }
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions()

        setContent {
            var darkTheme by remember { mutableStateOf(true) }
            DHooMTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppScaffold(
                        selectedTab = selectedTab,
                        onTabSelected = {
                            selectedTab = it
                            if (it == 1) refreshBonded()
                        },
                        darkTheme = darkTheme,
                        onToggleTheme = { darkTheme = !darkTheme },
                        scanResults = scanResults.values.toList(),
                        scanning = scanning,
                        onScanClick = { toggleScan() },
                        onScanDeviceClick = { address ->
                            if (scanning) {
                                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                                scanning = false
                            }
                            rawDevices[address]?.let { connectToDevice(it) }
                            selectedTab = 2
                        },
                        bondedList = bondedList,
                        onBondedRefresh = { refreshBonded() },
                        onBondedDeviceClick = { device ->
                            if (scanning) {
                                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
                                scanning = false
                            }
                            connectToDevice(device)
                            selectedTab = 2
                        },
                        connectionStatus = connectionStatus,
                        gattServices = gattServices,
                        characteristicValues = characteristicValues,
                        onReadCharacteristic = { readCharacteristic(it) },
                        onDisconnect = { disconnectGatt() },
                        advertising = advertising,
                        onToggleAdvertising = { toggleAdvertising() }
                    )
                }
            }
        }
    }

    private fun requestPermissions() {
        val perms = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            perms.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return hasPermission(perm)
    }

    private fun hasConnectPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_CONNECT else Manifest.permission.ACCESS_FINE_LOCATION
        return hasPermission(perm)
    }

    private fun toggleScan() {
        try {
            val adapter = bluetoothAdapter
            if (adapter == null) {
                Toast.makeText(this, "Bluetooth not supported on this device", Toast.LENGTH_LONG).show()
                return
            }
            if (!adapter.isEnabled) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show()
                return
            }
            if (!hasScanPermission()) {
                requestPermissions()
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Toast.makeText(this, "Scanner unavailable. Try toggling Bluetooth off/on.", Toast.LENGTH_LONG).show()
                return
            }
            if (scanning) {
                scanner.stopScan(scanCallback)
                scanning = false
            } else {
                scanResults.clear()
                rawDevices.clear()
                scanner.startScan(scanCallback)
                scanning = true
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
            scanning = false
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            scanning = false
        }
    }

    private fun refreshBonded() {
        try {
            if (!hasConnectPermission()) {
                requestPermissions()
                return
            }
            bondedList = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        try {
            if (!hasConnectPermission()) {
                requestPermissions()
                return
            }
            pendingDevice = device
            connectRetryCount = 0
            bluetoothGatt?.close()
            connectionStatus = "Connecting..."
            bluetoothGatt = device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            Toast.makeText(this, "Connect error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun disconnectGatt() {
        try {
            pendingDevice = null
            connectRetryCount = 0
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
            connectionStatus = "Disconnected"
            gattServices = emptyList()
        } catch (e: Exception) {
        }
    }

    private fun readCharacteristic(characteristic: BluetoothGattCharacteristic) {
        try {
            bluetoothGatt?.readCharacteristic(characteristic)
        } catch (e: Exception) {
            Toast.makeText(this, "Read error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun toggleAdvertising() {
        try {
            val adapter = bluetoothAdapter
            if (adapter == null || !adapter.isEnabled) {
                Toast.makeText(this, "Please turn on Bluetooth", Toast.LENGTH_LONG).show()
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !hasPermission(Manifest.permission.BLUETOOTH_ADVERTISE)) {
                requestPermissions()
                return
            }
            val advertiser = adapter.bluetoothLeAdvertiser
            if (advertiser == null) {
                Toast.makeText(this, "Advertising not supported on this device", Toast.LENGTH_LONG).show()
                return
            }
            if (advertising) {
                advertiseCallback?.let { advertiser.stopAdvertising(it) }
                advertising = false
            } else {
                val settings = AdvertiseSettings.Builder()
                    .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                    .setConnectable(true)
                    .setTimeout(0)
                    .build()
                val data = AdvertiseData.Builder()
                    .setIncludeDeviceName(true)
                    .addServiceUuid(ParcelUuid(UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")))
                    .build()
                advertiseCallback = object : AdvertiseCallback() {
                    override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                        runOnUiThread { advertising = true }
                    }
                    override fun onStartFailure(errorCode: Int) {
                        runOnUiThread {
                            advertising = false
                            Toast.makeText(this@MainActivity, "Advertise failed (code $errorCode)", Toast.LENGTH_LONG).show()
                        }
                    }
                }
                advertiser.startAdvertising(settings, data, advertiseCallback)
            }
        } catch (e: SecurityException) {
            Toast.makeText(this, "Permission error: ${e.message}", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            bluetoothGatt?.close()
            advertiseCallback?.let { bluetoothAdapter?.bluetoothLeAdvertiser?.stopAdvertising(it) }
        } catch (e: Exception) {
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppScaffold(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    scanResults: List<BleDevice>,
    scanning: Boolean,
    onScanClick: () -> Unit,
    onScanDeviceClick: (String) -> Unit,
    bondedList: List<BluetoothDevice>,
    onBondedRefresh: () -> Unit,
    onBondedDeviceClick: (BluetoothDevice) -> Unit,
    connectionStatus: String,
    gattServices: List<BluetoothGattService>,
    characteristicValues: Map<String, String>,
    onReadCharacteristic: (BluetoothGattCharacteristic) -> Unit,
    onDisconnect: () -> Unit,
    advertising: Boolean,
    onToggleAdvertising: () -> Unit
) {
    val tabTitles = listOf("Scanner", "Bonded", "GATT", "Advertiser")
    val tabIcons = listOf("📡", "🔗", "🧬", "📶")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DHooM API BT") },
                actions = {
                    IconButton(onClick = onToggleTheme) {
                        Text(if (darkTheme) "☀" else "🌙")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                tabTitles.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { onTabSelected(index) },
                        icon = { Text(tabIcons[index]) },
                        label = { Text(title) }
                    )
                }
            }
        },
        floatingActionButton = {
            when (selectedTab) {
                0 -> ExtendedFloatingActionButton(
                    text = { Text(if (scanning) "Stop Scan" else "Start Scan") },
                    onClick = onScanClick,
                    icon = {}
                )
                3 -> ExtendedFloatingActionButton(
                    text = { Text(if (advertising) "Stop Advertising" else "Start Advertising") },
                    onClick = onToggleAdvertising,
                    icon = {}
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> ScannerScreen(scanResults, scanning, onScanDeviceClick)
                1 -> BondedScreen(bondedList, onBondedRefresh, onBondedDeviceClick)
                2 -> GattScreen(connectionStatus, gattServices, characteristicValues, onReadCharacteristic, onDisconnect)
                3 -> AdvertiserScreen(advertising)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScannerScreen(devices: List<BleDevice>, scanning: Boolean, onDeviceClick: (String) -> Unit) {
    if (devices.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (scanning) "Scanning..." else "No devices found. Tap Start Scan.")
        }
    } else {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(devices) { device ->
                ListItem(
                    headlineContent = { Text(device.name) },
                    supportingContent = { Text(device.address) },
                    trailingContent = { Text("${device.rssi} dBm") },
                    modifier = Modifier.clickable { onDeviceClick(device.address) }
                )
                Divider()
            }
        }
    }
}

@SuppressLint("MissingPermission")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BondedScreen(
    devices: List<BluetoothDevice>,
    onRefresh: () -> Unit,
    onDeviceClick: (BluetoothDevice) -> Unit
) {
    LaunchedEffect(Unit) { onRefresh() }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Paired Devices", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onRefresh) { Text("Refresh") }
        }
        if (devices.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No paired devices. Pair a device from phone Settings > Bluetooth first.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(devices) { device ->
                    ListItem(
                        headlineContent = { Text(device.name ?: "Unknown Device") },
                        supportingContent = { Text(device.address) },
                        modifier = Modifier.clickable { onDeviceClick(device) }
                    )
                    Divider()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GattScreen(
    connectionStatus: String,
    services: List<BluetoothGattService>,
    characteristicValues: Map<String, String>,
    onReadCharacteristic: (BluetoothGattCharacteristic) -> Unit,
    onDisconnect: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Status: $connectionStatus", style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = onDisconnect) { Text("Disconnect") }
        }
        if (services.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No services. Connect to a device from Scanner or Bonded tab.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(services) { service ->
                    Text(
                        "Service: ${service.uuid}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp)
                    )
                    service.characteristics.forEach { characteristic ->
                        val value = characteristicValues[characteristic.uuid.toString()] ?: "—"
                        ListItem(
                            headlineContent = { Text(characteristic.uuid.toString()) },
                            supportingContent = { Text("Value: $value") },
                            trailingContent = {
                                val canRead = (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ) != 0
                                if (canRead) {
                                    TextButton(onClick = { onReadCharacteristic(characteristic) }) {
                                        Text("Read")
                                    }
                                }
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}

@Composable
fun AdvertiserScreen(advertising: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (advertising) "Advertising..." else "Not advertising", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Tap the button below to start broadcasting this device as a BLE peripheral.")
        }
    }
}
