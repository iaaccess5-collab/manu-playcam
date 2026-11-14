package com.manumusicprod.playcam.session

import android.content.Context
import androidx.camera.video.Recording
import androidx.camera.video.VideoRecordEvent
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.audio.AudioEngine
import com.manumusicprod.playcam.camera.CameraController
import com.manumusicprod.playcam.core.MidiFileWriter
import com.manumusicprod.playcam.midi.MidiHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Enregistre une performance : vidéo (caméra + incrustation), audio de l'instrument (WAV) et MIDI,
 * sur une même horloge (System.nanoTime), puis assemble le MP4 final et écrit le fichier .mid.
 *
 * Synchronisation :
 *  - début réel de la vidéo = min(instant de l'événement − durée déjà enregistrée) sur les premiers événements ;
 *  - première trame audio = horodatage matériel AudioRecord (AudioTimestamp) ;
 *  - MIDI = horodatage du message reçu ;
 *  - un réglage manuel (Paramètres > Synchronisation) corrige la latence propre à chaque appareil.
 * Toutes les positions étant mesurées depuis le même point, il n'y a pas de dérive cumulative.
 */
class SessionRecorder(
    private val context: Context,
    private val prefs: Prefs,
    private val midi: MidiHub,
    private val audio: AudioEngine,
    private val store: SessionStore,
    private val scope: CoroutineScope,
) {
    sealed class State {
        object Idle : State()
        data class Recording(val startedAtMs: Long) : State()
        data class Processing(val progress: Float) : State()
        data class Done(val sessionId: String, val warning: String?) : State()
        data class Failed(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private var recording: Recording? = null
    private var dir: File? = null
    private val events = ArrayList<LongArray>()
    @Volatile private var capturing = false
    private var videoStartEstimate = Long.MAX_VALUE
    private var startEventNs = 0L
    private var statusCount = 0
    private var stamp = ""

    private val listener = MidiHub.RawListener { s, a, b, t ->
        if (capturing) synchronized(events) { events.add(longArrayOf(t, s.toLong(), a.toLong(), b.toLong())) }
    }

    val isBusy: Boolean get() = _state.value is State.Recording || _state.value is State.Processing

    /** Démarre. Retourne un message à afficher si l'enregistrement est impossible. */
    fun start(camera: CameraController): String? {
        if (isBusy) return null
        val free = store.freeBytes()
        if (free in 1 until MIN_FREE_BYTES) return "Espace de stockage insuffisant (${free / 1_000_000} Mo libres). Libère de la place pour filmer."
        if (!audio.hasPermission()) return "Autorise le micro pour enregistrer le son."

        stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.FRANCE).format(Date())
        val d = store.newDir(stamp)
        dir = d
        audio.startRecording(File(d, "audio.wav"))?.let { d.deleteRecursively(); return it }

        synchronized(events) { events.clear() }
        videoStartEstimate = Long.MAX_VALUE; startEventNs = 0L; statusCount = 0
        // notes déjà tenues au moment du départ : elles figurent dans le fichier MIDI dès 0
        val now = System.nanoTime()
        val snap = midi.performance.snapshot()
        synchronized(events) {
            for (n in 0 until 128) if (snap.down[n]) events.add(longArrayOf(now, 0x90L, n.toLong(), snap.velocity[n].coerceAtLeast(1).toLong()))
        }
        capturing = true
        midi.addListener(listener)

        val rec = camera.startRecording(File(d, "video_raw.mp4")) { e -> onVideoEvent(e) }
        if (rec == null) {
            cleanupFailed("La caméra n'est pas prête.")
            return "La caméra n'est pas prête. Réessaie dans un instant."
        }
        recording = rec
        _state.value = State.Recording(System.currentTimeMillis())
        return null
    }

    fun stop() { recording?.stop(); recording = null }

    private fun onVideoEvent(e: VideoRecordEvent) {
        val now = System.nanoTime()
        when (e) {
            is VideoRecordEvent.Start -> startEventNs = now
            is VideoRecordEvent.Status -> {
                val dur = e.recordingStats.recordedDurationNanos
                if (dur > 0 && statusCount < 20) { videoStartEstimate = minOf(videoStartEstimate, now - dur); statusCount++ }
            }
            is VideoRecordEvent.Finalize -> onFinalize(e)
            else -> {}
        }
    }

    private fun onFinalize(e: VideoRecordEvent.Finalize) {
        capturing = false
        midi.removeListener(listener)
        val take = audio.stopRecording()
        val d = dir ?: return
        val raw = File(d, "video_raw.mp4")
        val durNs = e.recordingStats.recordedDurationNanos
        if (!raw.exists() || raw.length() == 0L || durNs <= 0) {
            cleanupFailed("La vidéo n'a pas pu être enregistrée (code ${e.error}).")
            return
        }
        val videoStart = if (videoStartEstimate != Long.MAX_VALUE) videoStartEstimate else startEventNs
        val evs = synchronized(events) { ArrayList(events) }
        _state.value = State.Processing(0f)
        scope.launch(Dispatchers.IO) { process(d, raw, take, videoStart, durNs, evs) }
    }

    private fun process(d: File, raw: File, take: AudioEngine.Take?, videoStart: Long, durNs: Long, evs: List<LongArray>) {
        val warnings = ArrayList<String>()
        // ---- MIDI
        val w = MidiFileWriter()
        val on = BooleanArray(16 * 128)
        for (ev in evs) {
            val st = ev[1].toInt(); val a = ev[2].toInt(); val b = ev[3].toInt()
            w.add(ev[0] - videoStart, st, a, b)
            val ch = st and 0x0F
            when (st and 0xF0) {
                0x90 -> on[ch * 128 + a] = b > 0
                0x80 -> on[ch * 128 + a] = false
            }
        }
        for (i in on.indices) if (on[i]) w.add(durNs, 0x80 or (i / 128), i % 128, 0) // ferme les notes encore tenues
        val midiFile = File(d, "performance.mid")
        if (w.size() > 0) runCatching { w.write(midiFile, "Manu Play Cam", durNs) }.onFailure { warnings.add("Fichier MIDI non écrit") }

        // ---- assemblage audio + vidéo
        val final = File(d, "performance.mp4")
        var offsetNs = 0L
        if (take != null && take.frames > 0) {
            offsetNs = take.firstFrameNanos - videoStart + prefs.syncOffsetMs * 1_000_000L
            try {
                AvMuxer.mux(raw, take.file, final, offsetNs) { p -> _state.value = State.Processing(p) }
                raw.delete()
            } catch (e: Exception) {
                warnings.add("Assemblage audio impossible : la vidéo est gardée sans le son de l'instrument.")
                final.delete(); raw.renameTo(File(d, "video.mp4"))
            }
            take.error?.let { warnings.add(it) }
            if (take.clippedBlocks > 0) warnings.add("Saturation détectée à ${take.clippedBlocks} reprises : baisse le gain d'entrée.")
        } else {
            raw.renameTo(File(d, "video.mp4"))
            warnings.add("Aucun audio capté : vérifie la source audio.")
        }
        val video = if (final.exists()) final else File(d, "video.mp4")

        // ---- galerie du téléphone
        var galleryUri: String? = null
        if (prefs.saveToGallery && video.exists()) {
            galleryUri = store.exportVideoToGallery(video, "ManuPlayCam_$stamp.mp4")?.toString()
            if (galleryUri == null) warnings.add("Copie dans la galerie impossible")
        }

        val midiNames = midi.sources.value.filter { it.connected }.joinToString(", ") { it.name }
        store.writeMeta(d, mapOf(
            "createdAt" to System.currentTimeMillis(),
            "durationMs" to durNs / 1_000_000L,
            "midiEvents" to evs.size,
            "audioDevice" to (take?.deviceLabel ?: ""),
            "audioRate" to (take?.sampleRate ?: 0),
            "audioChannels" to (take?.channels ?: 0),
            "audioOffsetMs" to offsetNs / 1_000_000L,
            "syncCorrectionMs" to prefs.syncOffsetMs,
            "midiDevices" to midiNames,
            "galleryUri" to galleryUri,
            "note" to warnings.joinToString(" "),
        ))
        store.reload()
        _state.value = State.Done(d.name, warnings.firstOrNull())
    }

    private fun cleanupFailed(msg: String) {
        capturing = false
        midi.removeListener(listener)
        audio.stopRecording()
        dir?.deleteRecursively()
        _state.value = State.Failed(msg)
    }

    fun acknowledge() { if (_state.value is State.Done || _state.value is State.Failed) _state.value = State.Idle }

    companion object { const val MIN_FREE_BYTES = 500L * 1024 * 1024 }
}
