package com.manumusicprod.playcam.session

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** Les enregistrements : un dossier par performance (vidéo finale, audio WAV, fichier MIDI, informations). */
class SessionStore(private val context: Context) {

    data class Session(
        val id: String,
        val dir: File,
        val createdAt: Long,
        val durationMs: Long,
        val video: File?,
        val wav: File?,
        val midi: File?,
        val midiEvents: Int,
        val audioDevice: String,
        val midiDevices: String,
        val galleryUri: String?,
        val note: String?,
    )

    val root: File get() = File(context.getExternalFilesDir(null) ?: context.filesDir, "sessions").apply { mkdirs() }

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions

    fun newDir(stamp: String): File = File(root, stamp).apply { mkdirs() }

    fun reload() {
        _sessions.value = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { read(it) }.sortedByDescending { it.createdAt }
    }

    fun find(id: String): Session? = _sessions.value.firstOrNull { it.id == id } ?: read(File(root, id))

    private fun read(dir: File): Session? {
        val meta = File(dir, "session.json")
        val j = if (meta.exists()) runCatching { JSONObject(meta.readText()) }.getOrNull() else null
        val video = listOf("performance.mp4", "video.mp4").map { File(dir, it) }.firstOrNull { it.exists() && it.length() > 0 }
        if (video == null && j == null) return null
        return Session(
            id = dir.name, dir = dir,
            createdAt = j?.optLong("createdAt", dir.lastModified()) ?: dir.lastModified(),
            durationMs = j?.optLong("durationMs", 0) ?: 0,
            video = video,
            wav = File(dir, "audio.wav").takeIf { it.exists() },
            midi = File(dir, "performance.mid").takeIf { it.exists() },
            midiEvents = j?.optInt("midiEvents", 0) ?: 0,
            audioDevice = j?.optString("audioDevice", "") ?: "",
            midiDevices = j?.optString("midiDevices", "") ?: "",
            galleryUri = j?.optString("galleryUri")?.takeIf { it.isNotBlank() },
            note = j?.optString("note")?.takeIf { it.isNotBlank() },
        )
    }

    fun writeMeta(dir: File, values: Map<String, Any?>) {
        val f = File(dir, "session.json")
        val j = if (f.exists()) runCatching { JSONObject(f.readText()) }.getOrDefault(JSONObject()) else JSONObject()
        values.forEach { (k, v) -> j.put(k, v ?: JSONObject.NULL) }
        f.writeText(j.toString(2))
    }

    fun delete(s: Session) {
        s.galleryUri?.let { runCatching { context.contentResolver.delete(Uri.parse(it), null, null) } }
        s.dir.deleteRecursively()
        reload()
    }

    fun freeBytes(): Long = runCatching { StatFs(root.path).availableBytes }.getOrDefault(0L)

    fun uriFor(f: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)

    fun shareIntent(f: File, mime: String, title: String): Intent {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uriFor(f))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Copie la vidéo dans la galerie du téléphone (Films / Manu Play Cam). */
    fun exportVideoToGallery(f: File, name: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Manu Play Cam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) { resolver.delete(uri, null, null); null }
    }

    /** Copie un fichier (MIDI, WAV) dans Téléchargements / Manu Play Cam. */
    fun exportToDownloads(f: File, name: String, mime: String): Boolean {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Manu Play Cam")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values) ?: return false
        return try {
            resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            values.clear(); values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) { resolver.delete(uri, null, null); false }
    }
}
