package fr.antoineassocies.galene.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import fr.antoineassocies.galene.R
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import kotlin.math.ceil
import kotlin.math.sqrt

/** One grid cell: a participant, with or without video. */
data class VideoTile(
    val key: String,
    val label: String,
    val track: VideoTrack?,
    val mirror: Boolean = false,
    val speaking: Boolean = false,
    val raisedHand: Boolean = false,
)

/**
 * Grid filling all available space: n tiles → ⌈√n⌉ columns.
 * No scrolling: beyond a dozen participants tiles get small, which is acceptable
 * for the intended use (small meetings).
 */
@Composable
fun VideoGrid(
    tiles: List<VideoTile>,
    eglContext: EglBase.Context,
    onTileClick: (VideoTile) -> Unit,
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
                        TileView(tile, eglContext, Modifier.weight(1f).fillMaxHeight(), onClick = { onTileClick(tile) })
                    }
                }
                // Empty cells keep tiles the same width on the last row
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A single participant over all available space. The video is shown whole
 * (letterboxed if needed) rather than cropped: everything should be visible, e.g. a whiteboard.
 */
@Composable
fun FullscreenTile(
    tile: VideoTile,
    eglContext: EglBase.Context,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Fill the space, cutting the edges (Picture-in-Picture), rather than show the whole picture. */
    crop: Boolean = false,
) {
    TileView(
        tile, eglContext,
        modifier.background(Color.Black),
        onClick = onClick,
        scaling = if (crop) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT,
        rounded = false,
    )
}

@Composable
private fun TileView(
    tile: VideoTile,
    eglContext: EglBase.Context,
    modifier: Modifier,
    onClick: () -> Unit,
    scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
    rounded: Boolean = true,
) {
    val shape = if (rounded) RoundedCornerShape(12.dp) else RectangleShape
    Box(
        modifier
            .clip(shape)
            .background(if (rounded) MaterialTheme.colorScheme.surfaceVariant else Color.Black)
    ) {
        if (tile.track != null) {
            VideoRenderer(tile.track, eglContext, tile.mirror, Modifier.fillMaxSize(), scaling)
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
        if (tile.raisedHand) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            ) {
                Icon(
                    Icons.Default.PanTool,
                    contentDescription = stringResource(R.string.label_hand_raised),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(6.dp).size(18.dp),
                )
            }
        }
        // Speaking: coloured outline, drawn over the video
        if (tile.speaking) {
            Box(Modifier.matchParentSize().border(3.dp, MaterialTheme.colorScheme.primary, shape))
        }
        // Transparent layer above the SurfaceView: touches reach Compose, not the native
        // view, which would ignore or swallow them depending on the version
        Box(Modifier.matchParentSize().clickable(onClick = onClick))
    }
}

/**
 * Renders a WebRTC track in a SurfaceViewRenderer.
 * `key(track)` recreates the view if the track changes, so no orphan sink is ever kept.
 * The runCatching calls cover a PeerConnection that already released the track (stream closed).
 */
@Composable
fun VideoRenderer(
    track: VideoTrack,
    eglContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier,
    scaling: RendererCommon.ScalingType = RendererCommon.ScalingType.SCALE_ASPECT_FILL,
) {
    key(track) {
        AndroidView(
            factory = { ctx ->
                SurfaceViewRenderer(ctx).apply {
                    init(eglContext, null)
                    setEnableHardwareScaler(true)
                    runCatching { track.addSink(this) }
                }
            },
            update = {
                it.setMirror(mirror)
                it.setScalingType(scaling)
            },
            onRelease = { renderer ->
                runCatching { track.removeSink(renderer) }
                renderer.release()
            },
            modifier = modifier,
        )
    }
}
