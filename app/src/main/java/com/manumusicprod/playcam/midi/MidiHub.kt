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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Liaison Wi-Fi avec l'ordinateur — la seule connexion de l'application :
 *  - « Manu Play Cam Connect » (logiciel de l'ordinateur) envoie le SON et le MIDI (port 5010) ;
 *  - en option, la session MIDI réseau du Mac / rtpMIDI (RTP-MIDI) envoie le MIDI seul.
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
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val connectParser = MidiParser { s, a, b, t -> dispatch(s, a, b, t) }

    val connect = ConnectReceiver(phoneName, object : ConnectReceiver.Listener {
        override fun onMidi(data: ByteArray, offset: Int, length: Int, arrivalNanos: Long) { connectParser.feed(data, offset, length, arrivalNanos) }
        override fun onSender(name: String, host: String) { _computer.value = name }
        override fun onLevel(peakL: Float, peakR: Float, rms: Float) {
            val p = maxOf(peakL, peakR)
            _audioLevel.value = AudioEngine.Level(db(p), db(rms), p >= 0.985f, true, db(peakL), db(peakR), fromComputer = true)
        }
    })

    /** Option : MIDI seul par la session réseau du Mac ou rtpMIDI (Windows). */
    val wifi = WifiMidi(context, prefs) { msg, len, t ->
        dispatch(msg[0].toInt() and 0xFF, msg[1].toInt() and 0x7F, if (len >= 3) msg[2].toInt() and 0x7F else -1, t)
    }

    private val watcher = Thread {
        while (true) {
            try { Thread.sleep(1000) } catch (e: InterruptedException) { break }
            if (!connect.senderActive() && _computer.value != null) { _computer.value = null; performance.clear() }
            if (!connect.audioActive() && _audioLevel.value.active) _audioLevel.value = AudioEngine.Level(fromComputer = true)
        }
    }.apply { isDaemon = true; name = "mmp-link-watch" }

    fun start() {
        try { connect.start() } catch (e: Exception) { note("Le Wi-Fi n'a pas pu démarrer : ${e.message}") }
        if (prefs.networkMidi) wifi.start()
        watcher.start()
    }

    fun addListener(l: RawListener) { listeners.add(l) }
    fun removeListener(l: RawListener) { listeners.remove(l) }

    /** Vrai si un ordinateur envoie quelque chose (Connect ou session MIDI réseau). */
    fun isLinked(): Boolean = _computer.value != null || wifi.isLinked()

    fun linkedName(): String? = _computer.value ?: wifi.linked.value.firstOrNull()?.name

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

    fun setSessionMidiEnabled(on: Boolean) {
        prefs.networkMidi = on
        if (on) wifi.start() else wifi.stop()
    }

    fun note(msg: String) { _message.value = msg }
    fun consumeMessage() { _message.value = null }

    private fun db(x: Float): Float = if (x <= 1e-5f) -90f else (20 * kotlin.math.log10(x))

    companion object {
        private val EN = arrayOf("C", "C♯", "D", "E♭", "E", "F", "F♯", "G", "A♭", "A", "B♭", "B")
        private val FR = arrayOf("Do", "Do♯", "Ré", "Mi♭", "Mi", "Fa", "Fa♯", "Sol", "La♭", "La", "Si♭", "Si")
        fun noteName(n: Int, french: Boolean = false): String = (if (french) FR else EN)[((n % 12) + 12) % 12] + (n / 12 - 1)
        fun pitchName(n: Int, french: Boolean): String = (if (french) FR else EN)[((n % 12) + 12) % 12]
    }
}
