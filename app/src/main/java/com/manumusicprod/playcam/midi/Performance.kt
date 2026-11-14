package com.manumusicprod.playcam.midi

import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.core.ChordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * État musical en temps réel : touches enfoncées, notes tenues par la pédale, vélocités, accord.
 * Alimenté par toutes les sources MIDI, lu par le clavier virtuel (aperçu, vidéo, écrans).
 */
class Performance(private val prefs: Prefs) {

    class Snapshot(
        val down: BooleanArray,
        val sustained: BooleanArray,
        val velocity: IntArray,
        val chord: String?,
        val chordAgeMs: Long,
        val sustain: Boolean,
    ) {
        fun anyActive(): Boolean { for (i in 0 until 128) if (down[i] || sustained[i]) return true; return false }
    }

    private val lock = Any()
    private val down = BooleanArray(128)
    private val sustained = BooleanArray(128)
    private val velocity = IntArray(128)
    private var sustainOn = false
    private var chord: String? = null
    private var chordSince = 0L
    private var lastAllUpAt = 0L

    private val _tick = MutableStateFlow(0L)
    /** Change à chaque événement : permet aux écrans Compose de se redessiner. */
    val tick: StateFlow<Long> = _tick

    fun onMessage(status: Int, d1: Int, d2: Int) {
        val type = status and 0xF0
        synchronized(lock) {
            when (type) {
                0x90 -> if (d2 > 0) noteOn(d1, d2) else noteOff(d1)
                0x80 -> noteOff(d1)
                0xB0 -> when (d1) {
                    64 -> { // pédale de sustain
                        sustainOn = d2 >= 64
                        if (!sustainOn) for (i in 0 until 128) sustained[i] = false
                        updateChord()
                    }
                    120, 123 -> clearLocked() // all sound off / all notes off
                }
            }
        }
        _tick.value = _tick.value + 1
    }

    private fun noteOn(n: Int, v: Int) {
        if (n !in 0..127) return
        down[n] = true; sustained[n] = false; velocity[n] = v
        updateChord()
    }

    private fun noteOff(n: Int) {
        if (n !in 0..127) return
        down[n] = false
        if (sustainOn) sustained[n] = true
        updateChord()
    }

    private fun updateChord() {
        val notes = ArrayList<Int>()
        for (i in 0 until 128) if (down[i] || (prefs.chordWithSustain && sustained[i])) notes.add(i)
        if (notes.isEmpty()) { lastAllUpAt = System.currentTimeMillis(); return }
        val c = ChordDetector.detect(notes, prefs.chordSpelling)
        if (c != null) {
            if (c.name != chord) { chord = c.name; chordSince = System.currentTimeMillis() }
        } else if (notes.size >= 3) {
            chord = null
        }
    }

    private fun clearLocked() {
        for (i in 0 until 128) { down[i] = false; sustained[i] = false }
        sustainOn = false
        lastAllUpAt = System.currentTimeMillis()
    }

    fun clear() { synchronized(lock) { clearLocked(); chord = null }; _tick.value = _tick.value + 1 }

    fun snapshot(): Snapshot = synchronized(lock) {
        var anyDown = false
        for (i in 0 until 128) if (down[i] || (prefs.chordWithSustain && sustained[i])) { anyDown = true; break }
        val age = if (anyDown) 0L else System.currentTimeMillis() - lastAllUpAt
        Snapshot(down.copyOf(), sustained.copyOf(), velocity.copyOf(), chord, age, sustainOn)
    }
}
