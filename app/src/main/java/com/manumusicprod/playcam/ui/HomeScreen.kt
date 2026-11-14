package com.manumusicprod.playcam.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.Screen

@Composable
fun HomeScreen(go: (Screen) -> Unit) {
    val app = PlayCamApp.instance
    val sources by app.midi.sources.collectAsState()
    val inputs by app.audio.inputs.collectAsState()
    val sessions by app.store.sessions.collectAsState()
    LaunchedEffect(Unit) { app.midi.refresh(); app.audio.refreshInputs(); app.store.reload() }
    val connected = sources.filter { it.connected }
    val audioLabel = inputs.firstOrNull { it.id == app.prefs.audioDeviceId }?.label
        ?: inputs.firstOrNull { it.external }?.let { "Auto · ${it.label}" } ?: "Auto · Micro du téléphone"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MmpLogo(30.dp)
            Spacer(Modifier.width(10.dp))
            Text("MANU MUSIC PROD", style = MaterialTheme.typography.labelSmall, color = Mmp.Ivory)
        }
        Spacer(Modifier.height(6.dp))
        Eyebrow("Vidéo · Audio · MIDI")
        Text(buildAnnotatedString {
            append("MANU\n")
            withStyle(SpanStyle(color = Mmp.Green)) { append("PLAY CAM") }
        }, style = MaterialTheme.typography.displaySmall, color = Mmp.Ivory)
        Text("Filme ton jeu. Le son de ton instrument, le MIDI, les notes et les accords sont enregistrés ensemble, au même tempo que l'image.",
            style = MaterialTheme.typography.bodyMedium, color = Mmp.Soft)

        // aperçu vivant du clavier
        MmpCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(connected.isNotEmpty())
                Spacer(Modifier.width(8.dp))
                Text(if (connected.isEmpty()) "Aucun clavier MIDI connecté" else connected.joinToString(" · ") { it.name },
                    style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().aspectRatio(3.2f)) { KeyboardPreview(Modifier.fillMaxSize()) }
            if (connected.isEmpty()) Text("Branche un clavier MIDI en USB (adaptateur OTG) ou connecte-le en Bluetooth : les touches s'allumeront ici.",
                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
        }

        PillButton("FILMER", onClick = { go(Screen.CAMERA) }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MmpCard(Modifier.weight(1f), highlight = connected.isNotEmpty(), onClick = { go(Screen.MIDI) }) {
                GlyphIcon("midi", Mmp.Green)
                Text("MIDI", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text(if (connected.isEmpty()) "USB, Bluetooth, ordinateur" else "${connected.size} source(s) active(s)", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.AUDIO) }) {
                GlyphIcon("mic", Mmp.Green)
                Text("Audio", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text(audioLabel, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute, maxLines = 2)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.KEYBOARD) }) {
                GlyphIcon("keys", Mmp.Green)
                Text("Clavier", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("Apparence et accords", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.GALLERY) }) {
                GlyphIcon("grid", Mmp.Green)
                Text("Galerie", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("${sessions.size} enregistrement(s)", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
        }
        MmpCard(onClick = { go(Screen.SETTINGS) }) {
            Text("Paramètres", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
            Text("Qualité vidéo, images par seconde, synchronisation, notation des accords", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
        }
        Spacer(Modifier.height(8.dp))
        Text("© MANU MUSIC PROD · Emmanuel NOUHOU", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
