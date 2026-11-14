package com.manumusicprod.playcam.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.R
import com.manumusicprod.playcam.midi.MidiHub
import com.manumusicprod.playcam.midi.Performance
import kotlin.math.max
import kotlin.math.min

/**
 * Dessine le clavier virtuel et le nom de l'accord avec android.graphics.
 * Le même code sert à l'aperçu caméra, à la vidéo enregistrée (incrustation) et aux écrans de réglage.
 */
class KeyboardRenderer(context: Context, private val prefs: Prefs) {

    private val display: Typeface = ResourcesCompat.getFont(context, R.font.inter_display_black_italic) ?: Typeface.DEFAULT_BOLD
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.inter_bold) ?: Typeface.DEFAULT_BOLD
    private val semi: Typeface = ResourcesCompat.getFont(context, R.font.inter_semibold) ?: Typeface.DEFAULT

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val rect = RectF()

    companion object {
        const val EBONY = 0xFF0E0F0D.toInt()
        const val IVORY = 0xFFF3EFE4.toInt()
        const val GREEN = 0xFF59C35F.toInt()
        private val WHITE_PC = booleanArrayOf(true, false, true, false, true, true, false, true, false, true, false, true)
        fun isWhite(n: Int) = WHITE_PC[((n % 12) + 12) % 12]

        /** Proportion hauteur / largeur du bloc clavier (sans l'accord), pour un nombre de touches blanches. */
        fun keyboardAspect(whiteKeys: Int): Float = when {
            whiteKeys >= 45 -> 0.115f
            whiteKeys >= 30 -> 0.15f
            whiteKeys >= 20 -> 0.2f
            else -> 0.26f
        }
    }

    fun whiteCount(low: Int, high: Int): Int { var c = 0; for (n in low..high) if (isWhite(n)) c++; return max(c, 1) }

    /** Hauteur totale (accord compris) du bloc pour une largeur donnée. */
    fun blockHeight(width: Float, low: Int = prefs.kbLowNote, high: Int = prefs.kbHighNote): Float {
        val kbH = width * keyboardAspect(whiteCount(low, high))
        return kbH + if (prefs.kbShowChord) chordBand(width) else 0f
    }

    private fun chordBand(width: Float): Float = min(width * 0.11f, 140f) * prefs.kbChordScale

    /**
     * Dessine le bloc (accord + clavier) dont le coin haut-gauche est (x, y) et la largeur w, en pixels de [canvas].
     * @param scale facteur appliqué aux traits et textes (1 = aperçu)
     */
    fun draw(canvas: Canvas, x: Float, y: Float, w: Float, snap: Performance.Snapshot?, demoNotes: IntArray? = null) {
        val low = adjustLow(prefs.kbLowNote)
        val high = adjustHigh(prefs.kbHighNote)
        val whites = whiteCount(low, high)
        val alpha = (prefs.kbOpacity.coerceIn(0.2f, 1f) * 255).toInt()
        val chordH = if (prefs.kbShowChord) chordBand(w) else 0f
        val kbTop = y + chordH
        val kbH = w * keyboardAspect(whites)
        val pad = max(2f, w * 0.008f)
        val active = prefs.kbActiveColor

        val down = snap?.down
        val sus = snap?.sustained
        val vel = snap?.velocity
        fun isDown(n: Int) = (down != null && down[n]) || (demoNotes != null && demoNotes.contains(n))
        fun isSus(n: Int) = sus != null && sus[n]

        // ---- accord
        if (prefs.kbShowChord) {
            val name = when {
                demoNotes != null -> "Cmaj7"
                snap?.chord != null && snap.chordAgeMs < 2500 -> snap.chord
                else -> null
            }
            if (name != null) {
                val fade = if (snap != null && snap.chordAgeMs > 1500) (1f - (snap.chordAgeMs - 1500) / 1000f).coerceIn(0f, 1f) else 1f
                text.typeface = display
                text.textSize = chordH * 0.78f
                text.textAlign = Paint.Align.CENTER
                val tw = text.measureText(name)
                val cx = x + w / 2
                // pastille ébène derrière l'accord, lisible sur n'importe quelle image
                fill.color = EBONY; fill.alpha = (alpha * 0.82f * fade).toInt()
                rect.set(cx - tw / 2 - chordH * 0.45f, y + chordH * 0.04f, cx + tw / 2 + chordH * 0.45f, y + chordH * 0.92f)
                canvas.drawRoundRect(rect, chordH * 0.44f, chordH * 0.44f, fill)
                // trait vert incliné de la marque
                fill.color = GREEN; fill.alpha = (255 * fade).toInt()
                val bx = rect.left + chordH * 0.22f
                canvas.save(); canvas.skew(-0.6f, 0f)
                canvas.restore()
                rect.set(bx, rect.centerY() - chordH * 0.04f, bx + chordH * 0.16f, rect.centerY() + chordH * 0.04f)
                canvas.drawRect(rect, fill)
                text.color = IVORY; text.alpha = (255 * fade).toInt()
                canvas.drawText(name, cx + chordH * 0.08f, y + chordH * 0.48f - (text.ascent() + text.descent()) / 2, text)
            }
        }

        // ---- cadre
        fill.color = EBONY; fill.alpha = (alpha * 0.85f).toInt()
        rect.set(x, kbTop, x + w, kbTop + kbH + pad * 2)
        canvas.drawRoundRect(rect, pad * 3, pad * 3, fill)

        val left = x + pad
        val top = kbTop + pad
        val inner = w - pad * 2
        val ww = inner / whites
        val bw = ww * 0.6f
        val bh = kbH * 0.62f
        val gap = max(0.6f, ww * 0.06f)

        // touches blanches
        var i = 0
        for (n in low..high) {
            if (!isWhite(n)) continue
            val kx = left + i * ww
            rect.set(kx + gap / 2, top, kx + ww - gap / 2, top + kbH)
            when {
                isDown(n) -> { fill.color = active; fill.alpha = 255 }
                isSus(n) -> { fill.color = blend(active, IVORY, 0.55f); fill.alpha = alpha }
                else -> { fill.color = IVORY; fill.alpha = alpha }
            }
            canvas.drawRoundRect(rect, ww * 0.12f, ww * 0.12f, fill)
            if (isDown(n) && prefs.kbShowVelocity && vel != null && vel[n] > 0) {
                fill.color = Color.WHITE; fill.alpha = 70
                val vh = kbH * 0.3f * (vel[n] / 127f)
                canvas.drawRect(kx + gap, top + kbH - vh, kx + ww - gap, top + kbH, fill)
            }
            // repère des do quand il y a la place
            if (n % 12 == 0 && ww > 9f && !isDown(n)) {
                text.typeface = semi; text.textSize = min(ww * 0.42f, kbH * 0.12f); text.color = 0xFF8C887C.toInt(); text.alpha = alpha
                canvas.drawText(if (prefs.kbFrenchNames) "Do${n / 12 - 1}" else "C${n / 12 - 1}", kx + ww / 2, top + kbH - kbH * 0.06f, text)
            }
            i++
        }
        // touches noires
        i = 0
        for (n in low..high) {
            if (isWhite(n)) { i++; continue }
            val kx = left + i * ww - bw / 2
            rect.set(kx, top, kx + bw, top + bh)
            when {
                isDown(n) -> { fill.color = active; fill.alpha = 255 }
                isSus(n) -> { fill.color = blend(active, EBONY, 0.45f); fill.alpha = 255 }
                else -> { fill.color = 0xFF151614.toInt(); fill.alpha = max(alpha, 200) }
            }
            canvas.drawRoundRect(rect, bw * 0.16f, bw * 0.16f, fill)
        }
        // noms des notes jouées
        if (prefs.kbShowNames) {
            text.typeface = bold
            i = 0
            for (n in low..high) {
                val white = isWhite(n)
                if (isDown(n)) {
                    val label = MidiHub.pitchName(n, prefs.kbFrenchNames)
                    val size = max(min(ww * 0.55f, kbH * 0.18f), 9f)
                    text.textSize = size
                    if (white) {
                        val kx = left + i * ww
                        text.color = EBONY; text.alpha = 255
                        canvas.drawText(label, kx + ww / 2, top + kbH - kbH * 0.08f, text)
                    } else {
                        val kx = left + i * ww
                        // pastille au-dessus de la touche noire
                        val tw = text.measureText(label)
                        fill.color = active; fill.alpha = 255
                        rect.set(kx - tw / 2 - size * 0.3f, top + bh + size * 0.15f, kx + tw / 2 + size * 0.3f, top + bh + size * 1.25f)
                        canvas.drawRoundRect(rect, size * 0.5f, size * 0.5f, fill)
                        text.color = EBONY
                        canvas.drawText(label, kx, rect.bottom - size * 0.28f, text)
                    }
                }
                if (white) i++
            }
        }
        if (snap?.sustain == true) {
            text.typeface = bold; text.textSize = max(9f, kbH * 0.11f); text.color = active; text.alpha = 255; text.textAlign = Paint.Align.RIGHT
            canvas.drawText("SUSTAIN", x + w - pad * 2, kbTop - max(4f, kbH * 0.04f), text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    /** Petit filigrane de marque en bas à droite de l'image. */
    fun drawWatermark(canvas: Canvas, viewW: Float, viewH: Float) {
        val size = max(11f, min(viewW, viewH) * 0.028f)
        text.typeface = display; text.textSize = size; text.textAlign = Paint.Align.RIGHT
        text.color = IVORY; text.alpha = 200
        val label = "MANU PLAY CAM"
        val xr = viewW - size * 1.2f
        val yb = viewH - size * 1.4f
        canvas.drawText(label, xr, yb, text)
        val tw = text.measureText(label)
        fill.color = GREEN; fill.alpha = 230
        rect.set(xr - tw - size * 1.3f, yb - size * 0.42f, xr - tw - size * 0.45f, yb - size * 0.2f)
        canvas.drawRect(rect, fill)
        text.textAlign = Paint.Align.CENTER
    }

    private fun adjustLow(n: Int): Int { var v = n.coerceIn(0, 120); while (!isWhite(v)) v--; return max(v, 0) }
    private fun adjustHigh(n: Int): Int { var v = n.coerceIn(7, 127); while (!isWhite(v)) v++; return min(v, 127) }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).toInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).toInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        return Color.rgb(r, g, bl)
    }
}
