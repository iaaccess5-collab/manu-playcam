package com.manumusicprod.playcam.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.core.WavWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Acquisition audio : micro du téléphone ou interface audio USB (choix explicite du périphérique),
 * gain logiciel, limiteur anti-saturation, vumètre et enregistrement WAV 16 bits horodaté.
 */
class AudioEngine(private val context: Context, private val prefs: Prefs) {

    data class Input(val id: Int, val label: String, val kind: String, val external: Boolean, val stereo: Boolean)
    /** Niveau d'un signal en dB (0 = pleine échelle). active = un flux audio arrive ; signal = du son y est présent. */
    data class Level(
        val peakDb: Float = -90f, val rmsDb: Float = -90f, val clipping: Boolean = false, val active: Boolean = false,
        val leftDb: Float = peakDb, val rightDb: Float = peakDb, val fromComputer: Boolean = false,
    ) { val signal: Boolean get() = active && peakDb > -60f }

    /** Résultat d'un enregistrement : instant (System.nanoTime) de la première trame du fichier. */
    data class Take(val file: File, val firstFrameNanos: Long, val sampleRate: Int, val channels: Int, val frames: Long,
                    val deviceLabel: String, val clippedBlocks: Int, val error: String?)

    private val am = context.getSystemService(AudioManager::class.java)

    private val _inputs = MutableStateFlow<List<Input>>(emptyList())
    val inputs: StateFlow<List<Input>> = _inputs
    private val _level = MutableStateFlow(Level())
    val level: StateFlow<Level> = _level
    private val _activeDevice = MutableStateFlow("")
    val activeDevice: StateFlow<String> = _activeDevice

    private val lock = Any()
    private var record: AudioRecord? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    @Volatile private var monitorUsers = 0
    private var writer: WavWriter? = null
    private var writerFile: File? = null
    private var writerStartFrame = 0L
    private var framesRead = 0L
    private var rate = 48000
    private var channels = 2
    private val ts = AudioTimestamp()
    private var tsFrame = -1L
    private var tsNanos = 0L
    private var lastReadNanos = 0L
    private var clippedBlocks = 0
    private var writeError: String? = null

