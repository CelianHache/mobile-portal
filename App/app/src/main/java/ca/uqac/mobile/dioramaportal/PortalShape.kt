package ca.uqac.mobile.dioramaportal

import org.webrtc.VideoFrame
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Forme du portail : comment l'image reçue est recadrée puis étirée avant l'affichage.
 * Utile avec une lentille fisheye 180° : l'image utile n'est qu'un cercle au centre,
 * on zoome dessus puis on l'étire pour remplir l'écran comme une vraie fenêtre.
 *
 * Tout est exprimé dans le sens d'affichage (rotation de l'image déjà appliquée) :
 * - [zoom] ≥ 1 : on ne garde que 1/zoom de la largeur et de la hauteur ;
 * - [stretch] : étirement horizontal (> 1 plus large, < 1 plus haut) ;
 * - [offsetX], [offsetY] : décalage du recadrage dans la marge laissée par le zoom,
 *   de -1 (tout à gauche / en haut) à +1 (tout à droite / en bas). Sans zoom, pas de marge :
 *   le décalage n'a aucun effet.
 */
data class PortalShape(
    val zoom: Float = 1f,
    val stretch: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val isIdentity get() = zoom == 1f && stretch == 1f && offsetX == 0f && offsetY == 0f

    /**
     * Nouvelle image recadrée et étirée (à libérer par l'appelant), ou null si rien à faire.
     * Pour une image GPU (cas du décodeur matériel), c'est seulement une matrice : pas de copie.
     */
    fun transform(frame: VideoFrame): VideoFrame? {
        if (isIdentity) return null
        val buf = frame.buffer
        val bw = buf.width
        val bh = buf.height
        val rot = ((frame.rotation % 360) + 360) % 360
        val swap = rot % 180 != 0

        // Le zoom est le même sur les deux axes : le recadrage se calcule directement dans l'image.
        val cw = even((bw / zoom).roundToInt()).coerceIn(2, bw)
        val ch = even((bh / zoom).roundToInt()).coerceIn(2, bh)

        // Décalage du sens d'affichage vers le sens de l'image (avant rotation).
        val (ox, oy) = when (rot) {
            90 -> offsetY to -offsetX
            180 -> -offsetX to -offsetY
            270 -> -offsetY to offsetX
            else -> offsetX to offsetY
        }
        // Le décalage parcourt toute la marge, quel que soit le zoom.
        val cx = ((bw - cw) / 2f * (1f + ox)).roundToInt().coerceIn(0, bw - cw)
        val cy = ((bh - ch) / 2f * (1f + oy)).roundToInt().coerceIn(0, bh - ch)

        // Taille de sortie dans le sens d'affichage : largeur multipliée par l'étirement.
        var outW = (if (swap) ch else cw) * stretch
        var outH = (if (swap) cw else ch).toFloat()
        val scale = MAX_SIDE / max(outW, outH)
        if (scale < 1f) { outW *= scale; outH *= scale }
        val dispW = even(outW.roundToInt()).coerceAtLeast(2)
        val dispH = even(outH.roundToInt()).coerceAtLeast(2)

        val out = buf.cropAndScale(cx, cy, cw, ch, if (swap) dispH else dispW, if (swap) dispW else dispH)
        return VideoFrame(out, frame.rotation, frame.timestampNs)
    }

    companion object {
        const val MIN_ZOOM = 1f
        const val MAX_ZOOM = 4f
        const val MIN_STRETCH = 0.25f
        const val MAX_STRETCH = 4f
        const val MAX_OFFSET = 1f

        /** Plus grand côté de l'image produite (limite le coût si l'image est logicielle). */
        private const val MAX_SIDE = 1280f

        private fun even(v: Int) = v and 1.inv()
    }
}
