package com.manumusicprod.playcam.ui

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.midi.MidiHub
import com.manumusicprod.playcam.midi.NetworkMidiServer

@Composable
fun MidiScreen(onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val midi = app.midi
    val ctx = LocalContext.current
    val sources by midi.sources.collectAsState()
    val ble by midi.bleFound.collectAsState()
    val scanning by midi.scanning.collectAsState()
    val log by midi.log.collectAsState()
    val msg by midi.message.collectAsState()
    val netOn by midi.network.running.collectAsState()
    val netClient by midi.network.client.collectAsState()
    val tick by app.performance.tick.collectAsState()

    LaunchedEffect(Unit) { midi.refresh() }
    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_SHORT).show(); midi.consumeMessage() } }
    LaunchedEffect(netClient) { midi.refresh() }
    DisposableEffect(Unit) { onDispose { midi.stopBleScan() } }

    val blePerms = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
                   else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val bleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (midi.hasBlePermission()) midi.startBleScan() else Toast.makeText(ctx, "Autorisation Bluetooth refusée", Toast.LENGTH_SHORT).show()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Connexion MIDI", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            if (!midi.supported) {
                MmpCard { Text("Ce téléphone ne propose pas le MIDI d'Android (fonction android.software.midi absente). Le MIDI par le Wi-Fi reste utilisable.",
                    style = MaterialTheme.typography.bodyMedium, color = Mmp.Amber) }
            }

            // ---- moniteur
            MmpCard(highlight = tick > 0 && sources.any { it.connected }) {
                Eyebrow("Test en direct")
                Text("Joue quelques notes : elles doivent s'allumer ici, avec le nom de l'accord.", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                Box(Modifier.fillMaxWidth().aspectRatio(3.2f)) { KeyboardPreview(Modifier.fillMaxSize(), demoWhenIdle = false) }
                if (log.isNotEmpty()) {
                    Column(Modifier.fillMaxWidth()) {
                        log.take(6).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Mmp.Soft, fontFamily = FontFamily.Monospace) }
                    }
                }
            }

            // ---- appareils
            Eyebrow("Claviers et appareils")
            val devs = sources.filter { it.kind != MidiHub.Kind.NETWORK }
            if (devs.isEmpty()) {
                MmpCard {
                    Text("Aucun appareil MIDI détecté.", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                    Text("• USB : branche le clavier avec un câble USB et un adaptateur USB-C / OTG. Il apparaît ici automatiquement.\n" +
                         "• Bluetooth : utilise la recherche ci-dessous (claviers « Bluetooth MIDI » / BLE).\n" +
                         "• Ordinateur par câble : relie le téléphone à l'ordinateur et choisis « MIDI » dans les options USB d'Android.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                }
            }
            devs.forEach { s ->
                MmpCard(highlight = s.connected) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(s.connected)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.name, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                            Text("${s.kind.label} · ${s.ports} sortie(s) MIDI · ${if (s.connected) "connecté" else "non connecté"}",
                                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                        }
                        Chip(if (s.connected) "Déconnecter" else "Connecter", selected = !s.connected, onClick = {
                            if (s.connected) midi.disconnect(s.key) else midi.connect(s.key)
                        })
                    }
                }
            }
            PillButton("Actualiser la liste", onClick = { midi.refresh() }, primary = false, modifier = Modifier.fillMaxWidth())

            // ---- bluetooth
            Eyebrow("Clavier Bluetooth")
            MmpCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (scanning) "Recherche en cours…" else "Allume ton clavier en mode Bluetooth MIDI, puis lance la recherche.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Soft, modifier = Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.padding(start = 8.dp).width(20.dp).height(20.dp), color = Mmp.Green, strokeWidth = 2.dp)
                }
                PillButton(if (scanning) "Arrêter la recherche" else "Rechercher un clavier Bluetooth", onClick = {
                    if (scanning) midi.stopBleScan()
                    else if (midi.hasBlePermission()) midi.startBleScan() else bleLauncher.launch(blePerms)
                }, modifier = Modifier.fillMaxWidth(), enabled = midi.supported)
                ble.forEach { b ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(b.name, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                            Text(b.address, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                        }
                        Chip("Connecter", selected = true, onClick = { midi.connectBle(b.address) })
                    }
                }
            }

            // ---- ordinateur
            Eyebrow("Ordinateur (Logic, Ableton, MainStage…)")
            MmpCard(highlight = netClient != null) {
                SettingSwitch("Recevoir le MIDI par le Wi-Fi", "Avec le compagnon Manu Play Cam Bridge sur Mac ou Windows", netOn) { midi.setNetworkEnabled(it) }
                if (netOn) {
                    val ips = NetworkMidiServer.localAddresses()
                    InfoLine("Adresse du téléphone", if (ips.isEmpty()) "pas de Wi-Fi" else ips.joinToString("  ·  "))
                    InfoLine("Port", NetworkMidiServer.PORT.toString())
                    InfoLine("État", netClient?.let { "connecté à $it" } ?: "en attente de l'ordinateur")
                    Text("Le téléphone et l'ordinateur doivent être sur le même réseau Wi-Fi. Le compagnon trouve le téléphone tout seul ; sinon, saisis l'adresse ci-dessus.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                }
                Spacer(Modifier.height(4.dp))
                Text("Par câble, sans Wi-Fi : relie le téléphone à l'ordinateur en USB, choisis « MIDI » dans la notification USB d'Android, puis sélectionne le téléphone comme sortie MIDI dans ton logiciel. Il apparaît alors dans la liste des appareils ci-dessus.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
