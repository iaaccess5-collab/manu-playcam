package com.manumusicprod.playcam.midi

import android.content.Context
import android.os.Build
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.audio.AudioEngine
import com.manumusicprod.playcam.core.ConnectReceiver
import com.manumusicprod.playcam.core.MidiParser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Liaison avec l'ordinateur par « Manu Play Cam Connect », qui envoie le SON et le MIDI :
 *  - par le Wi-Fi (port UDP 5010) ;
 *  - ou par le câble USB (port TCP 5011 ouvert par Connect avec « adb reverse »), prioritaire quand il est branché.
 */
class MidiHub(private val context: Context, private val prefs: Prefs, val performance: Performance) {

    /** Écouteur brut (horodatage System.nanoTime) : utilisé par l'enregistreur MIDI. */
    fun interface RawListener { fun onMidi(status: Int, d1: Int, d2: Int, tNanos: Long) }

    private val listeners = CopyOnWriteArrayList<RawListener>()
    val phoneName: String = "Manu Play Cam (${Build.MODEL})"

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log
    private val _lastEventAt = MutableStateFlow(0L)
    val lastEventAt: StateFlow<Long> = _lastEventAt
    private val _computer = MutableStateFlow<String?>(null)
    /** Nom de l'ordinateur relié par Manu Play Cam Connect (null si aucun). */
    val computer: StateFlow<String?> = _computer
    private val _audioLevel = MutableStateFlow(AudioEngine.Level(fromComputer = true))
    val audioLevel: StateFlow<AudioEngine.Level> = _audioLevel
    private val _usb = MutableStateFlow(false)
    /** true quand l'ordinateur est relié par le câble USB. */
    val usb: StateFlow<Boolean> = _usb
    private val _found = MutableStateFlow<List<ConnectReceiver.Computer>>(emptyList())
    /** Ordinateurs trouvés sur le Wi-Fi (Manu Play Cam Connect ouvert), par leur nom. */
    val found: StateFlow<List<ConnectReceiver.Computer>> = _found
    @Volatile private var manualOff: String? = null    // ordinateur déconnecté à la main : pas de reconnexion auto
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val connectParser = MidiParser { s, a, b, t -> dispatch(s, a, b, t) }

    val connect = ConnectReceiver(phoneName, object : ConnectReceiver.Listener {
        override fun onMidi(data: ByteArray, offset: Int, length: Int, arrivalNanos: Long) { connectParser.feed(data, offset, length, arrivalNanos) }
        override fun onSender(name: String, host: String) {
            _computer.value = name; _usb.value = host == "USB"
            if (host != "USB") prefs.lastComputer = name
        }
        override fun onLevel(peakL: Float, peakR: Float, rms: Float) {
            val p = maxOf(peakL, peakR)
            _audioLevel.value = AudioEngine.Level(db(p), db(rms), p >= 0.985f, true, db(peakL), db(peakR), fromComputer = true)
        }
    })

    private val watcher = Thread {
        while (true) {
            try { Thread.sleep(500) } catch (e: InterruptedException) { break }
            val list = connect.computers()
            if (list.map { it.name to it.host } != _found.value.map { it.name to it.host }) _found.value = list
            // reconnexion automatique au dernier ordinateur choisi
            if (!connect.senderActive() && connect.chosenHost() == null) {
                val last = prefs.lastComputer
                list.firstOrNull { it.name == last && it.name != manualOff && !connect.isBlocked(it.host) }?.let { connect.connectTo(it) }
            }
            if (!connect.senderActive() && _computer.value != null) { _computer.value = null; performance.clear() }
            val u = connect.viaUsb() && connect.senderActive()
            if (_usb.value != u) _usb.value = u
            if (!connect.audioActive() && _audioLevel.value.active) _audioLevel.value = AudioEngine.Level(fromComputer = true)
        }
    }.apply { isDaemon = true; name = "mmp-link-watch" }

    fun start() {
        // sans ce verrou, certains téléphones ignorent les annonces envoyées à tout le Wi-Fi (recherche par le nom)
        runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            wm.createMulticastLock("mmp-connect").apply { setReferenceCounted(false); acquire() }
            // Wi-Fi sans économie d'énergie : sinon le téléphone regroupe les paquets reçus (jusqu'à 300 ms de retard)
            val mode = if (Build.VERSION.SDK_INT >= 29) android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                       else android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm.createWifiLock(mode, "mmp-realtime").apply { setReferenceCounted(false); acquire() }
        }
        try { connect.start() } catch (e: Exception) { note("La liaison avec l'ordinateur n'a pas pu démarrer : ${e.message}") }
        watcher.start()
    }

    fun addListener(l: RawListener) { listeners.add(l) }
    fun removeListener(l: RawListener) { listeners.remove(l) }

    /** Vrai si un ordinateur est relié (Wi-Fi ou câble). */
    fun isLinked(): Boolean = _computer.value != null

    fun linkedName(): String? = _computer.value

    /** Relie le téléphone à cet ordinateur (choisi par son nom). */
    fun connectTo(c: ConnectReceiver.Computer) {
        manualOff = null
        prefs.lastComputer = c.name
        connect.connectTo(c)
    }

    fun disconnect() {
        manualOff = _computer.value
        connect.disconnect()
        _computer.value = null
        performance.clear()
    }

    // ---- son de l'ordinateur pour la vidéo
    fun startAudioRecording(file: File) = connect.startRecording(file)
    fun stopAudioRecording(): ConnectReceiver.Take? = connect.stopRecording()
    fun computerAudioActive(): Boolean = connect.audioActive()

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
        _log.value = (listOf(line) + _log.value).take(40)
    }

    fun note(msg: String) { _message.value = msg }
    fun consumeMessage() { _message.value = null }

    private fun db(x: Float): Float = if (x <= 1e-5f) -90f else (20 * kotlin.math.log10(x))

    companion object {
        private val EN = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val FR = arrayOf("Do", "Do♯", "Ré", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")
        fun noteName(n: Int, french: Boolean = false): String = (if (french) FR else EN)[((n % 12) + 12) % 12] + (n / 12 - 1)
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }
        fun pitchName(n: Int, french: Boolean): String = (if (french) FR else EN)[((n % 12) + 12) % 12]
    }
}
