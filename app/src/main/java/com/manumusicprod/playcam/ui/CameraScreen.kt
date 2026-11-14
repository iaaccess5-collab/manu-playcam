package com.manumusicprod.playcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.Screen
import com.manumusicprod.playcam.camera.CameraController
import com.manumusicprod.playcam.session.SessionRecorder
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun CameraScreen(go: (Screen) -> Unit, setOrientation: (Int) -> Unit, autoRecordSeconds: Int = 0, openSession: (String) -> Unit) {
    val app = PlayCamApp.instance
    val ctx = LocalContext.current
    val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    fun allGranted() = perms.all { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    var granted by remember { mutableStateOf(allGranted()) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = allGranted() }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(perms) }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Eyebrow("Autorisations")
            Spacer(Modifier.height(10.dp))
            Text("La caméra et le micro sont nécessaires pour filmer et enregistrer le son.", style = MaterialTheme.typography.titleLarge, color = Mmp.Ivory)
            Spacer(Modifier.height(18.dp))
            PillButton("Autoriser", onClick = { launcher.launch(perms) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            PillButton("Retour", onClick = { go(Screen.HOME) }, primary = false, modifier = Modifier.fillMaxWidth())
        }
        return
    }
    CameraContent(go, setOrientation, autoRecordSeconds, openSession)
}

