package com.manumusicprod.playcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.BuildConfig
import com.manumusicprod.playcam.PlayCamApp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(setOrientation: (Int) -> Unit, onBack: () -> Unit) {
    val app = PlayCamApp.instance
    val prefs = app.prefs
    val caps = app.camera.caps.value
    var quality by remember { mutableStateOf(prefs.quality) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
    var orient by remember { mutableIntStateOf(prefs.orientationMode) }
    var front by remember { mutableStateOf(prefs.useFrontCamera) }
    var sync by remember { mutableFloatStateOf(prefs.syncOffsetMs.toFloat()) }
    var wm by remember { mutableStateOf(prefs.watermark) }
    var gallery by remember { mutableStateOf(prefs.saveToGallery) }
    val freeGb = app.store.freeBytes() / 1_000_000_000.0

    Column(Modifier.fillMaxSize()) {
        ScreenTopBar("Paramètres", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Eyebrow("Vidéo")
            MmpCard {
                Text("Qualité", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("UHD", "FHD", "HD").forEach { q ->
                        val ok = caps.qualities.contains(q) || q == "FHD"
                        Chip(when (q) { "UHD" -> "4K"; "FHD" -> "1080p"; else -> "720p" }, selected = quality == q, enabled = ok,
                            onClick = { quality = q; prefs.quality = q })
                    }
                }
                Text("Images par seconde", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("30 i/s", selected = fps == 30, onClick = { fps = 30; prefs.fps = 30 })
                    Chip("60 i/s", selected = fps == 60, onClick = { fps = 60; prefs.fps = 60 })
                }
                Text("Les choix non pris en charge par ton téléphone sont ajustés automatiquement à l'ouverture de la caméra (la 4K à 60 i/s n'existe que sur certains modèles).",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
                Text("Orientation", style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "Portrait", "Paysage").forEachIndexed { i, l ->
                        Chip(l, selected = orient == i, onClick = { orient = i; prefs.orientationMode = i; setOrientation(i) })
                    }
                }
                SettingSwitch("Caméra avant par défaut", null, front) { front = it; prefs.useFrontCamera = it }
            }

            Eyebrow("Synchronisation")
            MmpCard {
                SettingSlider("Décalage audio", (if (sync > 0) "+" else "") + "${sync.roundToInt()} ms", sync, -300f..300f, steps = 59,
                    onChange = { sync = it }, onDone = { prefs.syncOffsetMs = sync.roundToInt() })
                Text("L'audio, la vidéo et le MIDI sont calés automatiquement grâce à leurs horodatages. Chaque téléphone a toutefois sa propre latence : si le son arrive en avance ou en retard sur l'image, corrige-le ici (positif = retarder le son). Méthode : filme un coup sec sur une touche, regarde la vidéo, ajuste, recommence.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }

            Eyebrow("Enregistrements")
            MmpCard {
                SettingSwitch("Copier les vidéos dans la galerie", "Dossier Films › Manu Play Cam", gallery) { gallery = it; prefs.saveToGallery = it }
                SettingSwitch("Signature « MANU PLAY CAM »", "Petit texte en bas de la vidéo", wm) { wm = it; prefs.watermark = it }
                InfoLine("Espace libre", "%.1f Go".format(freeGb))
                InfoLine("Estimation", "≈ ${(freeGb * 1000 / 130).roundToInt()} min en 1080p · ≈ ${(freeGb * 1000 / 380).roundToInt()} min en 4K")
            }

            Eyebrow("À propos")
            MmpCard {
                Text("Manu Play Cam ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, color = Mmp.Ivory)
                Text("Une application MANU MUSIC PROD · Emmanuel NOUHOU", style = MaterialTheme.typography.bodySmall, color = Mmp.Soft)
                Text("Fichiers produits pour chaque prise : la vidéo MP4 (image + son de l'instrument + clavier incrusté), l'audio WAV d'origine et le fichier MIDI (.mid) calé sur la vidéo.",
                    style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}
