package com.manumusicprod.playcam.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraEffect
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.effects.Frame
import androidx.camera.effects.OverlayEffect
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.concurrent.futures.await
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
import androidx.lifecycle.LifecycleOwner
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.midi.Performance
import com.manumusicprod.playcam.ui.KeyboardRenderer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Caméra (CameraX) : aperçu, enregistrement vidéo, choix de la caméra, qualité, images/s, zoom,
 * mise au point et exposition. Le clavier virtuel et l'accord sont incrustés directement dans les images
 * (OverlayEffect) : ce que tu vois à l'aperçu est exactement ce qui est enregistré.
 */
class CameraController(
    private val context: Context,
    private val prefs: Prefs,
    private val performance: Performance,
    private val renderer: KeyboardRenderer,
) {
    data class Caps(
        val qualities: List<String> = listOf("FHD"),
        val fps60: Boolean = false,
        val hasFront: Boolean = true,
        val zoomMin: Float = 1f,
        val zoomMax: Float = 1f,
        val expMin: Int = 0,
        val expMax: Int = 0,
        val activeQuality: String = "FHD",
        val activeFps: Int = 30,
    )

    private val _caps = MutableStateFlow(Caps())
    val caps: StateFlow<Caps> = _caps
    private val _zoom = MutableStateFlow(1f)
    val zoom: StateFlow<Float> = _zoom
    private val _exposure = MutableStateFlow(0)
    val exposure: StateFlow<Int> = _exposure
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var preview: Preview? = null
    private var effect: OverlayEffect? = null
    private var previewView: PreviewView? = null
    private var owner: LifecycleOwner? = null

    private val overlayThread = HandlerThread("mmp-overlay").apply { start() }
    private val overlayHandler = Handler(overlayThread.looper)
    private val main = Handler(Looper.getMainLooper())

    private val _content = MutableStateFlow(RectF())
    /** Zone de l'aperçu (en pixels de la vue) occupée réellement par l'image filmée. */
    val content: StateFlow<RectF> = _content
    private val inv = Matrix()
    private val bufRect = RectF()
    private val contentTmp = RectF()

    @Volatile private var sensorToView: Matrix? = null
    @Volatile private var viewW = 0f
    @Volatile private var viewH = 0f
    private val tmp = Matrix()

    private val transformUpdater = object : Runnable {
        override fun run() {
            previewView?.let { pv ->
                sensorToView = pv.sensorToViewTransform
                viewW = pv.width.toFloat(); viewH = pv.height.toFloat()
            }
            main.postDelayed(this, 250)
        }
    }

    suspend fun bind(lifecycleOwner: LifecycleOwner, pv: PreviewView) {
        previewView = pv
        owner = lifecycleOwner
        provider = ProcessCameraProvider.getInstance(context).await()
        rebind()
        main.removeCallbacks(transformUpdater)
        main.post(transformUpdater)
    }

    fun unbind() {
        main.removeCallbacks(transformUpdater)
        provider?.unbindAll()
        effect?.close(); effect = null
        camera = null; videoCapture = null; preview = null
        previewView = null; owner = null
    }

    private fun hasCamera(p: ProcessCameraProvider, s: CameraSelector) = try { p.hasCamera(s) } catch (e: Exception) { false }

    fun rebind() {
        val p = provider ?: return
        val pv = previewView ?: return
        val lo = owner ?: return
        p.unbindAll()
        effect?.close(); effect = null

        val hasFront = hasCamera(p, CameraSelector.DEFAULT_FRONT_CAMERA)
        val hasBack = hasCamera(p, CameraSelector.DEFAULT_BACK_CAMERA)
        val selector = when {
            prefs.useFrontCamera && hasFront -> CameraSelector.DEFAULT_FRONT_CAMERA
            hasBack -> CameraSelector.DEFAULT_BACK_CAMERA
            else -> CameraSelector.DEFAULT_FRONT_CAMERA
        }
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
        val supported = info?.let { Recorder.getVideoCapabilities(it).getSupportedQualities(DynamicRange.SDR) } ?: emptyList()
        val names = buildList {
            if (supported.contains(Quality.UHD)) add("UHD")
            if (supported.contains(Quality.FHD)) add("FHD")
            if (supported.contains(Quality.HD)) add("HD")
        }.ifEmpty { listOf("FHD") }
        val ranges = info?.supportedFrameRateRanges ?: emptySet()
        val fps60 = ranges.any { it.upper >= 60 }

        // qualité et cadence demandées, avec repli automatique si l'association n'est pas prise en charge
        val attempts = buildList {
            val q = if (names.contains(prefs.quality)) prefs.quality else names.first()
            val f = if (prefs.fps == 60 && fps60) 60 else 30
            add(q to f)
            if (f == 60) add(q to 30)
            if (q == "UHD") add("FHD" to f)
            add("FHD" to 30)
        }.distinct()

        for ((q, f) in attempts) {
            try {
                bindWith(p, pv, lo, selector, q, f)
                _caps.value = Caps(names, fps60, hasFront, zoomMin(), zoomMax(), expRange().lower, expRange().upper, q, f)
                if (q != prefs.quality || f != prefs.fps) _error.value = "Réglage ajusté à ${label(q)} · $f i/s (pris en charge par ce téléphone)"
                return
            } catch (e: Exception) {
                Log.w("PlayCam", "Liaison caméra impossible en $q/$f", e)
                p.unbindAll(); effect?.close(); effect = null
            }
        }
        _error.value = "La caméra n'a pas pu démarrer. Ferme les autres applications qui l'utilisent."
    }

    @SuppressLint("RestrictedApi")
    private fun bindWith(p: ProcessCameraProvider, pv: PreviewView, lo: LifecycleOwner, selector: CameraSelector, q: String, fps: Int) {
        val quality = when (q) { "UHD" -> Quality.UHD; "HD" -> Quality.HD; else -> Quality.FHD }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
            .build()
        val vb = VideoCapture.Builder(recorder).setMirrorMode(MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        if (fps == 60) vb.setTargetFrameRate(Range(60, 60))
        val vc = vb.build()
        val prev = Preview.Builder().build()
        prev.setSurfaceProvider(pv.surfaceProvider)
        val rotation = pv.display?.rotation ?: Surface.ROTATION_0
        vc.targetRotation = rotation
        prev.targetRotation = rotation

        val eff = OverlayEffect(CameraEffect.PREVIEW or CameraEffect.VIDEO_CAPTURE, 0, overlayHandler,
            Consumer<Throwable> { t -> Log.e("PlayCam", "Incrustation", t) })
        eff.setOnDrawListener { frame -> drawOverlay(frame); true }

        val group = UseCaseGroup.Builder().addUseCase(prev).addUseCase(vc).addEffect(eff).build()
        camera = p.bindToLifecycle(lo, selector, group)
        videoCapture = vc; preview = prev; effect = eff
        _zoom.value = 1f
        _exposure.value = 0
    }

    private fun drawOverlay(frame: Frame) {
        val canvas = frame.overlayCanvas
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val s2v = sensorToView ?: return
        val w = viewW; val h = viewH
        if (w <= 0f || h <= 0f) return
        synchronized(tmp) {
            if (!s2v.invert(tmp)) return
            tmp.postConcat(frame.sensorToBufferTransform)
            canvas.setMatrix(tmp)
            // zone de l'image filmée, exprimée dans les coordonnées de l'aperçu
            if (tmp.invert(inv)) {
                bufRect.set(0f, 0f, frame.size.width.toFloat(), frame.size.height.toFloat())
                inv.mapRect(contentTmp, bufRect)
                contentTmp.intersect(0f, 0f, w, h)
            } else contentTmp.set(0f, 0f, w, h)
        }
        val c = contentTmp
        val cur = _content.value
        if (kotlin.math.abs(cur.left - c.left) > 1f || kotlin.math.abs(cur.top - c.top) > 1f || kotlin.math.abs(cur.width() - c.width()) > 1f || kotlin.math.abs(cur.height() - c.height()) > 1f)
            _content.value = RectF(c)
        val cw = c.width(); val ch = c.height()
        if (cw <= 0f || ch <= 0f) return
        canvas.save()
        canvas.clipRect(c)
        if (prefs.kbVisible) renderer.draw(canvas, c.left + prefs.kbX * cw, c.top + prefs.kbY * ch, prefs.kbW * cw, performance.snapshot())
        if (prefs.watermark) { canvas.translate(c.left, c.top); renderer.drawWatermark(canvas, cw, ch) }
        canvas.restore()
    }

    /** Met à jour l'orientation des images après une rotation de l'écran. */
    fun updateRotation() {
        val rotation = previewView?.display?.rotation ?: return
        videoCapture?.targetRotation = rotation
        preview?.targetRotation = rotation
    }

    fun startRecording(file: File, onEvent: (VideoRecordEvent) -> Unit): Recording? {
        val vc = videoCapture ?: return null
        return vc.output.prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { e -> onEvent(e) }
    }

    fun focusAt(x: Float, y: Float) {
        val pv = previewView ?: return
        val point = pv.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point).setAutoCancelDuration(4, TimeUnit.SECONDS).build()
        camera?.cameraControl?.startFocusAndMetering(action)
    }

    fun setZoom(ratio: Float) {
        val r = ratio.coerceIn(zoomMin(), zoomMax())
        camera?.cameraControl?.setZoomRatio(r)
        _zoom.value = r
    }

    fun setExposure(index: Int) {
        val range = expRange()
        val i = index.coerceIn(range.lower, range.upper)
        camera?.cameraControl?.setExposureCompensationIndex(i)
        _exposure.value = i
    }

    fun exposureStep(): Float = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: 0f

    private fun zoomMin() = camera?.cameraInfo?.zoomState?.value?.minZoomRatio ?: 1f
    private fun zoomMax() = camera?.cameraInfo?.zoomState?.value?.maxZoomRatio ?: 1f
    private fun expRange(): Range<Int> = camera?.cameraInfo?.exposureState?.exposureCompensationRange ?: Range(0, 0)

    fun clearError() { _error.value = null }

    companion object {
        fun label(q: String) = when (q) { "UHD" -> "4K"; "HD" -> "720p"; else -> "1080p" }
    }
}
