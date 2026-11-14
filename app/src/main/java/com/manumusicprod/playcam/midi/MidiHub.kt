package com.manumusicprod.playcam.midi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.core.MidiParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Point central du MIDI : claviers USB, Bluetooth (BLE MIDI), mode « USB MIDI » du téléphone
 * relié à un ordinateur, et MIDI reçu par le Wi-Fi depuis le compagnon ordinateur.
 */
class MidiHub(private val context: Context, private val prefs: Prefs, val performance: Performance) {

    enum class Kind(val label: String) { USB("USB"), BLUETOOTH("Bluetooth"), VIRTUAL("Virtuel"), NETWORK("Wi-Fi") }

    data class Source(val key: String, val name: String, val kind: Kind, val connected: Boolean, val ports: Int)
    data class BleFound(val address: String, val name: String)

    /** Écouteur brut (horodatage System.nanoTime) : utilisé par l'enregistreur MIDI. */
    fun interface RawListener { fun onMidi(status: Int, d1: Int, d2: Int, tNanos: Long) }

    val manager: MidiManager? = if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_MIDI))
        context.getSystemService(MidiManager::class.java) else null
    val supported: Boolean get() = manager != null

    private val thread = HandlerThread("mmp-midi").apply { start() }
    private val handler = Handler(thread.looper)

    private val opened = ConcurrentHashMap<Int, Pair<MidiDevice, List<MidiOutputPort>>>()
    private val listeners = CopyOnWriteArrayList<RawListener>()

    private val _sources = MutableStateFlow<List<Source>>(emptyList())
    val sources: StateFlow<List<Source>> = _sources

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log

    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt

    private val _ble = MutableStateFlow<List<BleFound>>(emptyList())
    val bleFound: StateFlow<List<BleFound>> = _ble
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val networkParser = MidiParser { s, a, b, t -> dispatch(s, a, b, t) }
    val network = NetworkMidiServer(context) { data, off, len, t -> networkParser.feed(data, off, len, t) }

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            refresh()
            // branchement à chaud : on ouvre automatiquement les claviers USB et Bluetooth
            if (device.outputPortCount > 0) open(device)
        }
        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            close(device.id)
            performance.clear()
            note("Déconnecté : " + nameOf(device))
            refresh()
        }
    }

    fun start() {
        val m = manager ?: return
        @Suppress("DEPRECATION")
        m.registerDeviceCallback(callback, handler)
        refresh()
        // ouvre ce qui est déjà branché
        devices().filter { it.outputPortCount > 0 }.forEach { open(it) }
        if (prefs.networkMidi) network.start()
    }

    fun addListener(l: RawListener) { listeners.add(l) }
    fun removeListener(l: RawListener) { listeners.remove(l) }

    @Suppress("DEPRECATION")
    private fun devices(): List<MidiDeviceInfo> {
        val m = manager ?: return emptyList()
        return if (Build.VERSION.SDK_INT >= 33)
            m.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
        else m.devices.toList()
    }

    fun refresh() {
        val list = devices().filter { it.outputPortCount > 0 }.map { info ->
            Source(
                key = info.id.toString(),
                name = nameOf(info),
                kind = when (info.type) {
                    MidiDeviceInfo.TYPE_USB -> Kind.USB
                    MidiDeviceInfo.TYPE_BLUETOOTH -> Kind.BLUETOOTH
                    else -> Kind.VIRTUAL
                },
                connected = opened.containsKey(info.id),
                ports = info.outputPortCount,
            )
        }.toMutableList()
        if (network.running.value) list.add(Source("net", network.statusLabel(), Kind.NETWORK, network.hasClient(), 1))
        _sources.value = list
    }

    fun connect(key: String) {
        val info = devices().firstOrNull { it.id.toString() == key } ?: return
        open(info)
    }

    fun disconnect(key: String) {
        key.toIntOrNull()?.let { close(it) }
        performance.clear()
        refresh()
    }

    private fun open(info: MidiDeviceInfo) {
        val m = manager ?: return
        if (opened.containsKey(info.id)) return
        m.openDevice(info, { device ->
            if (device == null) { note("Impossible d'ouvrir " + nameOf(info)); return@openDevice }
            attach(info, device)
        }, handler)
    }

    private fun attach(info: MidiDeviceInfo, device: MidiDevice) {
        val ports = ArrayList<MidiOutputPort>()
        for (i in 0 until info.outputPortCount) {
            val port = device.openOutputPort(i) ?: continue
            val parser = MidiParser { s, a, b, t -> dispatch(s, a, b, t) }
            port.connect(object : MidiReceiver() {
                override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                    parser.feed(msg, offset, count, if (timestamp > 0) timestamp else System.nanoTime())
                }
            })
            ports.add(port)
        }
        opened[info.id] = device to ports
        note("Connecté : " + nameOf(info))
        refresh()
    }

    private fun close(id: Int) {
        opened.remove(id)?.let { (dev, ports) ->
            ports.forEach { runCatching { it.close() } }
            runCatching { dev.close() }
        }
    }

    private fun dispatch(status: Int, d1: Int, d2: Int, t: Long) {
        performance.onMessage(status, d1, d2)
        for (l in listeners) l.onMidi(status, d1, d2, t)
        _lastEventAt.value = System.currentTimeMillis()
        val type = status and 0xF0
        val ch = (status and 0x0F) + 1
        val line = when {
            type == 0x90 && d2 > 0 -> "Note On   ${noteName(d1)}  vél. $d2  canal $ch"
            type == 0x80 || type == 0x90 -> "Note Off  ${noteName(d1)}  canal $ch"
            type == 0xB0 && d1 == 64 -> "Sustain   ${if (d2 >= 64) "appuyée" else "relâchée"}  canal $ch"
            type == 0xB0 -> "Contrôleur $d1 = $d2  canal $ch"
            type == 0xC0 -> "Program Change $d1  canal $ch"
            type == 0xE0 -> "Pitch Bend  canal $ch"
            else -> null
        } ?: return
        val cur = _log.value
        _log.value = (listOf(line) + cur).take(40)
    }

    // ---------------- Bluetooth MIDI ----------------
    private val bleUuid: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")
    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device
            val name = runCatching { d.name }.getOrNull() ?: result.scanRecord?.deviceName ?: d.address
            if (_ble.value.none { it.address == d.address }) _ble.value = _ble.value + BleFound(d.address, name)
        }
        override fun onScanFailed(errorCode: Int) { _scanning.value = false; note("Recherche Bluetooth impossible (code $errorCode)") }
    }

    fun hasBlePermission(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)
    else granted(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (!hasBlePermission()) { note("Autorise le Bluetooth pour rechercher un clavier."); return }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) { note("Active le Bluetooth du téléphone, puis relance la recherche."); return }
        val scanner = adapter.bluetoothLeScanner ?: run { note("Bluetooth Low Energy indisponible."); return }
        _ble.value = emptyList()
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(bleUuid)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filters, settings, scanCallback)
            _scanning.value = true
            handler.postDelayed({ stopBleScan() }, 15_000)
        } catch (e: SecurityException) { note("Autorisation Bluetooth refusée.") }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        if (!_scanning.value) return
        val scanner = context.getSystemService(BluetoothManager::class.java)?.adapter?.bluetoothLeScanner
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) {}
        _scanning.value = false
    }

    @SuppressLint("MissingPermission")
    fun connectBle(address: String) {
        val m = manager ?: return
        stopBleScan()
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        val dev: BluetoothDevice = try { adapter.getRemoteDevice(address) } catch (e: IllegalArgumentException) { return }
        note("Connexion Bluetooth en cours…")
        m.openBluetoothDevice(dev, { device ->
            if (device == null) { note("Le clavier Bluetooth n'a pas répondu. Réessaie en le rapprochant."); return@openBluetoothDevice }
            attach(device.info, device)
        }, handler)
    }

    // ---------------- Réseau ----------------
    fun setNetworkEnabled(on: Boolean) {
        prefs.networkMidi = on
        if (on) network.start() else network.stop()
        refresh()
    }

    fun note(msg: String) { _message.value = msg }
    fun consumeMessage() { _message.value = null }

    fun connectedCount(): Int = opened.size + if (network.hasClient()) 1 else 0

    companion object {
        private val EN = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val FR = arrayOf("Do", "Do♯", "Ré", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")
        fun noteName(n: Int, french: Boolean = false): String {
            val names = if (french) FR else EN
            return names[((n % 12) + 12) % 12] + (n / 12 - 1)
        }
        fun pitchName(n: Int, french: Boolean): String = (if (french) FR else EN)[((n % 12) + 12) % 12]

        fun nameOf(info: MidiDeviceInfo): String {
            val p = info.properties
            val name = p.getString(MidiDeviceInfo.PROPERTY_NAME)
                ?: listOfNotNull(p.getString(MidiDeviceInfo.PROPERTY_MANUFACTURER), p.getString(MidiDeviceInfo.PROPERTY_PRODUCT)).joinToString(" ")
            return name.ifBlank { "Appareil MIDI ${info.id}" }.trim().replaceFirstChar { it.titlecase(Locale.FRANCE) }
        }
    }
}
