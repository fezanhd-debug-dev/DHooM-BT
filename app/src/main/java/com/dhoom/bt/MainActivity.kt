package com.dhoom.bt

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat

data class BleDevice(val name: String, val address: String, val rssi: Int)

class MainActivity : ComponentActivity() {

    private val devices = mutableStateMapOf<String, BleDevice>()
    private var scanning by mutableStateOf(false)

    private val bluetoothManager by lazy {
        getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    }
    private val bluetoothAdapter: BluetoothAdapter? by lazy { bluetoothManager.adapter }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = device.name ?: "Unknown Device"
            devices[device.address] = BleDevice(name, device.address, result.rssi)
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
                    MainScreen(
                        devices = devices.values.toList(),
                        scanning = scanning,
                        darkTheme = darkTheme,
                        onToggleTheme = { darkTheme = !darkTheme },
                        onScanClick = { toggleScan() }
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
        }
        permissionLauncher.launch(perms.toTypedArray())
    }

    @SuppressLint("MissingPermission")
    private fun toggleScan() {
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        if (!hasScanPermission()) {
            requestPermissions()
            return
        }
        if (scanning) {
            scanner.stopScan(scanCallback)
            scanning = false
        } else {
            devices.clear()
            scanner.startScan(scanCallback)
            scanning = true
        }
    }

    private fun hasScanPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
        return ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    devices: List<BleDevice>,
    scanning: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onScanClick: () -> Unit
) {
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(if (scanning) "Stop Scan" else "Start Scan") },
                onClick = onScanClick,
                icon = {}
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(if (scanning) "Scanning..." else "No devices found. Tap Start Scan.")
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(devices) { device ->
                    ListItem(
                        headlineContent = { Text(device.name) },
                        supportingContent = { Text(device.address) },
                        trailingContent = { Text("${device.rssi} dBm") }
                    )
                    Divider()
                }
            }
        }
    }
}
