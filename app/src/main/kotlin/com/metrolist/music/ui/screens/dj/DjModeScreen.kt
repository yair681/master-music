/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.dj

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.C
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.metadata
import com.metrolist.music.models.MediaMetadata
import com.metrolist.music.utils.makeTimeString
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val DjBackground = Color(0xFF04040B)
private val DjBlue = Color(0xFF2E6BFF)
private val DjTeal = Color(0xFF1DE9B6)
private val DjPurple = Color(0xFF8A4DFF)
private val DjPink = Color(0xFFFF3D9A)
private val DjPalette = listOf(DjBlue, DjTeal, DjPurple, DjPink)

@Composable
fun DjModeScreen(navController: NavController) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val service = playerConnection.service
    val context = LocalContext.current

    val isPlaying by playerConnection.isPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    fun exitDj() {
        service.stopDjMode()
        navController.popBackStack()
    }

    LaunchedEffect(Unit) {
        if (service.castConnectionHandler?.isCasting?.value == true) {
            Toast.makeText(context, R.string.dj_unavailable_casting, Toast.LENGTH_SHORT).show()
            navController.popBackStack()
        } else {
            service.startDjMode()
        }
    }
    var showFx by remember { mutableStateOf(false) }
    BackHandler { if (showFx) showFx = false else exitDj() }

    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(C.TIME_UNSET) }
    var nextUp by remember { mutableStateOf<MediaMetadata?>(null) }
    var audioSessionId by remember { mutableIntStateOf(C.AUDIO_SESSION_ID_UNSET) }

    LaunchedEffect(resumed) {
        while (resumed && isActive) {
            val player = runCatching { playerConnection.player }.getOrNull()
            if (player == null) {
                delay(500)
                continue
            }
            position = player.currentPosition
            duration = player.duration
            val nextIndex = player.nextMediaItemIndex
            nextUp = if (nextIndex != C.INDEX_UNSET) player.getMediaItemAt(nextIndex).metadata else null
            audioSessionId = player.audioSessionId
            delay(250)
        }
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasAudioPermission = granted
        }

    val levels = remember { DjAudioLevels() }

    DisposableEffect(audioSessionId, hasAudioPermission, resumed) {
        val visualizer =
            if (hasAudioPermission && resumed && audioSessionId > 0) levels.attach(audioSessionId) else null
        onDispose { levels.detach(visualizer) }
    }

    // Drives every animation. Runs only while the screen is visible, and drops to a
    // low frame rate when the music is paused to save battery.
    var frameTime by remember { mutableFloatStateOf(0f) }
    var discAngle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(resumed, isPlaying) {
        if (!resumed) return@LaunchedEffect
        var last = withFrameNanosCompat()
        while (isActive) {
            val now = withFrameNanosCompat()
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.1f)
            last = now
            levels.step(dt, isPlaying)
            frameTime += dt
            if (isPlaying) discAngle = (discAngle + dt * 24f) % 360f
            if (!isPlaying) delay(90)
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(DjBackground),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = frameTime
            val energy = levels.energy
            val bass = levels.bass
            val w = size.width
            val h = size.height

            DjPalette.forEachIndexed { i, color ->
                val phase = t * (0.18f + i * 0.05f) + i * 1.7f
                val center =
                    Offset(
                        w * (0.5f + 0.38f * sin(phase)),
                        h * (0.42f + 0.32f * cos(phase * 0.8f + i)),
                    )
                val radius = w * (0.55f + 0.35f * energy + 0.1f * sin(t + i))
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(color.copy(alpha = 0.16f + 0.34f * energy), Color.Transparent),
                            center = center,
                            radius = radius,
                        ),
                    radius = radius,
                    center = center,
                )
            }

            val beams = 5
            for (i in 0 until beams) {
                val swing = sin(t * (0.6f + i * 0.13f) + i * 2.1f)
                val angle = (PI / 2 + swing * 0.75).toFloat()
                val origin = Offset(w * (i + 0.5f) / beams, -10f)
                val length = h * 1.25f
                val end = Offset(origin.x + cos(angle) * length, origin.y + sin(angle) * length)
                drawLine(
                    brush =
                        Brush.linearGradient(
                            colors =
                                listOf(
                                    DjPalette[i % DjPalette.size].copy(alpha = 0.05f + 0.4f * bass),
                                    Color.Transparent,
                                ),
                            start = origin,
                            end = end,
                        ),
                    start = origin,
                    end = end,
                    strokeWidth = 6f + 18f * bass,
                    cap = StrokeCap.Round,
                )
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = ::exitDj,
                    modifier =
                        Modifier
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.08f)),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.close),
                        contentDescription = stringResource(R.string.dj_exit),
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "MASTER DJ",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 4.sp,
                    modifier = Modifier.weight(1f),
                )
                if (!hasAudioPermission) {
                    Text(
                        text = stringResource(R.string.dj_live_visualizer),
                        color = DjTeal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                            Modifier
                                .clip(RoundedCornerShape(50))
                                .border(1.dp, DjTeal.copy(alpha = 0.6f), RoundedCornerShape(50))
                                .clickable { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "FX",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp,
                    modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(DjPurple, DjPink)))
                            .clickable { showFx = true }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier = Modifier.size(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    @Suppress("UNUSED_VARIABLE")
                    val tick = frameTime
                    val bands = levels.bands
                    val count = bands.size * 2
                    val center = Offset(size.width / 2, size.height / 2)
                    val inner = size.minDimension * 0.36f
                    for (i in 0 until count) {
                        val band = bands[if (i < bands.size) i else count - 1 - i]
                        val angle = (2 * PI * i / count - PI / 2).toFloat()
                        val length = 8f + band * size.minDimension * 0.15f
                        val dir = Offset(cos(angle), sin(angle))
                        drawLine(
                            color = lerpPalette(i.toFloat() / count),
                            start = center + dir * inner,
                            end = center + dir * (inner + length),
                            strokeWidth = 7f,
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(
                        brush =
                            Brush.radialGradient(
                                colors = listOf(DjPink.copy(alpha = 0.35f * levels.bass), Color.Transparent),
                                center = center,
                                radius = inner * 1.4f,
                            ),
                        radius = inner * 1.4f,
                        center = center,
                    )
                }
                AsyncImage(
                    model = mediaMetadata?.thumbnailUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier =
                        Modifier
                            .size(196.dp)
                            .graphicsLayer {
                                rotationZ = discAngle
                                val pulse = 1f + 0.04f * levels.bass
                                scaleX = pulse
                                scaleY = pulse
                            }.clip(CircleShape)
                            .background(Color(0xFF15152A))
                            .border(3.dp, Brush.sweepGradient(DjPalette + DjBlue), CircleShape),
                )
                Box(
                    modifier =
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(DjBackground),
                )
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = mediaMetadata?.title ?: stringResource(R.string.dj_building_set),
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mediaMetadata?.artists?.joinToString { it.name }.orEmpty(),
                color = DjTeal.copy(alpha = 0.85f),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(20.dp))

            val progress =
                if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.12f)),
            ) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(progress)
                            .height(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Brush.horizontalGradient(listOf(DjBlue, DjTeal, DjPurple, DjPink))),
                )
            }
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(makeTimeString(position), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Text(
                    if (duration > 0) makeTimeString(duration) else "",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            nextUp?.let { next ->
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.06f))
                            .border(1.dp, DjPurple.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
                            .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AsyncImage(
                        model = next.thumbnailUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier =
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF15152A)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.dj_up_next),
                            color = DjPink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                        )
                        Text(
                            text = next.title,
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = next.artists.joinToString { it.name },
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            Box(
                modifier =
                    Modifier
                        .size(80.dp)
                        .graphicsLayer {
                            if (frameTime < 0f) return@graphicsLayer
                            val pulse = 1f + 0.06f * levels.bass
                            scaleX = pulse
                            scaleY = pulse
                        }.clip(CircleShape)
                        .background(Brush.linearGradient(listOf(DjBlue, DjPurple, DjPink)))
                        .clickable { playerConnection.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(40.dp),
                )
            }

            Spacer(Modifier.height(12.dp))

            Canvas(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
            ) {
                val t = frameTime
                val bands = levels.bands
                val bars = 48
                val gap = size.width / bars
                val mid = size.height / 2
                for (i in 0 until bars) {
                    val band = bands[(i * bands.size / bars).coerceAtMost(bands.size - 1)]
                    val wave = 0.5f + 0.5f * sin(t * 4f + i * 0.45f)
                    val amp = (0.08f + band * 0.8f * (0.6f + 0.4f * wave)).coerceAtMost(1f)
                    val half = mid * amp
                    val x = gap * (i + 0.5f)
                    drawLine(
                        color = lerpPalette(i.toFloat() / bars),
                        start = Offset(x, mid - half),
                        end = Offset(x, mid + half),
                        strokeWidth = gap * 0.55f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }

        if (showFx) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.35f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { showFx = false },
            )
        }
        AnimatedVisibility(
            visible = showFx,
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            DjFxPanel(engine = service.djFx, onClose = { showFx = false })
        }
    }
}

private fun lerpPalette(fraction: Float): Color {
    val scaled = fraction.coerceIn(0f, 1f) * (DjPalette.size - 1)
    val index = scaled.toInt().coerceAtMost(DjPalette.size - 2)
    return androidx.compose.ui.graphics.lerp(DjPalette[index], DjPalette[index + 1], scaled - index)
}

private suspend fun withFrameNanosCompat(): Long = androidx.compose.runtime.withFrameNanos { it }
