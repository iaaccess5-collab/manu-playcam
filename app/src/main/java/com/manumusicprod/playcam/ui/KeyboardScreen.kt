package com.manumusicprod.playcam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import kotlin.math.roundToInt

private data class KbRange(val label: String, val low: Int, val high: Int)
private val RANGES = listOf(
    KbRange("88 touches", 21, 108), KbRange("76 touches", 28, 103), KbRange("61 touches", 36, 96),
    KbRange("49 touches", 36, 84), KbRange("37 touches", 48, 84), KbRange("25 touches", 48, 72),
)
private val COLORS = listOf(
    0xFF59C35F.toInt() to "Vert MMP", 0xFFF3EFE4.toInt() to "Ivoire", 0xFF3FA9F5.toInt() to "Bleu",
    0xFFE9B44C.toInt() to "Or", 0xFFE5484D.toInt() to "Rouge", 0xFFA78BFA.toInt() to "Violet",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KeyboardScreen(onBack: () -> Unit) {
    val prefs = PlayCamApp.instance.prefs
    // « version » force le redessin de l'aperçu après chaque réglage
    var version by remember { mutableIntStateOf(0) }
    fun bump() { version++ }
    var low by remember { mutableIntStateOf(prefs.kbLowNote) }
    var names by remember { mutableStateOf(prefs.kbShowNames) }
    var french by remember { mutableStateOf(prefs.kbFrenchNames) }
    var opacity by remember { mutableFloatStateOf(prefs.kbOpacity) }
    var color by remember { mutableIntStateOf(prefs.kbActiveColor) }
    var chord by remember { mutableStateOf(prefs.kbShowChord) }
    var chordScale by remember { mutableFloatStateOf(prefs.kbChordScale) }
    var vel by remember { mutableStateOf(prefs.kbShowVelocity) }
    var spelling by remember { mutableIntStateOf(prefs.chordSpelling) }
    var withSus by remember { mutableStateOf(prefs.chordWithSustain) }

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Clavier virtuel", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            MmpCard {
                Eyebrow("Aperçu")
                Box(Modifier.fillMaxWidth().aspectRatio(2.6f).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(Color(0xFF3A3F36)).padding(10.dp)) {
                    KeyboardPreview(Modifier.fillMaxSize(), version = version)
                }
                Text("Sans clavier connecté, l'aperçu montre un accord de Cmaj7. Pour placer le clavier sur la vidéo, utilise le bouton ✥ de l'écran caméra.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }

            Eyebrow("Étendue")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RANGES.forEach { r ->
                    Chip(r.label, selected = low == r.low && prefs.kbHighNote == r.high, onClick = {
                        low = r.low; prefs.kbLowNote = r.low; prefs.kbHighNote = r.high; bump()
                    })
                }
            }

            Eyebrow("Couleur des touches jouées")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                COLORS.forEach { (c, label) ->
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(Color(c))
                            .border(3.dp, if (color == c) Mmp.Ivory else Color.Transparent, CircleShape)
                            .clickable { color = c; prefs.kbActiveColor = c; bump() })
                        Text(label, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                    }
                }
            }

            MmpCard {
                SettingSwitch("Afficher le nom des notes", "Sur les touches jouées", names) { names = it; prefs.kbShowNames = it; bump() }
                SettingSwitch("Notation française", "Do, Ré, Mi… au lieu de C, D, E…", french) { french = it; prefs.kbFrenchNames = it; bump() }
                SettingSwitch("Intensité (vélocité)", "La force de frappe éclaire la touche", vel) { vel = it; prefs.kbShowVelocity = it; bump() }
                SettingSlider("Opacité du clavier", "${(opacity * 100).roundToInt()} %", opacity, 0.2f..1f,
                    onChange = { opacity = it; prefs.kbOpacity = it; bump() })
            }

            Eyebrow("Accords")
            MmpCard {
                SettingSwitch("Afficher l'accord", "Au-dessus du clavier, pendant que tu joues", chord) { chord = it; prefs.kbShowChord = it; bump() }
                SettingSlider("Taille de l'accord", "${(chordScale * 100).roundToInt()} %", chordScale, 0.6f..1.8f,
                    onChange = { chordScale = it; prefs.kbChordScale = it; bump() })
                SettingSwitch("Compter les notes tenues à la pédale", "Désactivé : seules les touches enfoncées comptent (plus précis)", withSus) {
                    withSus = it; prefs.chordWithSustain = it; bump()
                }
                Text("Écriture des altérations", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Usuelle (A♭, F♯)", "Bémols", "Dièses").forEachIndexed { i, l ->
                        Chip(l, selected = spelling == i, onClick = { spelling = i; prefs.chordSpelling = i; bump() })
                    }
                }
                Text("Les accords sont identifiés à partir des notes MIDI reçues : c'est la méthode la plus fiable. Avec un simple micro, sans MIDI, l'application n'affiche pas d'accord plutôt que d'en deviner un faux.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            PillButton("Replacer le clavier en haut de l'image", onClick = { prefs.kbX = 0.04f; prefs.kbY = 0.10f; prefs.kbW = 0.92f; bump() },
                primary = false, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))
        }
    }
}
