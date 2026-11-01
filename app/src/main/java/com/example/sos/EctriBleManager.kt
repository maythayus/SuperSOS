package com.example.sos

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
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import androidx.core.location.LocationManagerCompat
import java.util.ArrayDeque
import java.util.UUID

class EctriBleManager(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onStatus(status: String)
        fun onScanStateChanged(scanning: Boolean)
        fun onDeviceFound(device: FoundDevice)
        fun onConnected(device: BluetoothDevice)
        /** Notifications are on: the ring now accepts measurement commands. */
        fun onReady()
        fun onDisconnected()
        fun onMeasurement(measurement: Measurement)
    }

    data class FoundDevice(
        val name: String,
        val address: String,
        val rssi: Int,
        /** True when the device advertises the ECTRI service UUID (very likely the ring). */
        val isLikelyRing: Boolean,
    )

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

    private val handler = Handler(Looper.getMainLooper())

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var rxChar: BluetoothGattCharacteristic? = null

    /** True once notifications are enabled and the ring can receive commands. */
    @Volatile
    var isReady: Boolean = false
        private set

    @Volatile
    var isScanning: Boolean = false
        private set

    // Android only allows one GATT operation in flight at a time: any write sent while
    // another one is pending is silently dropped. Commands are therefore queued.
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writeInFlight = false

    private val stopScanRunnable = Runnable {
        if (isScanning) {
            stopScan()
            listener.onStatus("Scan terminé")
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val address = device.address ?: return
            val record = result.scanRecord
            val advertisedUuids = record?.serviceUuids.orEmpty()
            val likelyRing = advertisedUuids.any {
                it == ParcelUuid(ECTRI_SCAN_SERVICE_UUID) || it == ParcelUuid(ECTRI_SERVICE_UUID)
            }
            val name = record?.deviceName
                ?: runCatching { device.name }.getOrNull()
                ?: "(sans nom)"
            listener.onDeviceFound(FoundDevice(name, address, result.rssi, likelyRing))
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            handler.removeCallbacks(stopScanRunnable)
            listener.onScanStateChanged(false)
            val reason = when (errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "scan déjà en cours"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "redémarre le Bluetooth"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE non supporté"
                else -> "code $errorCode"
            }
            listener.onStatus("Scan échoué: $reason")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Erreur connexion ($status), rapproche la bague et réessaie")
                closeGatt()
                listener.onDisconnected()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onStatus("Connecté, découverte des services…")
                    this@EctriBleManager.gatt = gatt
                    listener.onConnected(gatt.device)
                    // Some phones fail discovery when it starts right after the link comes up.
                    handler.postDelayed({ gatt.discoverServices() }, 600)
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

            // Rings sold under different brands expose the same protocol with either the
            // 128-bit TI-style UUIDs (F000EFE0-0451-…) or the 16-bit form (0000EFE0-…-00805F9B34FB).
            val service = gatt.getService(ECTRI_SERVICE_UUID)
                ?: gatt.getService(ECTRI_SCAN_SERVICE_UUID)
                ?: gatt.services.firstOrNull { shortId(it.uuid) == "efe0" }

            if (service == null) {
                listener.onStatus("Service bague introuvable. Services vus: ${describeServices(gatt)}")
                return
            }

            val chars = service.characteristics.orEmpty()
            txChar = chars.firstOrNull { shortId(it.uuid) == "efe1" }
                ?: chars.firstOrNull { it.properties and WRITABLE != 0 }
            rxChar = chars.firstOrNull { shortId(it.uuid) == "efe3" }
                ?: chars.firstOrNull { it.properties and NOTIFIABLE != 0 }

            val rx = rxChar
            if (txChar == null || rx == null) {
                listener.onStatus("TX/RX introuvables. Services vus: ${describeServices(gatt)}")
                return
            }

            listener.onStatus("Activation des notifications…")
            enableNotifications(gatt, rx)
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (descriptor.uuid != CCCD_UUID) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                isReady = true
                listener.onStatus("Bague prête")
                listener.onReady()
            } else {
                listener.onStatus("Activation notifications échouée: $status")
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                listener.onStatus("Commande refusée par la bague: $status")
            }
            synchronized(writeQueue) { writeInFlight = false }
            drainQueue()
        }

        // Android 13+ calls this overload.
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(characteristic, value)
        }

        // Android 12 and below call this overload.
        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(characteristic, value)
        }
    }

    /** "efe0" for both F000EFE0-0451-… and 0000EFE0-0000-1000-…. */
    private fun shortId(uuid: UUID): String = uuid.toString().substring(4, 8).lowercase()

    /** Compact service/characteristic dump shown to the user when the ring is not recognised. */
    private fun describeServices(gatt: BluetoothGatt): String =
        gatt.services.joinToString(" | ") { s ->
            val c = s.characteristics.joinToString(",") { ch ->
                val flags = buildString {
                    if (ch.properties and WRITABLE != 0) append("W")
                    if (ch.properties and NOTIFIABLE != 0) append("N")
                }
                shortId(ch.uuid) + if (flags.isNotEmpty()) "($flags)" else ""
            }
            "${shortId(s.uuid)}[$c]"
        }.ifEmpty { "aucun" }

    private fun handleNotification(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != rxChar?.uuid) return
        if (value.size != 20) return

        val type = value[0].toInt() and 0xFF
        val payload = value.copyOfRange(1, value.size).map { it.toInt() and 0xFF }.toIntArray()
        parseMeasurement(type, payload, value)?.let { listener.onMeasurement(it) }
    }

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    /** Many phones return no BLE scan results while location (GPS) is switched off. */
    fun isLocationEnabled(): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(lm)
    }

    @SuppressLint("MissingPermission")
    fun startScan(durationMs: Long = SCAN_DURATION_MS) {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            listener.onStatus("Bluetooth non disponible sur ce téléphone")
            return
        }
        if (!adapter.isEnabled) {
            listener.onStatus("Bluetooth désactivé, active-le puis réessaie")
            return
        }

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) {
            listener.onStatus("Scanner BLE indisponible")
            return
        }

        if (isScanning) stopScan()

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        // No hardware filter: the ring does not always advertise its service UUID,
        // so every device is listed and the likely ring is flagged instead.
        scanner.startScan(null, settings, scanCallback)
        isScanning = true
        listener.onScanStateChanged(true)
        listener.onStatus(
            if (isLocationEnabled()) "Recherche… (porte la bague, écran allumé)"
            else "Recherche… (active la localisation si rien n'apparaît)"
        )

        handler.removeCallbacks(stopScanRunnable)
        handler.postDelayed(stopScanRunnable, durationMs)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        handler.removeCallbacks(stopScanRunnable)
        if (!isScanning) return
        isScanning = false
        listener.onScanStateChanged(false)
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        stopScan()
        listener.onStatus("Connexion…")
        closeGatt()
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun disconnect() {
        listener.onStatus("Déconnexion…")
        closeGatt()
        listener.onDisconnected()
    }

    fun startTest(testType: TestType) {
        if (gatt == null || txChar == null) {
            listener.onStatus("Pas connecté à la bague")
            return
        }
        if (!isReady) {
            listener.onStatus("Bague pas encore prête, patiente…")
            return
        }

        synchronized(writeQueue) { writeQueue.add(buildWriteHealthTestPacket(testType)) }
        drainQueue()
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
    private fun drainQueue() {
        val data: ByteArray
        synchronized(writeQueue) {
            if (writeInFlight) return
            data = writeQueue.poll() ?: return
            writeInFlight = true
        }

        val g = gatt
        val tx = txChar
        if (g == null || tx == null) {
            synchronized(writeQueue) {
                writeQueue.clear()
                writeInFlight = false
            }
            return
        }

        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(tx, data, writeTypeFor(tx)) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                tx.writeType = writeTypeFor(tx)
                tx.value = data
                g.writeCharacteristic(tx)
            }
        }

        if (ok) {
            listener.onStatus("Mesure en cours…")
        } else {
            listener.onStatus("Envoi de la commande échoué")
            synchronized(writeQueue) { writeInFlight = false }
            // Try the next queued command later rather than spinning.
            handler.postDelayed({ drainQueue() }, 500)
        }
    }

    private fun writeTypeFor(tx: BluetoothGattCharacteristic): Int =
        if (tx.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
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

        // Completion is reported in onDescriptorWrite.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(desc)
            }
        }
    }

    private fun parseMeasurement(type: Int, payload: IntArray, raw: ByteArray): Measurement? {
        return when (type) {
            0x0A -> { // HealthHR
                val hr = payload.getOrNull(11)?.takeIf { it > 0 } ?: return null
                Measurement(heartRateBpm = hr, rawType = type, raw = raw)
            }

            0x11 -> { // HealthBP
                val hp = payload.getOrNull(11)
                val lp = payload.getOrNull(12)
                val hr = payload.getOrNull(13)
                if (hp == null || lp == null || hp == 0 || lp == 0) return null
                Measurement(bpHigh = hp, bpLow = lp, bpHr = hr, rawType = type, raw = raw)
            }

            0x12 -> { // HealthSpO2
                val intPart = payload.getOrNull(11)?.takeIf { it > 0 } ?: return null
                val fracRaw = payload.getOrNull(12) ?: 0
                val frac = if (fracRaw >= 10) fracRaw.toString().first().digitToInt() else fracRaw
                val spo2 = "$intPart.$frac".toFloatOrNull() ?: return null
                Measurement(spo2Percent = spo2, rawType = type, raw = raw)
            }

            0x40 -> { // HealthTemp
                val hi = payload.getOrNull(11)
                val lo = payload.getOrNull(12)
                val sign = payload.getOrNull(13)
                if (hi == null || lo == null) return null
                var temp = ((hi shl 8) or lo).toFloat() / 10f
                if (temp == 0f) return null
                if (sign != null && sign != 0) temp *= -1f
                Measurement(temperatureC = temp, rawType = type, raw = raw)
            }

            else -> null
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        isReady = false
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInFlight = false
        }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        txChar = null
        rxChar = null
    }

    fun release() {
        stopScan()
        closeGatt()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val SCAN_DURATION_MS = 15_000L

        private const val WRITABLE = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        private const val NOTIFIABLE = BluetoothGattCharacteristic.PROPERTY_NOTIFY or
            BluetoothGattCharacteristic.PROPERTY_INDICATE

        // Extracted from ECTRI ManConstants
        val ECTRI_SCAN_SERVICE_UUID: UUID = UUID.fromString("0000EFE0-0000-1000-8000-00805F9B34FB")
        val ECTRI_SERVICE_UUID: UUID = UUID.fromString("F000EFE0-0451-4000-0000-00000000B000")
        val ECTRI_TX_CHAR_UUID: UUID = UUID.fromString("F000EFE1-0451-4000-0000-00000000B000")
        val ECTRI_RX_CHAR_UUID: UUID = UUID.fromString("F000EFE3-0451-4000-0000-00000000B000")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
