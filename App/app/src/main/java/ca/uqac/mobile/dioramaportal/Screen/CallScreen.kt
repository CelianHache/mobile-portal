package ca.uqac.mobile.dioramaportal.Screen

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.ui.platform.LocalWindowInfo
import ca.uqac.mobile.dioramaportal.PortalShape
import kotlin.math.exp
import kotlin.math.ln
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ca.uqac.mobile.dioramaportal.Log.AppLog
import ca.uqac.mobile.dioramaportal.ViewModel.CallViewModel
import ca.uqac.mobile.dioramaportal.Log.LogList
import ca.uqac.mobile.dioramaportal.VideoCall
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

/**
 * Page d'appel : vidéo au ratio exact du flux reçu, et à côté (paysage) ou dessous (portrait)
 * le panneau avec le bouton « Mode portail », les réglages de taille du portail
 * (affichés d'office dès que l'appel commence) et le journal de l'appel en cours.
 */
@Composable
fun CallScreen(vm: CallViewModel, modifier: Modifier = Modifier) {
    val peer by vm.link.peer.collectAsState()
    val callLog by AppLog.call.collectAsState()
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    // Ratio largeur/hauteur du flux reçu (connu à la première image). Gardé à la rotation.
    var remoteRatio by rememberSaveable { mutableStateOf<Float?>(null) }
    val onRatio = { r: Float -> remoteRatio = r }
    // Réglages du portail ouverts d'office au début de l'appel (émis ou reçu).
    var showShape by rememberSaveable { mutableStateOf(true) }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val panel: @Composable (Modifier) -> Unit = { m ->
            Column(m.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("En appel avec ${peer.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.portalMode.value = true }) { Text("Mode portail") }
                    OutlinedButton(onClick = { vm.link.disconnect() }) { Text("Raccrocher") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(showShape, { showShape = true }, label = { Text("Taille du portail") })
                    FilterChip(!showShape, { showShape = false }, label = { Text("Journal") })
                }
                if (showShape) {
                    PortalShapeControls(vm, Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()))
                } else {
                    LogList(
                        remember(callLog) { callLog.map { it.line } },
                        Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }

        if (landscape) {
            // Vidéo à gauche : toute la hauteur, largeur dictée par son ratio.
            // Le panneau prend toute la largeur restante (au moins MIN_PANEL).
            val maxVideoWidth = maxWidth - MIN_PANEL
            val videoWidth = remoteRatio?.let { minOf(maxHeight * it, maxVideoWidth) } ?: maxVideoWidth
            Row(Modifier.fillMaxSize()) {
                VideoArea(vm.call, Modifier.width(videoWidth).fillMaxHeight(), onRatio)
                panel(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // Vidéo en haut : toute la largeur, hauteur dictée par son ratio.
            // Le panneau prend toute la hauteur restante (au moins MIN_PANEL).
            val maxVideoHeight = maxHeight - MIN_PANEL
            val videoHeight = remoteRatio?.let { minOf(maxWidth / it, maxVideoHeight) } ?: maxVideoHeight
            Column(Modifier.fillMaxSize()) {
                VideoArea(vm.call, Modifier.fillMaxWidth().height(videoHeight), onRatio)
                panel(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/** Place minimale laissée au panneau à côté (ou sous) la vidéo. */
private val MIN_PANEL = 240.dp

/**
 * Mode portail du diorama : seulement la vidéo de l'autre lieu, sur fond noir, ratio conservé.
 * Toucher l'écran affiche / masque les réglages de taille ; appui long ou retour pour quitter.
 *
 * Aussi utilisé pour le test caméra sans appel ([test] = true) : on y voit notre propre caméra
 * telle que l'autre appareil l'afficherait, réglages ouverts d'office.
 */
@Composable
fun PortalScreen(vm: CallViewModel, onExit: () -> Unit, test: Boolean = false) {
    var showHint by remember { mutableStateOf(true) }
    var showControls by remember { mutableStateOf(test) }
    LaunchedEffect(Unit) {
        delay(3000)
        showHint = false
    }
    BackHandler(onBack = onExit)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        VideoView(vm.call, if (test) vm.call.previewSink else vm.call.remoteSink, mirror = false)
        // Calque transparent au-dessus de la vidéo pour capter les appuis.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { showControls = !showControls },
                    onLongPress = { onExit() },
                )
            }
        )
        if (test) {
            Text(
                "Test caméra (sans appel) · Appui long ou retour : quitter",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(24.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
        if (showControls) {
            PortalShapeControls(
                vm,
                test = test,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .widthIn(max = 480.dp)
                    .fillMaxWidth()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                    .padding(12.dp),
                contentColor = Color.White,
            )
        } else if (showHint && !test) {
            Text(
                "Toucher : taille du portail · Appui long : quitter",
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/**
 * Réglages de la forme du portail : zoom sur le cercle de la lentille fisheye, étirement,
 * décalage, et « Remplir l'écran » qui étire l'image au format exact de l'écran.
 */
@Composable
private fun PortalShapeControls(
    vm: CallViewModel,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
    test: Boolean = false,
) {
    val shape by vm.portalShape.collectAsState()
    val window = LocalWindowInfo.current.containerSize
    val textColor = if (contentColor == Color.Unspecified) MaterialTheme.colorScheme.onSurface else contentColor
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LabeledSlider("Zoom", "×%.2f".format(shape.zoom), textColor,
            shape.zoom, PortalShape.MIN_ZOOM..PortalShape.MAX_ZOOM) {
            vm.setPortalShape(shape.copy(zoom = it))
        }
        // Échelle logarithmique : ×1 au milieu, ×4 plus large à droite, ×4 plus haut à gauche.
        LabeledSlider("Étirement horizontal", "×%.2f".format(shape.stretch), textColor,
            ln(shape.stretch), ln(PortalShape.MIN_STRETCH)..ln(PortalShape.MAX_STRETCH)) {
            vm.setPortalShape(shape.copy(stretch = exp(it)))
        }
        // Le décalage déplace la zone recadrée : il faut zoomer pour avoir de la marge.
        val canShift = shape.zoom > 1.01f
        if (!canShift) {
            Text("Zoome pour pouvoir décaler l'image", color = textColor, style = MaterialTheme.typography.bodySmall)
        }
        LabeledSlider("Décalage horizontal", "%+.0f %%".format(shape.offsetX * 100), textColor,
            shape.offsetX, -PortalShape.MAX_OFFSET..PortalShape.MAX_OFFSET, enabled = canShift) {
            vm.setPortalShape(shape.copy(offsetX = it))
        }
        LabeledSlider("Décalage vertical", "%+.0f %%".format(shape.offsetY * 100), textColor,
            shape.offsetY, -PortalShape.MAX_OFFSET..PortalShape.MAX_OFFSET, enabled = canShift) {
            vm.setPortalShape(shape.copy(offsetY = it))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                // Le zoom garde le format de l'image : il suffit d'étirer jusqu'au format de l'écran.
                val source = (if (test) vm.call.previewSink else vm.call.remoteSink).sourceRatio
                if (source > 0f && window.height > 0) {
                    val target = window.width.toFloat() / window.height
                    val stretch = (target / source).coerceIn(PortalShape.MIN_STRETCH, PortalShape.MAX_STRETCH)
                    vm.setPortalShape(shape.copy(stretch = stretch))
                }
            }) { Text("Remplir l'écran") }
            OutlinedButton(onClick = { vm.setPortalShape(PortalShape()) }) {
                Text("Réinitialiser", color = textColor)
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    color: Color,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = color, style = MaterialTheme.typography.labelLarge)
            Text(valueText, color = color, style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, enabled = enabled)
    }
}

/** Vidéo de l'autre appareil en grand, aperçu de notre caméra en coin. */
@Composable
private fun VideoArea(call: VideoCall, modifier: Modifier, onRemoteRatio: (Float) -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) {
        VideoView(call, call.remoteSink, mirror = false, onRatio = onRemoteRatio)
        Box(
            Modifier.align(Alignment.BottomEnd).padding(8.dp).size(120.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            VideoView(call, call.localSink, mirror = true, onTop = true)
        }
    }
}

/**
 * Vue vidéo WebRTC branchée sur un relais de [VideoCall].
 * `mirror = false` pour le flux distant : on regarde par une fenêtre, pas dans un miroir.
 *
 * SurfaceViewRenderer rogne l'image pour remplir la taille qu'on lui impose : on lui donne
 * donc exactement le ratio de la vidéo reçue (rotation comprise), la plus grande taille qui
 * tient dans l'espace disponible. Résultat : image entière, bandes noires si besoin.
 */
@Composable
fun VideoView(
    call: VideoCall,
    sink: VideoCall.ProxySink,
    mirror: Boolean,
    onTop: Boolean = false,
    onRatio: (Float) -> Unit = {},
) {
    var ratio by remember { mutableStateOf<Float?>(null) }
    AndroidView(
        modifier = ratio?.let { Modifier.aspectRatio(it) } ?: Modifier.fillMaxSize(),
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                val view = this
                init(call.eglBase.eglBaseContext, object : RendererCommon.RendererEvents {
                    override fun onFirstFrameRendered() {}
                    override fun onFrameResolutionChanged(width: Int, height: Int, rotation: Int) {
                        if (width == 0 || height == 0) return
                        val r = if (rotation % 180 == 0) width.toFloat() / height else height.toFloat() / width
                        view.post { ratio = r; onRatio(r) } // appelé depuis le thread de rendu
                    }
                })
                setMirror(mirror)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                if (onTop) setZOrderMediaOverlay(true)
                sink.target = this
            }
        },
        onRelease = { view ->
            if (sink.target === view) sink.target = null
            view.release()
        }
    )
}
