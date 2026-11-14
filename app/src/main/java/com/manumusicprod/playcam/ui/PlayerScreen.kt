package com.manumusicprod.playcam.ui

import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.manumusicprod.playcam.PlayCamApp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(sessionId: String?, onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val store = app.store
    val ctx = LocalContext.current
    val s = remember(sessionId) { sessionId?.let { store.find(it) } }
    var confirm by remember { mutableStateOf(false) }

    if (s == null) {
        Column(Modifier.fillMaxSize()) {
            ScreenTopBar("Lecture", onBack)
            Text("Cet enregistrement est introuvable.", color = Mmp.Soft, modifier = Modifier.padding(20.dp))
        }
        return
    }
    val player = remember(s.id) {
        ExoPlayer.Builder(ctx).build().apply {
            s.video?.let { setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(it))); prepare() }
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Lecture", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(Color.Black)) {
                AndroidView(factory = { c -> PlayerView(c).apply { this.player = player; useController = true } }, modifier = Modifier.fillMaxSize())
            }
            MmpCard {
                Text(SimpleDateFormat("EEEE d MMMM yyyy · HH:mm", Locale.FRANCE).format(Date(s.createdAt)).replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                InfoLine("Durée", duration(s.durationMs))
                InfoLine("Source audio", s.audioDevice.ifBlank { "—" })
                InfoLine("MIDI", if (s.midi != null) "${s.midiEvents} événements · ${s.midiDevices.ifBlank { "source MIDI" }}" else "aucun événement reçu")
                s.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Mmp.Amber) }
            }
            Eyebrow("Exporter")
            s.video?.let { v -> PillButton("Partager la vidéo", onClick = { ctx.startActivity(store.shareIntent(v, "video/mp4", "Partager la vidéo")) }, modifier = Modifier.fillMaxWidth()) }
            s.midi?.let { m ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Partager le MIDI", onClick = { ctx.startActivity(store.shareIntent(m, "audio/midi", "Partager le fichier MIDI")) }, primary = false, modifier = Modifier.weight(1f))
                    PillButton("Téléchargements", onClick = {
                        val ok = store.exportToDownloads(m, "ManuPlayCam_${s.id}.mid", "audio/midi")
                        Toast.makeText(ctx, if (ok) "MIDI enregistré dans Téléchargements › Manu Play Cam" else "Copie impossible", Toast.LENGTH_LONG).show()
                    }, primary = false, modifier = Modifier.weight(1f))
                }
            }
            s.wav?.let { w ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PillButton("Partager l'audio WAV", onClick = { ctx.startActivity(store.shareIntent(w, "audio/wav", "Partager l'audio")) }, primary = false, modifier = Modifier.weight(1f))
                    PillButton("Téléchargements", onClick = {
                        val ok = store.exportToDownloads(w, "ManuPlayCam_${s.id}.wav", "audio/wav")
                        Toast.makeText(ctx, if (ok) "Audio enregistré dans Téléchargements › Manu Play Cam" else "Copie impossible", Toast.LENGTH_LONG).show()
                    }, primary = false, modifier = Modifier.weight(1f))
                }
            }
            Text("Le fichier MIDI commence au même instant que la vidéo : glisse-le dans Logic, Ableton ou ton logiciel au début de la piste vidéo pour retrouver chaque note.",
                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            Spacer(Modifier.height(4.dp))
            PillButton("Supprimer cet enregistrement", onClick = { confirm = true }, primary = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Supprimer définitivement ?") },
            text = { Text("La vidéo, l'audio et le fichier MIDI de cette prise seront effacés du téléphone, ainsi que la copie dans la galerie.") },
            confirmButton = { TextButton(onClick = { confirm = false; player.release(); store.delete(s); onBack() }) { Text("Supprimer", color = Mmp.Red) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Annuler", color = Mmp.Ivory) } },
            containerColor = Mmp.Ebony2, titleContentColor = Mmp.Ivory, textContentColor = Mmp.Soft,
        )
    }
}
