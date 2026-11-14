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
    val computer by app.midi.computer.collectAsState()
    val usb by app.midi.usb.collectAsState()
    val level by app.midi.audioLevel.collectAsState()
    val sessions by app.store.sessions.collectAsState()
    LaunchedEffect(Unit) { app.store.reload() }
    val linkName = computer

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
        Text("Relie ton ordinateur en Wi-Fi ou par câble, filme ton jeu : les notes, les accords et le son de ton logiciel entrent dans la vidéo, au même tempo que l'image.",
            style = MaterialTheme.typography.bodyMedium, color = Mmp.Soft)

        // aperçu vivant du clavier
        MmpCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(linkName != null)
                Spacer(Modifier.width(8.dp))
                Text(if (linkName == null) "Aucun ordinateur connecté" else "Connecté à $linkName" + (if (usb) " · câble" else " · Wi-Fi"),
                    style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
            }
            Box(Modifier.fillMaxWidth().aspectRatio(3.2f)) { KeyboardPreview(Modifier.fillMaxSize()) }
            if (linkName == null) Text("Ouvre Manu Play Cam Connect sur ton ordinateur (même Wi-Fi, ou câble USB) : ce que tu joues s'allumera ici.",
                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            if (computer != null) SignalMeter(level, Modifier.fillMaxWidth())
        }

        PillButton("FILMER", onClick = { go(Screen.CAMERA) }, modifier = Modifier.fillMaxWidth())

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MmpCard(Modifier.weight(1f), highlight = linkName != null, onClick = { go(Screen.COMPUTER) }) {
                GlyphIcon("midi", Mmp.Green)
                Text("Ordinateur", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text(when {
                    linkName == null -> "Wi-Fi ou câble"
                    level.active -> "MIDI + son reçus"
                    else -> "MIDI reçu"
                }, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute, maxLines = 2)
            }
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.KEYBOARD) }) {
                GlyphIcon("keys", Mmp.Green)
                Text("Clavier", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("Apparence et accords", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.GALLERY) }) {
                GlyphIcon("grid", Mmp.Green)
                Text("Galerie", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("${sessions.size} enregistrement(s)", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            MmpCard(Modifier.weight(1f), onClick = { go(Screen.SETTINGS) }) {
                GlyphIcon("sun", Mmp.Green)
                Text("Paramètres", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("Vidéo, synchronisation", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("© MANU MUSIC PROD · Emmanuel NOUHOU", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute, modifier = Modifier.align(Alignment.CenterHorizontally))
    }
}
