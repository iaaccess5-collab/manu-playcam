package com.manumusicprod.playcam

import android.content.Context
import android.content.SharedPreferences

/** Réglages persistants de l'application. */
class Prefs(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("playcam", Context.MODE_PRIVATE)

    // Vidéo
    var quality: String get() = sp.getString("quality", "FHD") ?: "FHD"; set(v) = sp.edit().putString("quality", v).apply()
    var fps: Int get() = sp.getInt("fps", 30); set(v) = sp.edit().putInt("fps", v).apply()
    var useFrontCamera: Boolean get() = sp.getBoolean("front", false); set(v) = sp.edit().putBoolean("front", v).apply()
    /** 0 = suit le téléphone, 1 = portrait, 2 = paysage */
    var orientationMode: Int get() = sp.getInt("orient", 0); set(v) = sp.edit().putInt("orient", v).apply()

    // Audio : "auto" = son de l'ordinateur s'il arrive par le Wi-Fi, sinon micro du téléphone ; "mic" = toujours le micro
    var audioSource: String get() = if (sp.getString("audioSrc", "auto") == "mic") "mic" else "auto"; set(v) = sp.edit().putString("audioSrc", v).apply()
    var audioDeviceId: Int get() = sp.getInt("audioDev", -1); set(v) = sp.edit().putInt("audioDev", v).apply()
    var sampleRate: Int get() = sp.getInt("rate", 48000); set(v) = sp.edit().putInt("rate", v).apply()
    var stereo: Boolean get() = sp.getBoolean("stereo", true); set(v) = sp.edit().putBoolean("stereo", v).apply()
    var gainDb: Float get() = sp.getFloat("gainDb", 0f); set(v) = sp.edit().putFloat("gainDb", v).apply()
    var limiter: Boolean get() = sp.getBoolean("limiter", true); set(v) = sp.edit().putBoolean("limiter", v).apply()
    var rawAudio: Boolean get() = sp.getBoolean("raw", true); set(v) = sp.edit().putBoolean("raw", v).apply()

    // Synchronisation : décalage appliqué à l'audio par rapport à l'image (ms, positif = audio retardé)
    var syncOffsetMs: Int get() = sp.getInt("syncMs", 0); set(v) = sp.edit().putInt("syncMs", v).apply()

    // Clavier virtuel
    var kbVisible: Boolean get() = sp.getBoolean("kbVis", true); set(v) = sp.edit().putBoolean("kbVis", v).apply()
    var kbLowNote: Int get() = sp.getInt("kbLow", 21); set(v) = sp.edit().putInt("kbLow", v).apply()
    var kbHighNote: Int get() = sp.getInt("kbHigh", 108); set(v) = sp.edit().putInt("kbHigh", v).apply()
    var kbAutoRange: Boolean get() = sp.getBoolean("kbAuto", false); set(v) = sp.edit().putBoolean("kbAuto", v).apply()
    var kbShowNames: Boolean get() = sp.getBoolean("kbNames", true); set(v) = sp.edit().putBoolean("kbNames", v).apply()
    var kbFrenchNames: Boolean get() = sp.getBoolean("kbFr", false); set(v) = sp.edit().putBoolean("kbFr", v).apply()
    var kbOpacity: Float get() = sp.getFloat("kbOpacity", 0.92f); set(v) = sp.edit().putFloat("kbOpacity", v).apply()
    var kbActiveColor: Int get() = sp.getInt("kbColor", 0xFF59C35F.toInt()); set(v) = sp.edit().putInt("kbColor", v).apply()
    var kbShowChord: Boolean get() = sp.getBoolean("kbChord", true); set(v) = sp.edit().putBoolean("kbChord", v).apply()
    var kbChordScale: Float get() = sp.getFloat("kbChordScale", 1f); set(v) = sp.edit().putFloat("kbChordScale", v).apply()
    var kbShowVelocity: Boolean get() = sp.getBoolean("kbVel", true); set(v) = sp.edit().putBoolean("kbVel", v).apply()
    /** Position du clavier dans l'image, en fractions de l'aperçu (0..1). */
    var kbX: Float get() = sp.getFloat("kbX", 0.04f); set(v) = sp.edit().putFloat("kbX", v).apply()
    var kbY: Float get() = sp.getFloat("kbY", 0.10f); set(v) = sp.edit().putFloat("kbY", v).apply()
    var kbW: Float get() = sp.getFloat("kbW", 0.92f); set(v) = sp.edit().putFloat("kbW", v).apply()

    // Accords
    var chordSpelling: Int get() = sp.getInt("spell", 0); set(v) = sp.edit().putInt("spell", v).apply()
    var chordWithSustain: Boolean get() = sp.getBoolean("chordSus", false); set(v) = sp.edit().putBoolean("chordSus", v).apply()

    // Réseau / divers
    var lastComputer: String get() = sp.getString("lastPc", "") ?: ""; set(v) = sp.edit().putString("lastPc", v).apply()
    var networkMidi: Boolean get() = sp.getBoolean("netMidi", true); set(v) = sp.edit().putBoolean("netMidi", v).apply()
    var watermark: Boolean get() = sp.getBoolean("wm", true); set(v) = sp.edit().putBoolean("wm", v).apply()
    var saveToGallery: Boolean get() = sp.getBoolean("toGallery", true); set(v) = sp.edit().putBoolean("toGallery", v).apply()
}
