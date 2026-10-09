package fr.antoineassocies.galene.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.ceil
import kotlin.math.sqrt

/** Une case de la grille : un participant, avec ou sans vidéo. */
data class VideoTile(
    val key: String,
    val label: String,
    val track: VideoTrack?,
    val mirror: Boolean = false,
)

/**
 * Grille qui remplit tout l'espace disponible : n tuiles → ⌈√n⌉ colonnes.
 * Pas de scroll : au-delà d'une dizaine de participants les tuiles deviennent petites,
 * acceptable pour l'usage visé (petites réunions).
 */
@Composable
fun VideoGrid(
    tiles: List<VideoTile>,
    eglContext: EglBase.Context,
    modifier: Modifier = Modifier,
) {
    if (tiles.isEmpty()) return
    val columns = ceil(sqrt(tiles.size.toDouble())).toInt()
    val rows = tiles.chunked(columns)

    Column(modifier.padding(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rows.forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { tile ->
                    key(tile.key) {
                        TileView(tile, eglContext, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                // Cases vides pour garder des tuiles de même largeur sur la dernière ligne
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun TileView(tile: VideoTile, eglContext: EglBase.Context, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (tile.track != null) {
            VideoRenderer(tile.track, eglContext, tile.mirror, Modifier.fillMaxSize())
        } else {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(64.dp).align(Alignment.Center)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        tile.label.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
        Text(
            tile.label,
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

/**
 * Affiche une piste WebRTC dans un SurfaceViewRenderer.
 * Le `key(track)` recrée la vue si la piste change, pour ne jamais garder un sink orphelin.
 * Les runCatching couvrent le cas où la PeerConnection a déjà libéré la piste (stream fermé).
 */
@Composable
fun VideoRenderer(track: VideoTrack, eglContext: EglBase.Context, mirror: Boolean, modifier: Modifier = Modifier) {
    key(track) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglContext, null)
                    setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                    setEnableHardwareScaler(true)
                    runCatching { track.addSink(this) }
                }
            },
            update = { it.setMirror(mirror) },
            onRelease = { renderer ->
                runCatching { track.removeSink(renderer) }
                renderer.release()
            },
            modifier = modifier,
        )
    }
}