    init {
        am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshInputs() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshInputs() }
        }, Handler(Looper.getMainLooper()))
        refreshInputs()
    }

    fun refreshInputs() {
        _inputs.value = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.isSource && it.type != AudioDeviceInfo.TYPE_TELEPHONY && it.type != AudioDeviceInfo.TYPE_REMOTE_SUBMIX && it.type != AudioDeviceInfo.TYPE_FM_TUNER }
            .map { d ->
                val kind = kindOf(d)
                val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL } ?: kind
                Input(d.id, if (name == kind) kind else "$name · $kind", kind, isExternal(d), d.channelCounts.isEmpty() || d.channelCounts.any { it >= 2 })
            }
            .distinctBy { it.label }
    }

    private fun kindOf(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Micro du téléphone"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "Interface audio USB"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "Casque / micro USB"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Entrée jack"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Micro Bluetooth"
        else -> "Entrée audio"
    }

    private fun isExternal(d: AudioDeviceInfo) = d.type == AudioDeviceInfo.TYPE_USB_DEVICE || d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            d.type == AudioDeviceInfo.TYPE_USB_ACCESSORY || d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET

    /** Le son vient de l'ordinateur (Wi-Fi) ; à défaut, du micro intégré du téléphone. */
    private fun chooseDevice(): AudioDeviceInfo? {
        val all = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        return all.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun unprocessedSupported(): Boolean = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

    // ------------------------------------------------------------------ capture
    fun startMonitor() { synchronized(lock) { monitorUsers++ }; ensureRunning() }
    fun stopMonitor() {
        val stop = synchronized(lock) { monitorUsers = max(0, monitorUsers - 1); monitorUsers == 0 && writer == null }
        if (stop) shutdown()
    }

    /** Redémarre la capture (après un changement de source, de fréquence ou de canaux). */
    fun restart() {
        val wasRunning = running
        if (writer != null) return
        shutdown()
        if (wasRunning) ensureRunning()
    }

    @SuppressLint("MissingPermission")
    private fun ensureRunning(): String? {
        synchronized(lock) {
            if (running) return null
            if (!hasPermission()) return "Autorisation du micro refusée"
            val dev = chooseDevice()
            val wantStereo = prefs.stereo && (dev?.channelCounts?.let { it.isEmpty() || it.any { c -> c >= 2 } } ?: true)
            val src = if (prefs.rawAudio && unprocessedSupported()) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.MIC
            var rec: AudioRecord? = null
            for (st in if (wantStereo) listOf(true, false) else listOf(false)) {
                val mask = if (st) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
                val min = AudioRecord.getMinBufferSize(prefs.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) continue
                val r = try {
                    AudioRecord.Builder()
                        .setAudioSource(src)
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(prefs.sampleRate).setChannelMask(mask).build())
                        .setBufferSizeInBytes(max(min * 4, 32768))
                        .build()
                } catch (e: Exception) { null }
                if (r != null && r.state == AudioRecord.STATE_INITIALIZED) { rec = r; channels = if (st) 2 else 1; break }
                r?.release()
            }
            val r = rec ?: return "Impossible d'ouvrir l'entrée audio"
            if (dev != null) r.setPreferredDevice(dev)
            rate = r.sampleRate
            try { r.startRecording() } catch (e: Exception) { r.release(); return "L'entrée audio est occupée par une autre application" }
            record = r
            framesRead = 0; tsFrame = -1
            running = true
            _activeDevice.value = r.routedDevice?.let { d -> labelFor(d) } ?: (dev?.let { labelFor(it) } ?: "Micro du téléphone")
            worker = Thread({ loop(r) }, "mmp-audio").apply { priority = Thread.MAX_PRIORITY; start() }
            return null
        }
    }

    private fun labelFor(d: AudioDeviceInfo): String {
        val kind = kindOf(d)
        val name = d.productName?.toString()?.takeIf { it.isNotBlank() && it != android.os.Build.MODEL }
        return if (name == null) kind else "$name · $kind"
    }

    private fun shutdown() {
        val r: AudioRecord?; val w: Thread?
        synchronized(lock) { running = false; r = record; w = worker; record = null; worker = null }
        w?.join(800)
        r?.let { runCatching { it.stop() }; it.release() }
        _level.value = Level()
    }

    private fun loop(r: AudioRecord) {
        val frameBytes = 2 * channels
        val buf = ByteArray(frameBytes * (rate / 100) * 2) // 20 ms
        val gain = 10f.pow(prefs.gainDb / 20f)
        var lastLevelAt = 0L
        var peakAcc = 0f; var pl = 0f; var pr = 0f; var sumSq = 0.0; var n = 0; var clip = false; var idx = 0
        while (running) {
            val got = r.read(buf, 0, buf.size)
            if (got <= 0) {
                if (got == AudioRecord.ERROR_DEAD_OBJECT) { writeError = "L'entrée audio a été débranchée pendant l'enregistrement"; running = false }
                continue
            }
            val now = System.nanoTime()
            // gain, limiteur, mesure
            var i = 0
            while (i + 1 < got) {
                var s = ((buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)).toShort().toFloat() / 32768f
                s *= gain
                if (prefs.limiter && abs(s) > 0.89f) {
                    val sign = if (s < 0) -1f else 1f
                    s = sign * (0.89f + 0.11f * tanh((abs(s) - 0.89f) / 0.11f))
                }
                if (s > 0.9999f) s = 0.9999f; if (s < -1f) s = -1f
                val a = abs(s)
                if (a > peakAcc) peakAcc = a
                if (channels == 1 || idx % 2 == 0) { if (a > pl) pl = a }
                if (channels == 1 || idx % 2 == 1) { if (a > pr) pr = a }
                idx++
                if (a >= 0.985f) clip = true
                sumSq += (s * s).toDouble(); n++
                val v = (s * 32767f).toInt()
                buf[i] = (v and 0xFF).toByte(); buf[i + 1] = ((v shr 8) and 0xFF).toByte()
                i += 2
            }
            val frames = (got / frameBytes).toLong()
            synchronized(lock) {
                if (r.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) { tsFrame = ts.framePosition; tsNanos = ts.nanoTime }
                lastReadNanos = now
                val w = writer
                if (w != null) {
                    try { w.write(buf, 0, frames.toInt() * frameBytes) } catch (e: Exception) { writeError = "Écriture audio impossible : ${e.message}" }
                    if (clip) clippedBlocks++
                }
                framesRead += frames
            }
            if (now - lastLevelAt > 50_000_000L) {
                val rms = if (n > 0) sqrt(sumSq / n).toFloat() else 0f
                _level.value = Level(db(peakAcc), db(rms), clip, true, db(pl), db(pr))
                lastLevelAt = now; peakAcc = 0f; pl = 0f; pr = 0f; sumSq = 0.0; n = 0; clip = false; idx = 0
            }
        }
    }

    private fun db(x: Float): Float = if (x <= 1e-5f) -90f else (20 * log10(x))

    /** Démarre l'écriture WAV. Retourne un message d'erreur, ou null si tout va bien. */
    fun startRecording(file: File): String? {
        ensureRunning()?.let { return it }
        synchronized(lock) {
            writer = WavWriter(file, rate, channels)
            writerFile = file
            writerStartFrame = framesRead
            clippedBlocks = 0; writeError = null
        }
        return null
    }

    fun stopRecording(): Take? {
        val take: Take?
        synchronized(lock) {
            val w = writer ?: return null
            val f = writerFile!!
            val frames = w.framesWritten
            runCatching { w.close() }
            writer = null; writerFile = null
            // instant de la première trame écrite, déduit de l'horodatage matériel quand il existe
            val first = if (tsFrame >= 0) tsNanos + ((writerStartFrame - tsFrame) * 1_000_000_000L) / rate
                        else lastReadNanos - (framesRead - writerStartFrame) * 1_000_000_000L / rate
            take = Take(f, first, rate, channels, frames, _activeDevice.value, clippedBlocks, writeError)
        }
        if (monitorUsers == 0) shutdown()
        return take
    }

    fun isCapturing() = running
}
