package com.manumusicprod.playcam.ui

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.midi.WifiMidi
import kotlin.math.roundToInt

const val CONNECT_DOWNLOAD_URL = "https://github.com/iaaccess5-collab/manu-playcam/releases/latest"

@Composable
fun ComputerScreen(onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val midi = app.midi
    val prefs = app.prefs
    val ctx = LocalContext.current
    val computer by midi.computer.collectAsState()
    val level by midi.audioLevel.collectAsState()
    val log by midi.log.collectAsState()
    val msg by midi.message.collectAsState()
    val found by midi.wifi.found.collectAsState()
    val linked by midi.wifi.linked.collectAsState()
    val searching by midi.wifi.searching.collectAsState()
    val connecting by midi.wifi.connecting.collectAsState()
    val wifiInfo by midi.wifi.info.collectAsState()
    var source by remember { mutableStateOf(prefs.audioSource) }
    var sessionOn by remember { mutableStateOf(prefs.networkMidi) }
    var showSession by remember { mutableStateOf(linked.isNotEmpty()) }
    var showGuide by remember { mutableStateOf(computer == null) }
    var manualIp by remember { mutableStateOf("") }

    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); midi.consumeMessage() } }
    LaunchedEffect(wifiInfo) { wifiInfo?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); midi.wifi.consumeInfo() } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Ordinateur (Wi-Fi)", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // ---------------- état de la liaison
            MmpCard(highlight = computer != null || linked.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(computer != null || linked.isNotEmpty(), 11.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(when {
                        computer != null -> "Connecté à $computer"
                        linked.isNotEmpty() -> "MIDI connecté à ${linked.first().name}"
                        else -> "En attente de ton ordinateur…"
                    }, style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory, modifier = Modifier.weight(1f))
                }
                if (computer == null && linked.isEmpty()) {
                    Text("Ouvre Manu Play Cam Connect sur ton ordinateur, connecté au même Wi-Fi que ce téléphone : il le trouve tout seul.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Soft)
                }
                InfoLine("Ce téléphone", midi.phoneName)
                InfoLine("Adresse Wi-Fi", WifiMidi.localAddresses().joinToString("  ·  ").ifBlank { "pas de Wi-Fi" })
                Spacer(Modifier.height(4.dp))
                Text("SON DE L'ORDINATEUR", style = MaterialTheme.typography.labelSmall, color = Mmp.Green)
                SignalMeter(level, Modifier.fillMaxWidth())
                Text(when {
                    !level.active -> "Pas de son reçu de l'ordinateur : la vidéo prendra le son du micro du téléphone."
                    level.clipping -> "Son reçu, mais il sature : baisse le volume de sortie du logiciel."
                    else -> "Son reçu · crête ${level.peakDb.roundToInt()} dB"
                }, style = MaterialTheme.typography.bodySmall, color = if (level.clipping) Mmp.Red else Mmp.Soft)
            }

            // ---------------- test MIDI
            MmpCard {
                Eyebrow("Tes notes en direct")
                Text("Joue sur ton clavier ou lance ta session : les touches s'allument ici avec le nom de l'accord.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                Box(Modifier.fillMaxWidth().aspectRatio(3.2f)) { KeyboardPreview(Modifier.fillMaxSize(), demoWhenIdle = false) }
                log.take(4).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Mmp.Soft, fontFamily = FontFamily.Monospace) }
            }

            // ---------------- son de la vidéo
            Eyebrow("Son de la vidéo")
            MmpCard {
                SourceChoice("Automatique (recommandé)", "Le son de ton logiciel s'il arrive par Manu Play Cam Connect, sinon le micro du téléphone", source == "auto") {
                    source = "auto"; prefs.audioSource = "auto"
                }
                SourceChoice("Toujours le micro du téléphone", "Le son de la pièce, même si l'ordinateur envoie du son", source == "mic") {
                    source = "mic"; prefs.audioSource = "mic"
                }
            }

            // ---------------- installation
            Eyebrow("Installer Manu Play Cam Connect")
            MmpCard(onClick = { showGuide = !showGuide }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sur ton Mac ou ton PC Windows", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
                    Text(if (showGuide) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Mmp.Green)
                }
                if (showGuide) {
                    GuideStep("1", "Sur l'ordinateur, télécharge Manu Play Cam Connect (Mac ou Windows) depuis la page de téléchargement.")
                    GuideStep("2", "Mac : ouvre le fichier, glisse l'application dans Applications, puis fais un clic droit › Ouvrir la première fois. Autorise « Réseau local » et « Enregistrement de l'écran et audio système » (c'est ce qui permet de capter le son de Logic).")
                    GuideStep("3", "Windows : lance ManuPlayCam-Connect.exe. Si Windows affiche un avertissement, clique sur « Informations complémentaires › Exécuter quand même », puis autorise l'accès au réseau privé.")
                    GuideStep("4", "Mets l'ordinateur et ce téléphone sur le même Wi-Fi, ouvre Connect : le téléphone apparaît, la liaison se fait toute seule.")
                    GuideStep("5", "Ton clavier relié à l'ordinateur est capté directement. Depuis Logic, Ableton ou MainStage, tu peux aussi envoyer le MIDI d'une piste vers le port « Manu Play Cam ».")
                    PillButton("Ouvrir la page de téléchargement", onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CONNECT_DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, primary = false, modifier = Modifier.fillMaxWidth())
                    Text(CONNECT_DOWNLOAD_URL, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                }
            }

            // ---------------- option : MIDI seul par la session réseau
            MmpCard(onClick = { showSession = !showSession }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Option : MIDI seul, sans Connect", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                        Text("Par la session MIDI réseau du Mac ou rtpMIDI (Windows). Pas de son.", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                    }
                    Text(if (showSession) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Mmp.Green)
                }
            }
            if (showSession) {
                MmpCard {
                    SettingSwitch("Session MIDI réseau", "Le téléphone est visible sous le nom « ${midi.wifi.phoneName} »", sessionOn) {
                        sessionOn = it; midi.setSessionMidiEnabled(it)
                    }
                    if (sessionOn) {
                        linked.forEach { l ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(true)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(l.name, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                                    Text(l.host + if (l.latencyMs >= 0) " · latence ${"%.1f".format(l.latencyMs)} ms" else "", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                                }
                                Chip("Déconnecter", selected = false, onClick = { midi.wifi.disconnect(l.ssrc) })
                            }
                        }
                        Text(if (searching) "Ordinateurs trouvés sur le Wi-Fi" else "Recherche arrêtée",
                            style = MaterialTheme.typography.labelMedium, color = Mmp.Green)
                        if (found.isEmpty()) Text("Aucun pour l'instant. Sur Mac : Configuration audio et MIDI › Fenêtre › Afficher le studio MIDI › Réseau, crée une session (+), coche-la et choisis « Tout le monde ».",
                            style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                        found.forEach { c ->
                            val isLinked = linked.any { it.host == c.host }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(c.name, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                                    Text("${c.host} · port ${c.port}", style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                                }
                                Chip(when { isLinked -> "Connecté"; connecting == c.host -> "Connexion…"; else -> "Connecter" },
                                    selected = !isLinked, enabled = !isLinked && connecting == null, onClick = { midi.wifi.connect(c) })
                            }
                        }
                        PillButton("Rechercher à nouveau", onClick = { midi.wifi.searchAgain() }, primary = false, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = manualIp, onValueChange = { manualIp = it }, singleLine = true,
                            label = { Text("Adresse de l'ordinateur (ex. 192.168.1.20)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Mmp.Green, unfocusedBorderColor = Mmp.Ebony3,
                                focusedTextColor = Mmp.Ivory, unfocusedTextColor = Mmp.Ivory, focusedLabelColor = Mmp.Green, unfocusedLabelColor = Mmp.Mute),
                            modifier = Modifier.fillMaxWidth())
                        PillButton("Se connecter à cette adresse", enabled = manualIp.isNotBlank(), onClick = {
                            val parts = manualIp.trim().split(":")
                            midi.wifi.connectManual(parts[0], parts.getOrNull(1)?.toIntOrNull() ?: 5004)
                        }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Text("Si rien n'apparaît : vérifie que les deux appareils sont sur le même Wi-Fi (pas un Wi-Fi « invité », qui isole les appareils) et que le pare-feu de l'ordinateur autorise Manu Play Cam Connect.",
                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun GuideStep(n: String, text: String) {
    Row(Modifier.padding(vertical = 3.dp)) {
        Text(n, style = MaterialTheme.typography.titleSmall, color = Mmp.Green, modifier = Modifier.width(22.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = Mmp.Soft)
    }
}

@Composable
private fun SourceChoice(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = onClick, colors = RadioButtonDefaults.colors(selectedColor = Mmp.Green, unselectedColor = Mmp.Mute))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
        }
    }
}

