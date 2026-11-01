package com.example.sos

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.nio.ByteBuffer
import java.util.UUID

class EctriBleManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: String)
        fun onDeviceFound(device: BluetoothDevice, rssi: Int)
        fun onConnected(device: BluetoothDevice)
        fun onDisconnected()
        fun onMeasurement(measurement: Measurement)
    }

    data class Measurement(
        val heartRateBpm: Int? = null,
        val spo2Percent: Float? = null,
        val bpHigh: Int? = null,
        val bpLow: Int? = null,
        val bpHr: Int? = null,
        val temperatureC: Float? = null,
        val rawType: Int,
        val raw: ByteArray,
    )

    enum class TestType(val code: Int) {
        STOP(0),
        HR(2),
        BP(5),
        SPO2(6),
        TEMP(7),
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bm?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    // Android runs one GATT operation at a time: a write issued while another is pending is
    // dropped. Commands wait here until the previous write (or the CCCD write) has completed.
    private val pendingWrites = ArrayDeque<ByteArray>()
    private var gattBusy = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            listener.onDeviceFound(device, result.rssi)
        }

        override fun onScanFailed(errorCode: Int) {
            listener.onStatus("Scan échoué: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("BLE erreur connexion: $status")
                closeGatt()
                listener.onDisconnected()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onStatus("Connecté, découverte services…")
                    this@EctriBleManager.gatt = gatt
                    listener.onConnected(gatt.device)
                    if (hasBlePermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                        gatt.discoverServices()
                    } else {
                        listener.onStatus(MISSING_PERMISSION_STATUS)
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    listener.onStatus("Déconnecté")
                    closeGatt()
                    listener.onDisconnected()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Découverte services échouée: $status")
                return
            }

            val service = gatt.getService(ECTRI_SERVICE_UUID)
            if (service == null) {
                listener.onStatus("Service ECTRI introuvable")
                return
            }

            txChar = service.getCharacteristic(ECTRI_TX_CHAR_UUID)
            rxChar = service.getCharacteristic(ECTRI_RX_CHAR_UUID)

            if (txChar == null || rxChar == null) {
                listener.onStatus("TX/RX introuvables")
                return
            }

            listener.onStatus("Activation notifications…")
            enableNotifications(gatt, rxChar!!)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            onOperationDone()
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) listener.onStatus("Write échoué: $status")
            onOperationDone()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.getValue() ?: return
            if (value.size != 20) return
            if (characteristic.uuid != ECTRI_RX_CHAR_UUID) return

            val type = value[0].toInt() and 0xFF
            val payload = value.copyOfRange(1, value.size).map { it.toInt() and 0xFF }.toIntArray()
            val m = parseMeasurement(type, payload, value)
            if (m != null) {
                listener.onMeasurement(m)
            }
        }
    }

    // BLUETOOTH_SCAN and BLUETOOTH_CONNECT only exist from Android 12; they can be revoked at any
    // time and a BLE call made without them throws SecurityException.
    private fun hasBlePermission(permission: String): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (!hasBlePermission(Manifest.permission.BLUETOOTH_SCAN)) {
            listener.onStatus(MISSING_PERMISSION_STATUS)
            return
        }

        val adapter = bluetoothAdapter
        if (adapter == null || !adapter.isEnabled) {
            listener.onStatus("Bluetooth désactivé")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onStatus("Scanner BLE indisponible")
            return
        }

        listener.onStatus("Scan…")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        if (!hasBlePermission(Manifest.permission.BLUETOOTH_SCAN)) return

        val adapter = bluetoothAdapter ?: return
        val scanner = adapter.bluetoothLeScanner ?: return
        scanner.stopScan(scanCallback)
        listener.onStatus("Scan arrêté")
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        if (!hasBlePermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            listener.onStatus(MISSING_PERMISSION_STATUS)
            return
        }

        stopScan()
        listener.onStatus("Connexion…")
        closeGatt()
        gatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        listener.onStatus("Déconnexion…")
        closeGatt()
        listener.onDisconnected()
    }

    fun startTest(testType: TestType) {
        val g = gatt
        val tx = txChar
        if (g == null || tx == null) {
            listener.onStatus("Pas connecté")
            return
        }

        synchronized(pendingWrites) {
            pendingWrites.addLast(buildWriteHealthTestPacket(testType))
        }
        drainWrites()
    }

    private fun onOperationDone() {
        synchronized(pendingWrites) { gattBusy = false }
        drainWrites()
    }

    private fun drainWrites() {
        val tx = txChar ?: return
        val packet = synchronized(pendingWrites) {
            if (gattBusy) return
            pendingWrites.removeFirstOrNull()?.also { gattBusy = true }
        } ?: return
        if (!write(tx, packet)) onOperationDone()
    }

    private fun buildWriteHealthTestPacket(testType: TestType): ByteArray {
        // Based on ECTRI ManBeanEnum.WriteHealthTest data: [0xFC, 0x09] then values [testType]
        val out = ByteArray(20)
        out[0] = 0xFC.toByte()
        out[1] = 0x09.toByte()
        out[2] = (testType.code and 0xFF).toByte()
        // remaining bytes are 0
        return out
    }

    @SuppressLint("MissingPermission")
    private fun write(tx: BluetoothGattCharacteristic, data: ByteArray): Boolean {
        val g = gatt ?: return false
        tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(tx, data, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                tx.value = data
                g.writeCharacteristic(tx)
            }
        }
        if (!ok) listener.onStatus("Write refusé")
        return ok
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val ok = gatt.setCharacteristicNotification(characteristic, true)
        if (!ok) {
            listener.onStatus("Impossible d'activer notifications")
            return
        }

        val desc = characteristic.getDescriptor(CCCD_UUID)
        if (desc == null) {
            listener.onStatus("CCCD introuvable")
            return
        }

        synchronized(pendingWrites) { gattBusy = true }
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: use non-deprecated overload.
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
        if (!started) onOperationDone()

        listener.onStatus("Notifications activées")
    }

    private fun parseMeasurement(type: Int, payload: IntArray, raw: ByteArray): Measurement? {
        return when (type) {
            0x0A -> { // HealthHR
                val hr = payload.getOrNull(11)
                Measurement(heartRateBpm = hr, rawType = type, raw = raw)
            }

            0x11 -> { // HealthBP
                val hp = payload.getOrNull(11)
                val lp = payload.getOrNull(12)
                val hr = payload.getOrNull(13)
                Measurement(bpHigh = hp, bpLow = lp, bpHr = hr, rawType = type, raw = raw)
            }

            0x12 -> { // HealthSpO2
                val intPart = payload.getOrNull(11)
                val fracRaw = payload.getOrNull(12)
                val frac = when {
                    fracRaw == null -> null
                    fracRaw >= 10 -> (fracRaw.toString().firstOrNull()?.toString()?.toIntOrNull())
                    else -> fracRaw
                }
                val spo2 = if (intPart != null && frac != null) {
                    ("$intPart.$frac").toFloatOrNull()
                } else {
                    null
                }
                val pi = payload.getOrNull(13)?.toFloat()?.div(1000f)
                Measurement(spo2Percent = spo2, rawType = type, raw = raw)
            }

            0x40 -> { // HealthTemp
                val hi = payload.getOrNull(11)
                val lo = payload.getOrNull(12)
                val sign = payload.getOrNull(13)
                if (hi == null || lo == null) return null
                var temp = ((hi shl 8) or lo).toFloat() / 10f
                if (sign != null && sign != 0) temp *= -1f
                Measurement(temperatureC = temp, rawType = type, raw = raw)
            }

            else -> null
        }
    }

    // Best-effort cleanup: the try blocks already absorb a SecurityException.
    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        try {
            gatt?.disconnect()
        } catch (_: Throwable) {
        }
        try {
            gatt?.close()
        } catch (_: Throwable) {
        }
        gatt = null
        txChar = null
        rxChar = null
        synchronized(pendingWrites) {
            pendingWrites.clear()
            gattBusy = false
        }
    }

    companion object {
        private const val MISSING_PERMISSION_STATUS = "Permission bluetooth manquante"

        // Extracted from ECTRI ManConstants
        val ECTRI_SCAN_SERVICE_UUID: UUID = UUID.fromString("0000EFE0-0000-1000-8000-00805F9B34FB")
        val ECTRI_SERVICE_UUID: UUID = UUID.fromString("F000EFE0-0451-4000-0000-00000000B000")
        val ECTRI_TX_CHAR_UUID: UUID = UUID.fromString("F000EFE1-0451-4000-0000-00000000B000")
        val ECTRI_RX_CHAR_UUID: UUID = UUID.fromString("F000EFE3-0451-4000-0000-00000000B000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
