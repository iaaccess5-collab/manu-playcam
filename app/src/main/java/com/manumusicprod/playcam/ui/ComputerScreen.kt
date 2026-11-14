package com.manumusicprod.playcam.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import kotlin.math.roundToInt

const val CONNECT_DOWNLOAD_URL = "https://github.com/iaaccess5-collab/manu-playcam/releases/latest"

@Composable
fun ComputerScreen(onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val midi = app.midi
    val prefs = app.prefs
    val ctx = LocalContext.current
    val computer by midi.computer.collectAsState()
    val usb by midi.usb.collectAsState()
    val found by midi.found.collectAsState()
    val level by midi.audioLevel.collectAsState()
    val log by midi.log.collectAsState()
    val msg by midi.message.collectAsState()
    var source by remember { mutableStateOf(prefs.audioSource) }
    var showGuide by remember { mutableStateOf(computer == null && found.isEmpty()) }
    var showCable by remember { mutableStateOf(false) }

    LaunchedEffect(msg) { msg?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); midi.consumeMessage() } }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Ordinateur", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            // ---------------- état de la liaison
            MmpCard(highlight = computer != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusDot(computer != null, 11.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (computer != null) "Connecté à $computer" else "Aucun ordinateur connecté",
                            style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                        if (computer != null) Text(if (usb) "Par le câble USB" else "Par le Wi-Fi",
                            style = MaterialTheme.typography.bodySmall, color = Mmp.Green)
                    }
                    if (computer != null && !usb) Chip("Déconnecter", selected = false, onClick = { midi.disconnect() })
                }
                Text("Ce téléphone s'appelle « ${midi.phoneName} » sur ton ordinateur.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                Spacer(Modifier.height(4.dp))
                Text("SON DE L'ORDINATEUR", style = MaterialTheme.typography.labelSmall, color = Mmp.Green)
                SignalMeter(level, Modifier.fillMaxWidth())
                Text(when {
                    !level.active -> "Pas de son reçu de l'ordinateur : la vidéo prendra le son du micro du téléphone."
                    level.clipping -> "Son reçu, mais il sature : baisse le volume de sortie du logiciel."
                    else -> "Son reçu · crête ${level.peakDb.roundToInt()} dB"
                }, style = MaterialTheme.typography.bodySmall, color = if (level.clipping) Mmp.Red else Mmp.Soft)
            }

            // ---------------- ordinateurs trouvés sur le Wi-Fi
            Eyebrow("Ordinateurs sur ton Wi-Fi")
            MmpCard {
                if (found.isEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SearchingDot()
                        Spacer(Modifier.width(10.dp))
                        Text("Recherche…", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                    }
                    Text("Ouvre Manu Play Cam Connect sur ton ordinateur. Il apparaîtra ici avec son nom, s'il est sur le même Wi-Fi que ce téléphone.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                }
                found.forEach { c ->
                    val on = computer == c.name && !usb
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        GlyphIcon("midi", if (on) Mmp.Green else Mmp.Soft)
                        Spacer(Modifier.width(12.dp))
                        Text(c.name, style = MaterialTheme.typography.titleSmall, color = if (on) Mmp.Green else Mmp.Ivory, modifier = Modifier.weight(1f))
                        if (on) Chip("Connecté", selected = true, onClick = {})
                        else Chip("Connecter", selected = false, enabled = !usb, onClick = { midi.connectTo(c) })
                    }
                }
                if (usb && found.isNotEmpty()) Text("Le câble est branché : il est utilisé à la place du Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
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
                SourceChoice("Automatique (recommandé)", "Le son de ton logiciel s'il arrive de l'ordinateur, sinon le micro du téléphone", source == "auto") {
                    source = "auto"; prefs.audioSource = "auto"
                }
                SourceChoice("Toujours le micro du téléphone", "Le son de la pièce, même si l'ordinateur envoie du son", source == "mic") {
                    source = "mic"; prefs.audioSource = "mic"
                }
            }

            // ---------------- installation de Connect
            Eyebrow("Installer Manu Play Cam Connect")
            MmpCard(onClick = { showGuide = !showGuide }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sur ton Mac ou ton PC Windows", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
                    Text(if (showGuide) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Mmp.Green)
                }
                if (showGuide) {
                    GuideStep("1", "Sur l'ordinateur, télécharge Manu Play Cam Connect (Mac ou Windows).")
                    GuideStep("2", "Mac : ouvre le fichier, glisse l'application dans Applications, puis fais un clic droit › Ouvrir la première fois. Autorise « Réseau local » et « Enregistrement de l'écran et audio système » (c'est ce qui permet de capter le son de Logic).")
                    GuideStep("3", "Windows : lance l'installateur. Si Windows affiche un avertissement, clique sur « Informations complémentaires › Exécuter quand même », puis autorise l'accès au réseau privé.")
                    GuideStep("4", "Ouvre Connect : ton ordinateur apparaît ici par son nom, et ce téléphone apparaît dans Connect. Touche « Connecter » d'un côté ou de l'autre. La fois suivante, ils se retrouvent tout seuls.")
                    GuideStep("5", "Ton clavier relié à l'ordinateur est capté directement. Depuis Logic, Ableton ou MainStage, tu peux aussi envoyer le MIDI d'une piste vers le port « Manu Play Cam ».")
                    PillButton("Ouvrir la page de téléchargement", onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(CONNECT_DOWNLOAD_URL)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, primary = false, modifier = Modifier.fillMaxWidth())
                }
            }

            // ---------------- câble USB
            Eyebrow("Sans Wi-Fi : par câble USB")
            MmpCard(onClick = { showCable = !showCable }, highlight = usb) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (usb) "Câble branché et relié" else "Relier avec un câble", style = MaterialTheme.typography.titleSmall, color = if (usb) Mmp.Green else Mmp.Ivory)
                        Text("Le son et le MIDI passent par le câble. Il passe avant le Wi-Fi quand il est branché.",
                            style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                    }
                    Text(if (showCable) "−" else "+", style = MaterialTheme.typography.titleLarge, color = Mmp.Green)
                }
                if (showCable) {
                    Text("À faire une seule fois :", style = MaterialTheme.typography.labelMedium, color = Mmp.Green)
                    GuideStep("1", "Paramètres du téléphone › À propos du téléphone : touche 7 fois « Numéro de build » (sur Samsung : Informations sur le logiciel › Numéro de version). Le message « Vous êtes développeur » apparaît.")
                    GuideStep("2", "Paramètres › Options pour les développeurs : active « Débogage USB ».")
                    PillButton("Ouvrir les options pour les développeurs", onClick = {
                        val ok = runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                        if (!ok) runCatching { ctx.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }, primary = false, modifier = Modifier.fillMaxWidth())
                    Text("Ensuite, à chaque fois :", style = MaterialTheme.typography.labelMedium, color = Mmp.Green)
                    GuideStep("3", "Ouvre Manu Play Cam Connect sur l'ordinateur et branche le câble USB.")
                    GuideStep("4", "La première fois, le téléphone demande « Autoriser le débogage USB ? » : coche « Toujours autoriser » et touche Autoriser.")
                    GuideStep("5", "C'est relié : « Par le câble USB » s'affiche en haut de cet écran.")
                    Text("Windows : si le téléphone n'est pas reconnu, installe le pilote USB de sa marque (Samsung, Xiaomi…), puis rebranche.",
                        style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                }
            }

            Text("Wi-Fi : si l'ordinateur n'apparaît pas, vérifie que les deux appareils sont sur le même Wi-Fi (pas un Wi-Fi « invité », qui isole les appareils) et que l'ordinateur autorise Manu Play Cam Connect sur le réseau. Sinon, utilise le câble.",
                style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SearchingDot() {
    val t = rememberInfiniteTransition(label = "search")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Box(Modifier.size(10.dp).background(Mmp.Green.copy(alpha = a), CircleShape))
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
