package com.jashith.stride.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.jashith.stride.core.recording.HeartRateMeasurement
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Reads any standard Bluetooth heart rate strap or armband. The Heart Rate Service is a published GATT
 * profile, so Polar, Garmin, Wahoo and the rest all work. Port of iOS `HeartRateMonitor`.
 */
class HeartRateMonitor(private val context: Context) {

    data class Device(val address: String, val name: String)

    data class State(
        val heartRate: Double = 0.0,
        val connected: Boolean = false,
        val deviceName: String = "",
        val scanning: Boolean = false,
        val discovered: List<Device> = emptyList(),
        val batteryLevel: Int? = null,
        val bluetoothReady: Boolean = false,
        val permitted: Boolean = false,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> get() = _state

    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("stride.bluetooth", Context.MODE_PRIVATE)
    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var wantConnection = false
    private val found = LinkedHashMap<String, BluetoothDevice>()

    val isSupported: Boolean
        get() = adapter != null && context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)

    /** The runtime permission needed to scan for and connect to a strap. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            emptyArray()
        }

    fun hasPermission(): Boolean = requiredPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun refresh() {
        _state.update { it.copy(bluetoothReady = adapter?.isEnabled == true, permitted = hasPermission()) }
    }

    val rememberedAddress: String? get() = prefs.getString(KEY_LAST_DEVICE, null)
    val rememberedName: String? get() = prefs.getString(KEY_LAST_NAME, null)

    // Scanning

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull() ?: "Heart Rate Monitor"
            found[device.address] = device
            _state.update { s ->
                if (s.discovered.any { it.address == device.address }) s
                else s.copy(discovered = s.discovered + Device(device.address, name))
            }
            // Auto-connect if this is the strap we used last time.
            if (wantConnection && device.address == rememberedAddress && gatt == null) connect(device.address)
        }

        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false) }
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        refresh()
        val scanner = adapter?.bluetoothLeScanner
        if (!hasPermission() || adapter?.isEnabled != true || scanner == null) return
        found.clear()
        _state.update { it.copy(discovered = emptyList(), scanning = true) }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scanCallback)
        } catch (_: Exception) {
            _state.update { it.copy(scanning = false) }
            return
        }
        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, SCAN_SECONDS * 1000L)
    }

    private val stopScanRunnable = Runnable { stopScan() }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        try {
            if (hasPermission()) adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        _state.update { it.copy(scanning = false) }
    }

    // Connecting

    @SuppressLint("MissingPermission")
    fun connect(address: String) {
        if (!hasPermission()) return
        val device = found[address] ?: runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        val name = _state.value.discovered.firstOrNull { it.address == address }?.name
            ?: rememberedName?.takeIf { rememberedAddress == address }
            ?: "Heart Rate Monitor"
        prefs.edit().putString(KEY_LAST_DEVICE, address).putString(KEY_LAST_NAME, name).apply()
        wantConnection = true
        stopScan()
        gatt?.close()
        gatt = if (Build.VERSION.SDK_INT >= 23) {
            device.connectGatt(context, true, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(context, true, callback)
        }
        _state.update { it.copy(deviceName = name) }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        wantConnection = false
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        _state.update { it.copy(connected = false, heartRate = 0.0, deviceName = "", batteryLevel = null) }
    }

    fun forgetDevice() {
        prefs.edit().remove(KEY_LAST_DEVICE).remove(KEY_LAST_NAME).apply()
        disconnect()
    }

    /** Reconnect to the remembered strap without the user doing anything. */
    fun reconnectLast() {
        refresh()
        if (!hasPermission() || adapter?.isEnabled != true) return
        val saved = rememberedAddress ?: return
        if (_state.value.connected || gatt != null) return
        wantConnection = true
        connect(saved)
    }

    private val callback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _state.update { it.copy(connected = true) }
                    g.discoverServices()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _state.update { it.copy(connected = false, heartRate = 0.0) }
                    // The connection was made with autoConnect, so the stack reconnects by itself when
                    // the strap comes back in range; only a deliberate disconnect closes the handle.
                    if (!wantConnection) {
                        g.close()
                        if (gatt === g) gatt = null
                    }
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val hr = g.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (hr != null) {
                g.setCharacteristicNotification(hr, true)
                val cccd = hr.getDescriptor(CCCD)
                if (cccd != null) {
                    if (Build.VERSION.SDK_INT >= 33) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }
            }
        }

        // Android allows one GATT operation at a time, so the battery is read once the CCCD write lands.
        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { g.readCharacteristic(it) }
        }

        @Deprecated("Replaced by the ByteArray overload on Android 13")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            deliver(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            deliver(c.uuid, value)
        }

        @Deprecated("Replaced by the ByteArray overload on Android 13")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (status == BluetoothGatt.GATT_SUCCESS) deliver(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) deliver(c.uuid, value)
        }
    }

    private fun deliver(uuid: UUID, value: ByteArray) {
        handler.post {
            when (uuid) {
                HEART_RATE_MEASUREMENT -> HeartRateMeasurement.parse(value)?.let { bpm ->
                    _state.update { it.copy(heartRate = bpm.toDouble()) }
                }
                BATTERY_LEVEL -> if (value.isNotEmpty()) {
                    _state.update { it.copy(batteryLevel = value[0].toInt() and 0xFF) }
                }
            }
        }
    }

    private companion object {
        const val KEY_LAST_DEVICE = "lastHeartRateDevice"
        const val KEY_LAST_NAME = "lastHeartRateDeviceName"
        const val SCAN_SECONDS = 15
        val HEART_RATE_SERVICE: UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        val HEART_RATE_MEASUREMENT: UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        val BATTERY_SERVICE: UUID = UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB")
        val BATTERY_LEVEL: UUID = UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
