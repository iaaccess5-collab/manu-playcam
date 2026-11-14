package com.manumusicprod.playcam

import android.app.Application
import com.manumusicprod.playcam.audio.AudioEngine
import com.manumusicprod.playcam.camera.CameraController
import com.manumusicprod.playcam.midi.MidiHub
import com.manumusicprod.playcam.midi.Performance
import com.manumusicprod.playcam.session.SessionRecorder
import com.manumusicprod.playcam.session.SessionStore
import com.manumusicprod.playcam.ui.KeyboardRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Objets partagés par toute l'application (une seule instance de chaque moteur). */
class PlayCamApp : Application() {
    lateinit var prefs: Prefs
    lateinit var performance: Performance
    lateinit var midi: MidiHub
    lateinit var audio: AudioEngine
    lateinit var store: SessionStore
    lateinit var renderer: KeyboardRenderer
    lateinit var camera: CameraController
    lateinit var recorder: SessionRecorder
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        performance = Performance(prefs)
        midi = MidiHub(this, prefs, performance)
        audio = AudioEngine(this, prefs)
        store = SessionStore(this)
        renderer = KeyboardRenderer(this, prefs)
        camera = CameraController(this, prefs, performance, renderer)
        recorder = SessionRecorder(this, prefs, midi, audio, store, scope)
        midi.start()
        store.reload()
    }

    companion object { lateinit var instance: PlayCamApp }
}
