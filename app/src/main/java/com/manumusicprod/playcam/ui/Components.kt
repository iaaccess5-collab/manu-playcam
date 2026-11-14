package com.manumusicprod.playcam.ui

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.manumusicprod.playcam.PlayCamApp
import com.manumusicprod.playcam.R
import com.manumusicprod.playcam.audio.AudioEngine

@Composable
fun MmpLogo(size: Dp = 28.dp) {
    Image(painterResource(R.drawable.ic_mmp_logo), contentDescription = "MANU MUSIC PROD", modifier = Modifier.size(size))
}

@Composable
fun Eyebrow(text: String, color: Color = Mmp.Green) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 18.dp, height = 4.dp)) {
            val skew = size.height * 0.9f
            val p = androidx.compose.ui.graphics.Path().apply {
                moveTo(skew, 0f); lineTo(size.width, 0f); lineTo(size.width - skew, size.height); lineTo(0f, size.height); close()
            }
            drawPath(p, color)
        }
        Spacer(Modifier.width(8.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun ScreenTopBar(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour", tint = Mmp.Ivory) }
        Text(title, style = MaterialTheme.typography.titleLarge, color = Mmp.Ivory, modifier = Modifier.weight(1f))
        MmpLogo(24.dp)
        Spacer(Modifier.width(12.dp))
    }
}

@Composable
fun MmpCard(modifier: Modifier = Modifier, highlight: Boolean = false, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    var m = modifier.clip(shape).background(if (highlight) Mmp.GreenDeep.copy(alpha = 0.22f) else Mmp.Ebony2)
        .border(1.dp, if (highlight) Mmp.Green.copy(alpha = 0.55f) else Mmp.Ebony3, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    val bg = when { !enabled -> Mmp.Ebony3; primary -> Mmp.Green; else -> Color.Transparent }
    val fg = when { !enabled -> Mmp.Mute; primary -> Mmp.OnGreen; else -> Mmp.Ivory }
    Box(
        modifier.height(52.dp).clip(shape).background(bg)
            .then(if (!primary) Modifier.border(1.5.dp, Mmp.Ivory.copy(alpha = 0.3f), shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        Modifier.clip(shape).background(if (selected) Mmp.Green else Mmp.Ebony2)
            .border(1.dp, if (selected) Mmp.Green else Mmp.Ebony3, shape)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (selected) Mmp.OnGreen else if (enabled) Mmp.Ivory else Mmp.Mute, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StatusDot(active: Boolean, size: Dp = 9.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (active) Mmp.Green else Mmp.Mute.copy(alpha = 0.5f)))
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(
            checkedThumbColor = Mmp.OnGreen, checkedTrackColor = Mmp.Green, uncheckedThumbColor = Mmp.Mute, uncheckedTrackColor = Mmp.Ebony3, uncheckedBorderColor = Mmp.Ebony3))
    }
}

@Composable
fun SettingSlider(title: String, valueLabel: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit, onDone: () -> Unit = {}) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
            Text(valueLabel, style = MaterialTheme.typography.labelMedium, color = Mmp.Green)
        }
        Slider(value = value, onValueChange = onChange, onValueChangeFinished = onDone, valueRange = range, steps = steps,
            colors = SliderDefaults.colors(thumbColor = Mmp.Green, activeTrackColor = Mmp.Green, inactiveTrackColor = Mmp.Ebony3))
    }
}

@Composable
fun LevelMeter(level: AudioEngine.Level, modifier: Modifier = Modifier, vertical: Boolean = false) {
    Canvas(modifier) {
        val norm = ((level.peakDb + 60f) / 60f).coerceIn(0f, 1f)
        val rms = ((level.rmsDb + 60f) / 60f).coerceIn(0f, 1f)
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(Mmp.Ebony3, cornerRadius = r)
        val color = when { level.clipping -> Mmp.Red; level.peakDb > -6f -> Mmp.Amber; else -> Mmp.Green }
        if (vertical) {
            drawRoundRect(color.copy(alpha = 0.45f), topLeft = Offset(0f, size.height * (1 - norm)), size = Size(size.width, size.height * norm), cornerRadius = r)
            drawRoundRect(color, topLeft = Offset(0f, size.height * (1 - rms)), size = Size(size.width, size.height * rms), cornerRadius = r)
        } else {
            drawRoundRect(color.copy(alpha = 0.45f), size = Size(size.width * norm, size.height), cornerRadius = r)
            drawRoundRect(color, size = Size(size.width * rms, size.height), cornerRadius = r)
            // repère -6 dB
            val x6 = size.width * (54f / 60f)
            drawLine(Mmp.Ivory.copy(alpha = 0.4f), Offset(x6, 0f), Offset(x6, size.height), strokeWidth = 1.dp.toPx())
        }
    }
}

