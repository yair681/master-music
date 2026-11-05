/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.dj

import android.os.SystemClock
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metrolist.music.R
import com.metrolist.music.playback.audio.DjFx
import com.metrolist.music.playback.audio.DjFxEngine
import com.metrolist.music.playback.audio.DjFxPreset
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private val PanelBg = Color(0xF20A0A16)
private val Blue = Color(0xFF2E6BFF)
private val Teal = Color(0xFF1DE9B6)
private val Purple = Color(0xFF8A4DFF)
private val Pink = Color(0xFFFF3D9A)

private val PadOrder =
    listOf(
        DjFx.BASS_DROP to Pink,
        DjFx.STUTTER to Purple,
        DjFx.BUILD_UP to Blue,
        DjFx.DJ_DROP to Pink,
        DjFx.QUICK_CUTS to Teal,
        DjFx.BASS_BOOST to Blue,
        DjFx.ECHO to Purple,
        DjFx.REVERB to Teal,
    )

private fun DjFx.label(): String =
    when (this) {
        DjFx.BASS_DROP -> "BASS\nDROP"
        DjFx.STUTTER -> "BEAT\nSTUTTER"
        DjFx.BUILD_UP -> "BUILD\nUP"
        DjFx.DJ_DROP -> "DJ\nDROP"
        DjFx.QUICK_CUTS -> "QUICK\nCUTS"
        DjFx.BASS_BOOST -> "BASS\nBOOST"
        DjFx.ECHO -> "ECHO"
        DjFx.REVERB -> "REVERB"
        DjFx.ECHO_OUT -> "ECHO\nOUT"
    }

@Composable
internal fun DjFxPanel(
    engine: DjFxEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready by engine.ready.collectAsStateWithLifecycle()
    val bpm by engine.bpm.collectAsStateWithLifecycle()
    val toggles by engine.toggles.collectAsStateWithLifecycle()
    val preset by engine.preset.collectAsStateWithLifecycle()
    // Read so the editor recomposes when amounts or lengths change.
    engine.settingsVersion.collectAsStateWithLifecycle().value
    var selected by remember { mutableStateOf(DjFx.BASS_DROP) }
    val autoFx by engine.autoFx.collectAsStateWithLifecycle()
    val smart by engine.smartTransitions.collectAsStateWithLifecycle()
    val lastEvent by engine.lastEvent.collectAsStateWithLifecycle()

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(lastEvent) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            val event = lastEvent ?: break
            if (now > event.endsAtElapsedMs) break
            delay(100)
        }
    }
    val runningShot = lastEvent?.takeIf { now <= it.endsAtElapsedMs }

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(PanelBg)
                .border(
                    1.dp,
                    Brush.horizontalGradient(listOf(Blue, Teal, Purple, Pink)),
                    RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                ).navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FX",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 3.sp,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = if (bpm > 0f) "${bpm.roundToInt()} BPM" else "— BPM",
                color = if (bpm > 0f) Teal else Color.White.copy(alpha = 0.4f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier =
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.06f))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            runningShot?.takeIf { it.auto }?.let {
                Text(
                    text = "AUTO · ${it.fx.label().replace('\n', ' ')}",
                    color = Pink,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(8.dp))
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.close),
                    contentDescription = null,
                    tint = Color.White,
                )
            }
        }

        FxSwitchRow(
            title = "Smart Transitions",
            subtitle = stringResource(R.string.dj_smart_transitions_hint),
            checked = smart,
            enabled = true,
            onChange = engine::setSmartTransitions,
        )
        FxSwitchRow(
            title = stringResource(R.string.dj_auto_fx),
            subtitle = null,
            checked = autoFx,
            enabled = ready,
            onChange = engine::setAutoFx,
        )

        if (!ready) {
            Text(
                text = stringResource(R.string.dj_fx_unavailable),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }

        Spacer(Modifier.height(8.dp))

        PadOrder.chunked(4).forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row.forEach { (fx, color) ->
                    val active =
                        if (fx.oneShot) runningShot?.fx == fx else fx in toggles
                    FxPad(
                        label = fx.label(),
                        color = color,
                        active = active,
                        toggle = !fx.oneShot,
                        enabled = ready,
                        selected = fx == selected,
                        onClick = {
                            selected = fx
                            if (fx.oneShot) engine.trigger(fx) else engine.toggle(fx)
                        },
                        onLongClick = { selected = fx },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.dj_fx_preset),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.width(64.dp),
            )
            DjFxPreset.entries.forEach { p ->
                Chip(
                    text = p.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = p == preset,
                    color = Pink,
                    onClick = { engine.applyPreset(p) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val selectedColor = PadOrder.first { it.first == selected }.second
        Text(
            text = selected.label().replace('\n', ' '),
            color = selectedColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 1.sp,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.dj_fx_intensity),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.width(64.dp),
            )
            val amount = engine.amountOf(selected)
            Slider(
                value = amount,
                onValueChange = { engine.setAmount(selected, it) },
                enabled = ready,
                colors =
                    SliderDefaults.colors(
                        thumbColor = selectedColor,
                        activeTrackColor = selectedColor,
                        inactiveTrackColor = Color.White.copy(alpha = 0.15f),
                    ),
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(amount * 100).roundToInt()}%",
                color = Color.White,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(44.dp),
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(if (selected.oneShot) R.string.dj_fx_length_beats else R.string.dj_fx_character),
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.width(64.dp),
            )
            selected.optionLabels.forEachIndexed { index, label ->
                Chip(
                    text = label,
                    selected = engine.optionOf(selected) == index,
                    color = selectedColor,
                    onClick = { engine.setOption(selected, index) },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FxPad(
    label: String,
    color: Color,
    active: Boolean,
    toggle: Boolean,
    enabled: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier =
            modifier
                .aspectRatio(1f)
                .alpha(if (enabled) 1f else 0.35f)
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            color.copy(alpha = if (active) 0.85f else 0.22f),
                            color.copy(alpha = if (active) 0.45f else 0.08f),
                        ),
                    ),
                ).border(
                    if (active || selected) 2.dp else 1.dp,
                    if (selected && !active) Color.White.copy(alpha = 0.85f) else color.copy(alpha = if (active) 1f else 0.5f),
                    shape,
                ).combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
        if (toggle) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(7.dp)
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(if (active) color else Color.White.copy(alpha = 0.2f)),
            )
        }
    }
}

@Composable
private fun FxSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    color = Color.White.copy(alpha = 0.55f),
                    fontSize = 11.sp,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Purple,
                    uncheckedTrackColor = Color.White.copy(alpha = 0.1f),
                ),
        )
    }
}

@Composable
private fun Chip(
    text: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) color.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.07f))
                .border(1.dp, color.copy(alpha = if (selected) 1f else 0.4f), RoundedCornerShape(50))
                .clickable(onClick = onClick)
                .padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
