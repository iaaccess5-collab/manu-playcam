package com.manumusicprod.playcam.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.session.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GalleryScreen(onBack: () -> Unit, open: (String) -> Unit) {
    val store = PlayCamApp.instance.store
    val sessions by store.sessions.collectAsState()
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { store.reload() } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Galerie", onBack)
        if (sessions.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                GlyphIcon("grid", Mmp.Mute, 40.dp)
                Spacer(Modifier.padding(6.dp))
                Text("Aucun enregistrement pour l'instant", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("Tes performances filmées apparaîtront ici, avec leur audio et leur fichier MIDI.", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            return
        }
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(sessions, key = { it.id }) { s -> SessionRow(s) { open(s.id) } }
        }
    }
}

@Composable
private fun SessionRow(s: SessionStore.Session, onClick: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, s.video) { value = withContext(Dispatchers.IO) { s.video?.let { thumbnail(it) } } }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Mmp.Ebony2).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 112.dp, height = 70.dp).clip(RoundedCornerShape(12.dp)).background(Mmp.Ebony3), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                ?: GlyphIcon("keys", Mmp.Mute)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(SimpleDateFormat("d MMM yyyy · HH:mm", Locale.FRANCE).format(Date(s.createdAt)), style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
            Text(duration(s.durationMs), style = MaterialTheme.typography.bodySmall, color = Mmp.Soft)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                Badge("VIDÉO", s.video != null)
                Badge("AUDIO", s.wav != null)
                Badge("MIDI", s.midi != null)
            }
        }
    }
}

@Composable
fun Badge(text: String, on: Boolean) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (on) Mmp.OnGreen else Mmp.Mute,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Mmp.Green else Mmp.Ebony3).padding(horizontal = 8.dp, vertical = 3.dp))
}

fun duration(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d h %02d min".format(s / 3600, (s % 3600) / 60) else "%d:%02d".format(s / 60, s % 60)
}

private fun thumbnail(f: File): Bitmap? = try {
    val r = MediaMetadataRetriever()
    r.setDataSource(f.path)
    val b = r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    r.release()
    b?.let { Bitmap.createScaledBitmap(it, 320, (320f * it.height / it.width).toInt().coerceAtLeast(1), true) }
} catch (e: Exception) { null }
