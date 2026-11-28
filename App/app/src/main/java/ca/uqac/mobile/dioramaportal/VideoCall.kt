package ca.uqac.mobile.dioramaportal

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.audio.JavaAudioDeviceModule
import org.json.JSONObject
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.concurrent.Executors

/**
 * Appel vidéo WebRTC minimal entre deux pairs, sans serveur :
 * - pas de STUN/TURN, seulement les candidats « hôte » (Wi-Fi, point d'accès, Tailscale) ;
 * - la signalisation (offre, réponse, candidats ICE) passe en JSON par [sendSignal],
 *   c'est-à-dire par le socket TCP de [ca.uqac.mobile.dioramaportal.Connection.PeerLink].
 *
 * Toutes les opérations passent par un seul thread ([executor]) pour éviter les courses.
 */
class VideoCall(
    context: Context,
    private val sendSignal: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    val eglBase: EglBase = EglBase.create()
    private val executor = Executors.newSingleThreadExecutor()

    /** Les vues s'y branchent quand elles existent (voir l'écran Compose). */
    /** Recadrage / étirement appliqué à la vidéo reçue (lu sur le thread de décodage). */
    @Volatile var portalShape = PortalShape()

    val localSink = ProxySink()
    val remoteSink = ProxySink { portalShape.transform(it) }

    /** Test sans appel : notre caméra, avec la même forme de portail que la vidéo reçue. */
    val previewSink = ProxySink { portalShape.transform(it) }

    private val factory: PeerConnectionFactory
    private var pc: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localTrack: VideoTrack? = null

    // Son : le module audio Java de WebRTC enregistre le micro et joue le son reçu.
    private val audioDevice: JavaAudioDeviceModule
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var audioSource: AudioSource? = null
    private var audioTrack: AudioTrack? = null
    private var savedAudioMode = AudioManager.MODE_NORMAL

    private val _micMuted = MutableStateFlow(false)
    /** Micro coupé par l'utilisateur (bouton de la page d'appel). */
    val micMuted: StateFlow<Boolean> = _micMuted

    // App en arrière-plan : la caméra ne doit pas tourner (Android la bloque de toute façon).
    private var cameraPaused = false

    // Test caméra en cours (sans appel) : la caméra tourne même si pc == null.
    private var previewing = false

    // Les candidats ICE qui arrivent avant la description distante sont mis de côté.
    private var remoteDescriptionSet = false
    private val pendingCandidates = mutableListOf<IceCandidate>()

    init {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(appContext)
                .createInitializationOptions()
        )
        // Erreurs natives de WebRTC visibles dans logcat (tag org.webrtc.Logging).
        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)
        // Annulation d'écho et réduction de bruit matérielles quand le téléphone en a :
        // indispensable en haut-parleur, sinon l'autre s'entend en écho.
        audioDevice = JavaAudioDeviceModule.builder(appContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioDevice)
            // Le NetworkMonitor Android ne voit ni le point d'accès (swlan0) ni certains VPN,
            // et WebRTC se retrouvait sans aucune interface (« Machine has no networks »).
            // Sans lui, WebRTC liste directement toutes les interfaces du système.
            .setOptions(PeerConnectionFactory.Options().apply { disableNetworkMonitor = true })
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
            .createPeerConnectionFactory()
    }

    /** Démarre la caméra et la connexion. L'initiateur envoie l'offre. */
    fun start(initiator: Boolean) = executor.execute {
        if (pc != null) return@execute
        if (capturer == null) startCamera() // déjà démarrée si un test caméra était en cours

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
        val peer = factory.createPeerConnection(config, observer) ?: run {
            log("WebRTC : impossible de créer la connexion")
            return@execute
        }
        pc = peer
        localTrack?.let { peer.addTrack(it, listOf("diorama")) }
        startAudio()
        audioTrack?.let { peer.addTrack(it, listOf("diorama")) }

        if (initiator) {
            peer.createOffer(object : SdpAdapter("createOffer") {
                override fun onCreateSuccess(sdp: SessionDescription) {
                    peer.setLocalDescription(SdpAdapter("setLocal(offer)"), sdp)
                    sendSignal(JSONObject().put("type", "offer").put("sdp", sdp.description).toString())
                    log("WebRTC : offre envoyée")
                }
            }, MediaConstraints())
        }
    }

    /** Traite un message de signalisation reçu de l'autre appareil. */
    fun handleSignal(json: String) = executor.execute {
        val peer = pc ?: return@execute
        val msg = runCatching { JSONObject(json) }.getOrNull() ?: return@execute
        when (msg.optString("type")) {
            "offer" -> {
                log("WebRTC : offre reçue")
                val offer = SessionDescription(SessionDescription.Type.OFFER, msg.getString("sdp"))
                peer.setRemoteDescription(object : SdpAdapter("setRemote(offer)") {
                    override fun onSetSuccess() {
                        onRemoteDescriptionSet()
                        peer.createAnswer(object : SdpAdapter("createAnswer") {
                            override fun onCreateSuccess(sdp: SessionDescription) {
                                peer.setLocalDescription(SdpAdapter("setLocal(answer)"), sdp)
                                sendSignal(JSONObject().put("type", "answer").put("sdp", sdp.description).toString())
                                log("WebRTC : réponse envoyée")
                            }
                        }, MediaConstraints())
                    }
                }, offer)
            }
            "answer" -> {
                log("WebRTC : réponse reçue")
                val answer = SessionDescription(SessionDescription.Type.ANSWER, msg.getString("sdp"))
                peer.setRemoteDescription(object : SdpAdapter("setRemote(answer)") {
                    override fun onSetSuccess() = onRemoteDescriptionSet()
                }, answer)
            }
            "ice" -> {
                val candidate = IceCandidate(
                    msg.getString("sdpMid"),
                    msg.getInt("sdpMLineIndex"),
                    msg.getString("candidate"),
                )
                log("ICE distant : ${describe(candidate)}")
                if (remoteDescriptionSet) peer.addIceCandidate(candidate)
                else pendingCandidates += candidate
            }
        }
    }

    /** App en arrière-plan : la caméra s'arrête, plus aucune image n'est envoyée. */
    fun pauseCamera() = executor.execute {
        cameraPaused = true
        runCatching { capturer?.stopCapture() }
        localTrack?.setEnabled(false)
        audioTrack?.setEnabled(false) // micro coupé aussi en arrière-plan
    }

    /** App au premier plan : la caméra repart si un appel est en cours. */
    fun resumeCamera() = executor.execute {
        if (!cameraPaused) return@execute
        cameraPaused = false
        localTrack?.setEnabled(true)
        audioTrack?.setEnabled(!_micMuted.value)
        capturer?.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
    }

    /** Coupe ou réactive notre micro : l'autre appareil ne reçoit plus que du silence. */
    fun setMicMuted(muted: Boolean) = executor.execute {
        _micMuted.value = muted
        audioTrack?.setEnabled(!muted && !cameraPaused)
        log(if (muted) "Micro coupé" else "Micro réactivé")
    }

    /** Test caméra sans appel : démarre la caméra seule (aucune connexion). */
    fun startPreview() = executor.execute {
        previewing = true
        if (capturer == null) startCamera()
    }

    /** Fin du test caméra : coupe la caméra, sauf si un appel l'utilise. */
    fun stopPreview() = executor.execute {
        previewing = false
        if (pc == null) stopCamera()
    }

    /** Raccroche : ferme la connexion et coupe la caméra (sauf test caméra en cours). */
    fun stop() = executor.execute {
        pc?.dispose()
        pc = null
        remoteDescriptionSet = false
        pendingCandidates.clear()
        stopAudio()
        if (!previewing) stopCamera()
    }

    /** Micro → piste audio envoyée à l'autre, et son de l'appel sur le haut-parleur. */
    private fun startAudio() {
        if (audioTrack != null) return
        val source = factory.createAudioSource(MediaConstraints())
        val track = factory.createAudioTrack("micro", source)
        track.setEnabled(!_micMuted.value && !cameraPaused)
        audioSource = source
        audioTrack = track
        routeToSpeaker(true)
    }

    private fun stopAudio() {
        audioTrack?.dispose()
        audioTrack = null
        audioSource?.dispose()
        audioSource = null
        routeToSpeaker(false)
        _micMuted.value = false
    }

    /**
     * Mode « communication » (celui des appels : annulation d'écho active) et son sur le
     * haut-parleur plutôt que l'écouteur : le téléphone est posé dans le diorama, pas à l'oreille.
     */
    private fun routeToSpeaker(on: Boolean) {
        val am = audioManager ?: return
        runCatching {
            if (on) {
                savedAudioMode = am.mode
                am.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= 31) {
                    am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = true
                }
            } else {
                if (Build.VERSION.SDK_INT >= 31) {
                    am.clearCommunicationDevice()
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = false
                }
                am.mode = savedAudioMode
            }
        }.onFailure { log("Son : routage impossible : ${it.message}") }
    }

    private fun stopCamera() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        capturer = null
        localTrack?.dispose()
        localTrack = null
        videoSource?.dispose()
        videoSource = null
        textureHelper?.dispose()
        textureHelper = null
    }

    fun release() {
        stop()
        executor.execute {
            previewing = false
            stopCamera()
            factory.dispose()
            audioDevice.release()
            eglBase.release()
        }
        executor.shutdown()
    }

    private fun onRemoteDescriptionSet() = executor.execute {
        remoteDescriptionSet = true
        pendingCandidates.forEach { pc?.addIceCandidate(it) }
        pendingCandidates.clear()
    }

    private fun startCamera() {
        try {
            val enumerator = Camera2Enumerator(appContext)
            val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.first()
            val helper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
            val source = factory.createVideoSource(false)
            val cam = enumerator.createCapturer(name, null)
            cam.initialize(helper, appContext, source.capturerObserver)
            // 4:3 pour garder le cercle fisheye. Si l'appel arrive app en arrière-plan,
            // la capture démarrera au retour sur l'app (resumeCamera).
            if (!cameraPaused) cam.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
            val track = factory.createVideoTrack("camera", source)
            track.setEnabled(!cameraPaused)
            track.addSink(localSink)
            track.addSink(previewSink)
            textureHelper = helper
            videoSource = source
            capturer = cam
            localTrack = track
        } catch (e: Exception) {
            log("Caméra indisponible : ${e.message}")
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            log("ICE local : ${describe(candidate)}")
            sendSignal(
                JSONObject()
                    .put("type", "ice")
                    .put("sdpMid", candidate.sdpMid)
                    .put("sdpMLineIndex", candidate.sdpMLineIndex)
                    .put("candidate", candidate.sdp)
                    .toString()
            )
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            when (val track = transceiver.receiver.track()) {
                is VideoTrack -> track.addSink(remoteSink)
                // Le son reçu est joué tout seul par le module audio : rien à brancher.
                is AudioTrack -> log("WebRTC : son de l'autre appareil reçu")
            }
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            log("WebRTC : ICE $state")
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) log("WebRTC : collecte des adresses terminée")
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) {}
        override fun onAddStream(stream: MediaStream) {}
        override fun onRemoveStream(stream: MediaStream) {}
        override fun onDataChannel(channel: DataChannel) {}
        override fun onRenegotiationNeeded() {}
    }

    /** SdpObserver qui journalise les erreurs ; on ne surcharge que ce qui sert. */
    private open inner class SdpAdapter(private val what: String) : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) = log("WebRTC : échec $what : $error")
        override fun onSetFailure(error: String?) = log("WebRTC : échec $what : $error")
    }

    /**
     * Relais vers une vue qui peut apparaître ou disparaître avec l'écran.
     * [transform] peut remplacer l'image (null = image d'origine) ; la nouvelle est libérée ici.
     */
    class ProxySink(private val transform: ((VideoFrame) -> VideoFrame?)? = null) : VideoSink {
        @Volatile var target: VideoSink? = null

        /** Ratio largeur/hauteur affiché de la dernière image reçue, avant [transform] (0 = aucune). */
        @Volatile var sourceRatio = 0f
            private set

        override fun onFrame(frame: VideoFrame) {
            sourceRatio = frame.rotatedWidth.toFloat() / frame.rotatedHeight
            val t = target ?: return
            val out = transform?.invoke(frame)
            if (out == null) {
                t.onFrame(frame)
            } else {
                t.onFrame(out)
                out.release()
            }
        }
    }

    companion object {
        /** « candidate:… 1 udp 2122260223 10.23.241.53 45678 typ host … » → « udp 10.23.241.53:45678 host ». */
        private fun describe(c: IceCandidate): String {
            val f = c.sdp.split(' ')
            return if (f.size >= 8) "${f[2]} ${f[4]}:${f[5]} ${f[7]}" else c.sdp
        }

        private const val CAPTURE_WIDTH = 640
        private const val CAPTURE_HEIGHT = 480
        private const val CAPTURE_FPS = 30
    }
}