/** Le clavier virtuel tel qu'il sera incrusté, piloté par le MIDI reçu (ou une démonstration). */
@Composable
fun KeyboardPreview(modifier: Modifier = Modifier, demoWhenIdle: Boolean = true, version: Int = 0) {
    val app = PlayCamApp.instance
    val tick by app.performance.tick.collectAsState()
    Canvas(modifier) {
        if (tick < 0L || version < 0) return@Canvas
        val snap = app.performance.snapshot()
        val demo = if (demoWhenIdle && !snap.anyActive() && snap.chord == null) intArrayOf(48, 64, 67, 71) else null
        val w = size.width
        drawIntoCanvas { c -> app.renderer.draw(c.nativeCanvas, 0f, 0f, w, if (demo != null) null else snap, demo) }
    }
}

// ---------------- petits pictogrammes dessinés (pas d'images externes)
@Composable
fun GlyphIcon(kind: String, tint: Color = Mmp.Ivory, size: Dp = 22.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val st = Stroke(width = s * 0.09f, cap = StrokeCap.Round)
        when (kind) {
            "flip" -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.12f, s * 0.28f), size = Size(s * 0.76f, s * 0.52f), cornerRadius = CornerRadius(s * 0.1f), style = st)
                drawArc(tint, 200f, 140f, false, topLeft = Offset(s * 0.32f, s * 0.38f), size = Size(s * 0.36f, s * 0.32f), style = st)
                drawArc(tint, 20f, 140f, false, topLeft = Offset(s * 0.32f, s * 0.38f), size = Size(s * 0.36f, s * 0.32f), style = st)
                drawRect(tint, topLeft = Offset(s * 0.36f, s * 0.18f), size = Size(s * 0.28f, s * 0.1f))
            }
            "keys" -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.08f, s * 0.22f), size = Size(s * 0.84f, s * 0.56f), cornerRadius = CornerRadius(s * 0.06f), style = st)
                for (i in 1..3) drawLine(tint, Offset(s * (0.08f + 0.21f * i), s * 0.5f), Offset(s * (0.08f + 0.21f * i), s * 0.78f), strokeWidth = s * 0.07f)
                for (i in listOf(0.25f, 0.46f, 0.71f)) drawRect(tint, topLeft = Offset(s * i, s * 0.22f), size = Size(s * 0.1f, s * 0.3f))
            }
            "midi" -> {
                drawCircle(tint, radius = s * 0.4f, style = st)
                for (a in listOf(180.0, 225.0, 270.0, 315.0, 360.0)) {
                    val rad = Math.toRadians(a)
                    drawCircle(tint, radius = s * 0.05f, center = Offset(s / 2 + (s * 0.24f * Math.cos(rad)).toFloat(), s * 0.52f + (s * 0.24f * Math.sin(rad)).toFloat() * -1f))
                }
            }
            "sun" -> {
                drawCircle(tint, radius = s * 0.18f, style = st)
                for (i in 0 until 8) {
                    val rad = Math.toRadians(i * 45.0)
                    val c = Offset(s / 2, s / 2)
                    drawLine(tint, c + Offset((s * 0.3f * Math.cos(rad)).toFloat(), (s * 0.3f * Math.sin(rad)).toFloat()),
                        c + Offset((s * 0.42f * Math.cos(rad)).toFloat(), (s * 0.42f * Math.sin(rad)).toFloat()), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
                }
            }
            "move" -> {
                val c = Offset(s / 2, s / 2)
                drawLine(tint, Offset(s * 0.12f, s / 2), Offset(s * 0.88f, s / 2), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
                drawLine(tint, Offset(s / 2, s * 0.12f), Offset(s / 2, s * 0.88f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
                drawCircle(tint, radius = s * 0.08f, center = c)
            }
            "grid" -> {
                for (i in 0..1) for (j in 0..1) drawRoundRect(tint, topLeft = Offset(s * (0.12f + i * 0.42f), s * (0.12f + j * 0.42f)), size = Size(s * 0.34f, s * 0.34f), cornerRadius = CornerRadius(s * 0.06f), style = st)
            }
            "mic" -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.36f, s * 0.1f), size = Size(s * 0.28f, s * 0.48f), cornerRadius = CornerRadius(s * 0.14f), style = st)
                drawArc(tint, 0f, 180f, false, topLeft = Offset(s * 0.24f, s * 0.3f), size = Size(s * 0.52f, s * 0.42f), style = st)
                drawLine(tint, Offset(s / 2, s * 0.72f), Offset(s / 2, s * 0.88f), strokeWidth = s * 0.08f)
            }
            "rotate" -> {
                drawRoundRect(tint, topLeft = Offset(s * 0.3f, s * 0.12f), size = Size(s * 0.4f, s * 0.76f), cornerRadius = CornerRadius(s * 0.08f), style = st)
                drawLine(tint, Offset(s * 0.44f, s * 0.78f), Offset(s * 0.56f, s * 0.78f), strokeWidth = s * 0.08f, cap = StrokeCap.Round)
            }
        }
    }
}