@Composable
private fun CameraContent(go: (Screen) -> Unit, setOrientation: (Int) -> Unit, autoRecordSeconds: Int, openSession: (String) -> Unit) {
    val app = PlayCamApp.instance
    val prefs = app.prefs
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val density = LocalDensity.current

    val previewView = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
    }
    LaunchedEffect(previewView) { app.camera.bind(owner, previewView) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        app.audio.startMonitor()
        onDispose {
            view.keepScreenOn = false
            app.audio.stopMonitor()
            if (!app.recorder.isBusy) app.camera.unbind()
        }
    }

    val caps by app.camera.caps.collectAsState()
    val zoom by app.camera.zoom.collectAsState()
    val exposure by app.camera.exposure.collectAsState()
    val camError by app.camera.error.collectAsState()
    val rec by app.recorder.state.collectAsState()
    val micLevel by app.audio.level.collectAsState()
    val pcLevel by app.midi.audioLevel.collectAsState()
    // son qui sera enregistré : celui de l'ordinateur s'il arrive (mode automatique), sinon le micro
    val level = if (prefs.audioSource == "auto" && pcLevel.active) pcLevel else micLevel
    val computer by app.midi.computer.collectAsState()
    val linked by app.midi.wifi.linked.collectAsState()
    val lastMidi by app.midi.lastEventAt.collectAsState()
    val content by app.camera.content.collectAsState()
    val midiOn = computer != null || linked.isNotEmpty()
    val recording = rec is SessionRecorder.State.Recording
    val busy = recording || rec is SessionRecorder.State.Processing

    var kbVisible by remember { mutableStateOf(prefs.kbVisible) }
    var editKb by remember { mutableStateOf(false) }
    var showExposure by remember { mutableStateOf(false) }
    var kbX by remember { mutableFloatStateOf(prefs.kbX) }
    var kbY by remember { mutableFloatStateOf(prefs.kbY) }
    var kbW by remember { mutableFloatStateOf(prefs.kbW) }
    var focus by remember { mutableStateOf<Offset?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var orient by remember { mutableStateOf(prefs.orientationMode) }

    LaunchedEffect(camError) { camError?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); app.camera.clearError() } }
    LaunchedEffect(recording) { while (recording) { now = System.currentTimeMillis(); delay(250) } }
    LaunchedEffect(focus) { if (focus != null) { delay(1200); focus = null } }
    // enregistrement automatique (tests de la compilation)
    LaunchedEffect(autoRecordSeconds) {
        if (autoRecordSeconds > 0) {
            delay(5000)
            if (app.recorder.start(app.camera) == null) { delay(autoRecordSeconds * 1000L); app.recorder.stop() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fullW = with(density) { maxWidth.toPx() }
            val fullH = with(density) { maxHeight.toPx() }
            // le clavier est positionné dans la zone réellement filmée (hors bandes noires)
            val cL = if (content.width() > 0f) content.left else 0f
            val cT = if (content.height() > 0f) content.top else 0f
            val wPx = if (content.width() > 0f) content.width() else fullW
            val hPx = if (content.height() > 0f) content.height() else fullH
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize().pointerInput(editKb) {
                if (!editKb) detectTapGestures { o -> app.camera.focusAt(o.x, o.y); focus = o }
            })
            focus?.let { f ->
                Box(Modifier.offset { IntOffset((f.x - 36.dp.toPx()).roundToInt(), (f.y - 36.dp.toPx()).roundToInt()) }
                    .size(72.dp).border(2.dp, Mmp.Ivory, RoundedCornerShape(12.dp)))
            }
            // cadre de déplacement / redimensionnement du clavier (le clavier lui-même est dessiné dans l'image)
            if (editKb && kbVisible) {
                val blockH = app.renderer.blockHeight(kbW * wPx)
                Box(
                    Modifier.offset { IntOffset((cL + kbX * wPx).roundToInt(), (cT + kbY * hPx).roundToInt()) }
                        .size(with(density) { (kbW * wPx).toDp() }, with(density) { blockH.toDp() })
                        .border(2.dp, Mmp.Green, RoundedCornerShape(10.dp))
                        .pointerInput(Unit) {
                            detectDragGestures(onDragEnd = { prefs.kbX = kbX; prefs.kbY = kbY }) { change, d ->
                                change.consume()
                                kbX = (kbX + d.x / wPx).coerceIn(-0.1f, 1f - kbW * 0.5f)
                                kbY = (kbY + d.y / hPx).coerceIn(0f, 0.95f)
                                prefs.kbX = kbX; prefs.kbY = kbY
                            }
                        },
                ) {
                    Box(
                        Modifier.align(Alignment.CenterEnd).offset(x = 14.dp).size(28.dp).clip(CircleShape).background(Mmp.Green)
                            .pointerInput(Unit) {
                                detectDragGestures(onDragEnd = { prefs.kbW = kbW }) { change, d ->
                                    change.consume()
                                    kbW = (kbW + d.x / wPx).coerceIn(0.25f, 1.2f)
                                    prefs.kbW = kbW
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) { Text("↔", color = Mmp.OnGreen, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }

        // ---------------- barre du haut
        Row(
            Modifier.fillMaxWidth().align(Alignment.TopCenter).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { if (!busy) go(Screen.HOME) }, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = if (busy) Mmp.Mute else Mmp.Ivory)
            }
            Chip(CameraController.label(caps.activeQuality), selected = false, enabled = !busy, onClick = {
                val list = caps.qualities
                val i = list.indexOf(caps.activeQuality)
                prefs.quality = list[(i + 1) % list.size]
                app.camera.rebind()
            })
            Spacer(Modifier.width(6.dp))
            Chip("${caps.activeFps} i/s", selected = false, enabled = !busy && caps.fps60, onClick = {
                prefs.fps = if (caps.activeFps == 60) 30 else 60
                app.camera.rebind()
            })
            Spacer(Modifier.width(8.dp))
            Row(Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy) { go(Screen.COMPUTER) }.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(midiOn)
                Spacer(Modifier.width(5.dp))
                Text(if (midiOn) "Wi-Fi" else "Wi-Fi ?", style = MaterialTheme.typography.labelMedium, color = if (midiOn) Mmp.Green else Mmp.Mute)
            }
            Box(Modifier.clickable(enabled = !busy) { go(Screen.COMPUTER) }.padding(6.dp)) {
                SignalLamp(level.signal, level.clipping)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { kbVisible = !kbVisible; prefs.kbVisible = kbVisible; if (!kbVisible) editKb = false }) {
                GlyphIcon("keys", if (kbVisible) Mmp.Green else Mmp.Mute)
            }
            IconButton(onClick = { editKb = !editKb }, enabled = kbVisible) { GlyphIcon("move", if (editKb) Mmp.Green else Mmp.Ivory) }
            IconButton(onClick = { showExposure = !showExposure }) { GlyphIcon("sun", if (showExposure) Mmp.Green else Mmp.Ivory) }
            IconButton(onClick = { if (!busy) go(Screen.SETTINGS) }, enabled = !busy) {
                Icon(Icons.Filled.Settings, contentDescription = "Paramètres", tint = if (busy) Mmp.Mute else Mmp.Ivory)
            }
        }

        // ---------------- son qui entre
        Column(
            Modifier.align(Alignment.TopStart).padding(top = 60.dp, start = 10.dp).width(168.dp)
                .clip(RoundedCornerShape(14.dp)).background(Mmp.Ebony.copy(alpha = 0.72f))
                .border(1.dp, if (level.signal) Mmp.Green.copy(alpha = 0.5f) else Mmp.Ebony3, RoundedCornerShape(14.dp))
                .clickable(enabled = !busy) { go(Screen.COMPUTER) }.padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalLamp(level.signal, level.clipping)
                Spacer(Modifier.width(6.dp))
                Text(when { level.clipping -> "SATURE"; level.signal -> "SON ENTRANT"; else -> "AUCUN SON" },
                    style = MaterialTheme.typography.labelSmall, color = when { level.clipping -> Mmp.Red; level.signal -> Mmp.Green; else -> Mmp.Mute })
            }
            Text(if (level.fromComputer) "Ordinateur · Wi-Fi" else "Micro du téléphone", style = MaterialTheme.typography.bodySmall, color = Mmp.Soft)
            Spacer(Modifier.height(4.dp))
            SignalMeter(level, Modifier.fillMaxWidth(), compact = true)
        }

        // ---------------- chronomètre
        if (rec is SessionRecorder.State.Recording) {
            val s = ((now - (rec as SessionRecorder.State.Recording).startedAtMs) / 1000).coerceAtLeast(0)
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 62.dp).clip(RoundedCornerShape(50)).background(Mmp.Red).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                Spacer(Modifier.width(8.dp))
                Text("%02d:%02d".format(s / 60, s % 60), color = Color.White, style = MaterialTheme.typography.titleSmall)
            }
        }
        if (editKb) {
            Text("Fais glisser le clavier pour le placer, et la pastille verte pour l'agrandir.",
                color = Mmp.Ivory, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Mmp.Ebony.copy(alpha = 0.75f)).padding(12.dp))
        }

        // ---------------- commandes du bas
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.35f)).padding(bottom = 14.dp, top = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (showExposure && caps.expMax > caps.expMin) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon("sun", Mmp.Ivory, 18.dp)
                    Slider(value = exposure.toFloat(), onValueChange = { app.camera.setExposure(it.roundToInt()) },
                        valueRange = caps.expMin.toFloat()..caps.expMax.toFloat(), modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                        colors = SliderDefaults.colors(thumbColor = Mmp.Green, activeTrackColor = Mmp.Green, inactiveTrackColor = Mmp.Ebony3))
                    Text(if (exposure > 0) "+$exposure" else "$exposure", color = Mmp.Ivory, style = MaterialTheme.typography.labelMedium)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val stops = buildList {
                    if (caps.zoomMin < 0.95f) add(caps.zoomMin)
                    add(1f)
                    if (caps.zoomMax >= 2f) add(2f)
                    if (caps.zoomMax >= 4f) add(4f)
                }
                for (z in stops) {
                    val sel = kotlin.math.abs(zoom - z) < 0.05f
                    Box(Modifier.size(38.dp).clip(CircleShape).background(if (sel) Mmp.Ivory else Mmp.Ebony.copy(alpha = 0.7f)).clickable { app.camera.setZoom(z) },
                        contentAlignment = Alignment.Center) {
                        Text(if (z < 1f) "%.1f".format(z) else "${z.roundToInt()}×", color = if (sel) Mmp.Ebony else Mmp.Ivory, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Box(Modifier.size(50.dp).clip(CircleShape).background(Mmp.Ebony.copy(alpha = 0.7f)).clickable(enabled = !busy) { go(Screen.GALLERY) }, contentAlignment = Alignment.Center) {
                    GlyphIcon("grid", if (busy) Mmp.Mute else Mmp.Ivory)
                }
                // bouton d'enregistrement
                Box(
                    Modifier.size(78.dp).clip(CircleShape).border(4.dp, Mmp.Ivory, CircleShape).clickable(enabled = rec !is SessionRecorder.State.Processing) {
                        if (recording) app.recorder.stop()
                        else app.recorder.start(app.camera)?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show() }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (recording) Box(Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(Mmp.Red))
                    else Box(Modifier.size(60.dp).clip(CircleShape).background(Mmp.Red))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(50.dp).clip(CircleShape).background(Mmp.Ebony.copy(alpha = 0.7f)).clickable(enabled = !busy && caps.hasFront) {
                        prefs.useFrontCamera = !prefs.useFrontCamera; app.camera.rebind()
                    }, contentAlignment = Alignment.Center) { GlyphIcon("flip", if (busy) Mmp.Mute else Mmp.Ivory) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(50)).clickable(enabled = !busy) {
                orient = (orient + 1) % 3; prefs.orientationMode = orient; setOrientation(orient)
            }.padding(horizontal = 10.dp, vertical = 4.dp)) {
                GlyphIcon("rotate", Mmp.Soft, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(when (orient) { 1 -> "Portrait"; 2 -> "Paysage"; else -> "Orientation auto" }, color = Mmp.Soft, style = MaterialTheme.typography.labelMedium)
            }
        }

        // ---------------- finalisation / résultat
        when (val r = rec) {
            is SessionRecorder.State.Processing -> Box(Modifier.fillMaxSize().background(Mmp.Ebony.copy(alpha = 0.82f)), contentAlignment = Alignment.Center) {
                Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow("Finalisation")
                    Spacer(Modifier.height(10.dp))
                    Text("Assemblage de la vidéo, du son et du MIDI…", color = Mmp.Ivory, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(16.dp))
                    LinearProgressIndicator(progress = { r.progress }, modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Mmp.Green, trackColor = Mmp.Ebony3)
                    Spacer(Modifier.height(8.dp))
                    Text("${(r.progress * 100).roundToInt()} %", color = Mmp.Soft, style = MaterialTheme.typography.labelMedium)
                }
            }
            is SessionRecorder.State.Done -> ResultCard(Modifier.align(Alignment.Center), "Performance enregistrée", r.warning ?: "Vidéo, audio et fichier MIDI sont prêts.",
                primary = "Voir", onPrimary = { app.recorder.acknowledge(); openSession(r.sessionId) }, onClose = { app.recorder.acknowledge() })
            is SessionRecorder.State.Failed -> ResultCard(Modifier.align(Alignment.Center), "Enregistrement interrompu", r.message,
                primary = null, onPrimary = {}, onClose = { app.recorder.acknowledge() })
            else -> {}
        }
    }
}

@Composable
private fun ResultCard(modifier: Modifier, title: String, body: String, primary: String?, onPrimary: () -> Unit, onClose: () -> Unit) {
    Column(modifier.padding(24.dp).clip(RoundedCornerShape(22.dp)).background(Mmp.Ebony2).border(1.dp, Mmp.Green.copy(alpha = 0.5f), RoundedCornerShape(22.dp)).padding(20.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = Mmp.Ivory)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Mmp.Soft)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (primary != null) PillButton(primary, onClick = onPrimary)
            PillButton("Fermer", onClick = onClose, primary = false)
        }
    }
}
