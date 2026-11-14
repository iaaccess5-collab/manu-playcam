package com.manumusicprod.playcam.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.manumusicprod.playcam.PlayCamApp
import kotlin.math.roundToInt

@Composable
fun AudioScreen(onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val audio = app.audio
    val prefs = app.prefs
    val ctx = LocalContext.current
    val inputs by audio.inputs.collectAsState()
    val level by audio.level.collectAsState()
    val active by audio.activeDevice.collectAsState()

    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; if (ok) audio.startMonitor() }
    var selected by remember { mutableIntStateOf(prefs.audioDeviceId) }
    var gain by remember { mutableFloatStateOf(prefs.gainDb) }
    var rate by remember { mutableIntStateOf(prefs.sampleRate) }
    var stereo by remember { mutableStateOf(prefs.stereo) }
    var limiter by remember { mutableStateOf(prefs.limiter) }
    var raw by remember { mutableStateOf(prefs.rawAudio) }

    LaunchedEffect(Unit) { audio.refreshInputs(); if (!granted) launcher.launch(Manifest.permission.RECORD_AUDIO) }
    DisposableEffect(granted) {
        if (granted) audio.startMonitor()
        onDispose { if (granted) audio.stopMonitor() }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Configuration audio", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            MmpCard {
                Eyebrow("Niveau d'entrée")
                LevelMeter(level, Modifier.fillMaxWidth().height(16.dp))
                Row {
                    Text(if (!granted) "Micro non autorisé" else if (level.active) "Crête ${level.peakDb.roundToInt()} dB" else "En attente du signal…",
                        style = MaterialTheme.typography.labelMedium, color = if (level.clipping) Mmp.Red else Mmp.Soft, modifier = Modifier.weight(1f))
                    if (level.clipping) Text("SATURATION", style = MaterialTheme.typography.labelSmall, color = Mmp.Red)
                }
                InfoLine("Source utilisée", active.ifBlank { "—" })
                Text("Joue le passage le plus fort de ton morceau : la barre doit rester verte ou orange, jamais rouge.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }

            Eyebrow("Source audio")
            MmpCard {
                SourceRow("Automatique", "Interface USB si elle est branchée, sinon le micro du téléphone", selected == -1) {
                    selected = -1; prefs.audioDeviceId = -1; audio.restart()
                }
                inputs.forEach { i ->
                    SourceRow(i.label, if (i.external) "Entrée externe${if (i.stereo) " · stéréo possible" else ""}" else "Intégré au téléphone", selected == i.id) {
                        selected = i.id; prefs.audioDeviceId = i.id; audio.restart()
                    }
                }
                Text("Pour le son de ton clavier : relie ses sorties (LINE OUT / casque) à une interface audio USB compatible Android, branchée sur le téléphone avec un adaptateur USB-C / OTG. Elle apparaît ici dès qu'elle est reconnue.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }

            Eyebrow("Réglages")
            MmpCard {
                SettingSlider("Gain d'entrée", (if (gain > 0) "+" else "") + "${gain.roundToInt()} dB", gain, -12f..24f, steps = 35,
                    onChange = { gain = it }, onDone = { prefs.gainDb = gain; audio.restart() })
                Text("Gain numérique appliqué par l'application. Le niveau matériel d'une interface se règle sur l'interface elle-même.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                SettingSwitch("Limiteur anti-saturation", "Arrondit les crêtes avant qu'elles ne saturent", limiter) { limiter = it; prefs.limiter = it; audio.restart() }
                SettingSwitch("Son brut, sans traitement", if (audio.unprocessedSupported()) "Désactive l'égalisation et le contrôle automatique du téléphone" else "Ce téléphone ne propose pas de source sans traitement",
                    raw && audio.unprocessedSupported()) { raw = it; prefs.rawAudio = it; audio.restart() }
                SettingSwitch("Stéréo", "Si l'entrée le permet (sinon mono)", stereo) { stereo = it; prefs.stereo = it; audio.restart() }
                Text("Fréquence d'échantillonnage", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(44100, 48000).forEach { r ->
                        Chip(if (r == 44100) "44,1 kHz" else "48 kHz", selected = rate == r, onClick = { rate = r; prefs.sampleRate = r; audio.restart() })
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SourceRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Mmp.Green, unselectedColor = Mmp.Mute))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
        }
    }
}