@Composable
fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Mmp.Mute, modifier = Modifier.widthIn(min = 120.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, color = Mmp.Ivory, modifier = Modifier.weight(1f))
    }
}

/**
 * Vumètre stéréo à segments (gauche / droite), façon console : vert, orange au-delà de −12 dB, rouge au-delà de −3 dB,
 * maintien de crête, graduation et voyant « SON ENTRANT ».
 */
@Composable
fun SignalMeter(level: AudioEngine.Level, modifier: Modifier = Modifier, compact: Boolean = false) {
    // maintien de crête : valeur (dB) et instant (ms) pour chaque canal
    val holdDb = androidx.compose.runtime.remember { floatArrayOf(-90f, -90f) }
    val holdAt = androidx.compose.runtime.remember { longArrayOf(0L, 0L) }
    val now = System.currentTimeMillis()
    fun hold(i: Int, v: Float): Float {
        if (v >= holdDb[i] || now - holdAt[i] > 1200) { holdDb[i] = v; holdAt[i] = now }
        return holdDb[i]
    }
    val hl = hold(0, level.leftDb)
    val hr = hold(1, level.rightDb)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SignalLamp(level.signal, level.clipping)
                Spacer(Modifier.width(8.dp))
                Text(
                    when { level.clipping -> "SATURATION"; level.signal -> "SON ENTRANT"; level.active -> "FLUX ACTIF · SILENCE"; else -> "AUCUN SON" },
                    style = MaterialTheme.typography.labelSmall,
                    color = when { level.clipping -> Mmp.Red; level.signal -> Mmp.Green; else -> Mmp.Mute },
                    modifier = Modifier.weight(1f),
                )
                Text(if (!level.active) "" else if (level.fromComputer) (if (com.manumusicprod.playcam.PlayCamApp.instance.midi.usb.value) "ORDINATEUR · CÂBLE" else "ORDINATEUR · WI-FI") else "MICRO DU TÉLÉPHONE",
                    style = MaterialTheme.typography.labelSmall, color = Mmp.Soft)
            }
        }
        MeterBar("L", level.leftDb, hl, compact)
        MeterBar("R", level.rightDb, hr, compact)
        if (!compact) {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp)) {
                listOf("-48", "-36", "-24", "-12", "-6", "-3", "0").forEachIndexed { i, t ->
                    Text(t, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
                        color = Mmp.Mute, modifier = Modifier.weight(if (i == 0) 0.6f else 1f))
                }
            }
        }
    }
}

private const val SEGMENTS = 30
private fun segDb(i: Int): Float = -48f + 48f * (i + 1) / SEGMENTS

@Composable
private fun MeterBar(label: String, db: Float, holdDb: Float, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Mmp.Soft, modifier = Modifier.width(18.dp))
        Canvas(Modifier.weight(1f).height(if (compact) 7.dp else 12.dp)) {
            val gap = 2.dp.toPx()
            val w = (size.width - gap * (SEGMENTS - 1)) / SEGMENTS
            val r = CornerRadius(1.5.dp.toPx())
            for (i in 0 until SEGMENTS) {
                val th = segDb(i)
                val base = when { th > -3f -> Mmp.Red; th > -12f -> Mmp.Amber; else -> Mmp.Green }
                val lit = db >= th - 48f / SEGMENTS
                val isHold = !lit && holdDb > -60f && kotlin.math.abs(holdDb - th) <= 48f / SEGMENTS
                drawRoundRect(
                    color = when { lit -> base; isHold -> base.copy(alpha = 0.75f); else -> base.copy(alpha = 0.13f) },
                    topLeft = Offset(i * (w + gap), 0f), size = Size(w, size.height), cornerRadius = r,
                )
            }
        }
    }
}

@Composable
fun SignalLamp(on: Boolean, clipping: Boolean = false) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "lamp")
    val a by pulse.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(
        androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "lampAlpha")
    val c = when { clipping -> Mmp.Red; on -> Mmp.Green; else -> Mmp.Mute.copy(alpha = 0.5f) }
    Canvas(Modifier.size(12.dp)) {
        if (on) drawCircle(c.copy(alpha = 0.25f * a), radius = size.minDimension / 2)
        drawCircle(c.copy(alpha = if (on) a else 1f), radius = size.minDimension * 0.3f)
    }
}
