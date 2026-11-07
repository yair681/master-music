/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.intelligence

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import coil3.compose.AsyncImage
import com.metrolist.music.LocalListenTogetherManager
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.playback.PlayerConnection
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.ui.component.NavigationTitle
import com.metrolist.music.ui.component.SongListItem
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.IntelligenceArtist
import com.metrolist.music.viewmodels.IntelligenceMixState
import com.metrolist.music.viewmodels.IntelligenceSong
import com.metrolist.music.viewmodels.IntelligenceUiState
import com.metrolist.music.viewmodels.IntelligenceViewModel
import java.text.NumberFormat

private const val RADIO_STATIONS = 5

/**
 * The Music Intelligence tab. Every number and list on it comes from the local taste profile
 * (see [com.metrolist.music.taste.TasteProfileRepository]); nothing is sent to an outside service.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun IntelligenceScreen(
    navController: NavController,
    viewModel: IntelligenceViewModel = hiltViewModel(),
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val listenTogetherManager = LocalListenTogetherManager.current
    val isListenTogetherGuest = listenTogetherManager?.let { it.isInRoom && !it.isHost } ?: false
    val canControlPlayback = !isListenTogetherGuest
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val dailyMix by viewModel.dailyMix.collectAsStateWithLifecycle()

    val lazyListState = rememberLazyListState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val scrollToTop =
        backStackEntry?.savedStateHandle?.getStateFlow("scrollToTop", false)?.collectAsStateWithLifecycle()
    LaunchedEffect(scrollToTop?.value) {
        if (scrollToTop?.value == true) {
            lazyListState.animateScrollToItem(0)
            backStackEntry?.savedStateHandle?.set("scrollToTop", false)
        }
    }

    var stationIndex by rememberSaveable { mutableIntStateOf(0) }
    val numberFormat = remember { NumberFormat.getIntegerInstance() }

    val examples =
        listOf(
            stringResource(R.string.smart_playlist_example_style),
            stringResource(R.string.smart_playlist_example_forgotten),
            stringResource(R.string.smart_playlist_example_new),
        )
    val dailyMixTitle = stringResource(R.string.daily_mix)
    val topSongsTitle = stringResource(R.string.intelligence_top_songs)

    LazyColumn(
        state = lazyListState,
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        val ui = state
        if (ui == null) {
            item(key = "loading") {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                ) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        if (ui.isEmpty) {
            item(key = "empty") {
                SectionCard {
                    Text(
                        text = stringResource(R.string.intelligence_empty_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.intelligence_empty_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item(key = "overview") {
                OverviewCard(ui = ui, numberFormat = numberFormat)
            }

            item(key = "radio") {
                val stations = ui.topSongs.take(RADIO_STATIONS)
                SectionCard {
                    CardTitle(stringResource(R.string.personal_radio), R.drawable.radio)
                    if (stations.isEmpty()) {
                        Text(
                            text = stringResource(R.string.intelligence_radio_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        val selected = stations[stationIndex.coerceIn(0, stations.lastIndex)]
                        Text(
                            text = stringResource(R.string.intelligence_radio_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (stations.size > 1) {
                            Text(
                                text = stringResource(R.string.intelligence_radio_pick),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(stations, key = { _, s -> "station_${s.song.id}" }) { index, station ->
                                    FilterChip(
                                        selected = station.song.id == selected.song.id,
                                        onClick = { stationIndex = index },
                                        label = {
                                            Text(
                                                text = station.song.title,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.widthIn(max = MaxChipLabelWidth),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        Text(
                            text = stringResource(R.string.intelligence_radio_station, selected.song.title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Button(
                            onClick = { playerConnection.startPersonalRadio(selected.song.toMediaMetadata()) },
                            enabled = canControlPlayback,
                        ) {
                            Text(stringResource(R.string.intelligence_radio_start))
                        }
                    }
                }
            }
        }

        item(key = "smart_playlist") {
            SectionCard {
                CardTitle(stringResource(R.string.smart_playlist), R.drawable.queue_music)
                Text(
                    text = stringResource(R.string.intelligence_smart_playlist_desc),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { example ->
                        SuggestionChip(
                            onClick = { navController.navigate("smart_playlist?prompt=${Uri.encode(example)}") },
                            label = { Text(example) },
                        )
                    }
                }
                OutlinedButton(onClick = { navController.navigate("smart_playlist") }) {
                    Text(stringResource(R.string.intelligence_open))
                }
            }
        }

        if (!ui.isEmpty) {
            item(key = "daily_mix") {
                SectionCard {
                    CardTitle(dailyMixTitle, R.drawable.shuffle)
                    when (val mix = dailyMix) {
                        IntelligenceMixState.Loading -> {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }

                        IntelligenceMixState.NotEnoughData -> {
                            Text(
                                text = stringResource(R.string.daily_mix_not_enough_data),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        IntelligenceMixState.Failed -> {
                            Text(
                                text = stringResource(R.string.intelligence_daily_mix_failed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(onClick = viewModel::loadDailyMix) {
                                Text(stringResource(R.string.retry))
                            }
                        }

                        is IntelligenceMixState.Ready -> {
                            Text(
                                text = stringResource(R.string.intelligence_daily_mix_desc, mix.items.size),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(
                                onClick = {
                                    playerConnection.playQueue(
                                        ListQueue(
                                            title = dailyMixTitle,
                                            items = mix.items.map { it.song.payload.toMediaItem() },
                                        ),
                                    )
                                },
                                enabled = canControlPlayback,
                            ) {
                                Text(stringResource(R.string.play))
                            }
                        }
                    }
                }
            }

            if (ui.topArtists.isNotEmpty()) {
                item(key = "top_artists_title") {
                    NavigationTitle(title = stringResource(R.string.intelligence_top_artists))
                }
                ui.topArtists.forEach { artist ->
                    item(key = "top_artist_${artist.taste.artistId}") {
                        ArtistRow(
                            artist = artist,
                            numberFormat = numberFormat,
                            onClick =
                                if (canOpenArtistPage(artist.taste.artistId)) {
                                    { navController.navigate("artist/${artist.taste.artistId}") }
                                } else {
                                    null
                                },
                        )
                    }
                }
            }

            if (ui.topSongs.isNotEmpty()) {
                item(key = "top_songs_title") {
                    NavigationTitle(
                        title = topSongsTitle,
                        onPlayAllClick =
                            if (canControlPlayback) {
                                { playTopSongs(playerConnection, topSongsTitle, ui.topSongs, 0) }
                            } else {
                                null
                            },
                    )
                }
                ui.topSongs.forEachIndexed { index, entry ->
                    item(key = "top_song_${entry.song.id}") {
                        SongListItem(
                            song = entry.song,
                            subtitleOverride =
                                stringResource(
                                    R.string.intelligence_item_stats,
                                    numberFormat.format(entry.taste.listens),
                                    formatListeningTime(entry.taste.listeningTimeMs),
                                ),
                            isActive = entry.song.id == mediaMetadata?.id,
                            isPlaying = isPlaying,
                            isSwipeable = false,
                            modifier =
                                Modifier.clickable(enabled = canControlPlayback) {
                                    if (entry.song.id == mediaMetadata?.id) {
                                        playerConnection.togglePlayPause()
                                    } else {
                                        playTopSongs(playerConnection, topSongsTitle, ui.topSongs, index)
                                    }
                                },
                        )
                    }
                }
            }
        }

        item(key = "stats_link") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .clickable { navController.navigate("stats") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.stats),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.intelligence_stats_link),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.intelligence_stats_link_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "insights_link") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable { navController.navigate("insights") }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.trending_up),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = 16.dp),
                ) {
                    Text(
                        text = stringResource(R.string.insights_link),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(R.string.insights_link_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.navigate_next),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item(key = "bottom_spacer") {
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val MaxChipLabelWidth = 160.dp

private fun playTopSongs(
    playerConnection: PlayerConnection,
    title: String,
    songs: List<IntelligenceSong>,
    startIndex: Int,
) {
    playerConnection.playQueue(
        ListQueue(
            title = title,
            items = songs.map { it.song.toMediaItem() },
            startIndex = startIndex,
        ),
    )
}

@Composable
private fun formatListeningTime(ms: Long): String {
    val duration = listeningDuration(ms)
    return if (duration.hours > 0) {
        stringResource(R.string.duration_hours_minutes, duration.hours.toInt(), duration.minutes.toInt())
    } else {
        stringResource(R.string.duration_minutes_only, duration.minutes.toInt())
    }
}

@Composable
private fun SectionCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(20.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(20.dp),
            content = content,
        )
    }
}

@Composable
private fun CardTitle(
    title: String,
    iconRes: Int,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun OverviewCard(
    ui: IntelligenceUiState,
    numberFormat: NumberFormat,
) {
    val noValue = stringResource(R.string.intelligence_no_value)
    val completion = completionPercent(ui.completionRate)
    SectionCard {
        Text(
            text = stringResource(R.string.intelligence_overview),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.intelligence_listening_time),
                value = formatListeningTime(ui.totalListeningTimeMs),
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.intelligence_listens),
                value = numberFormat.format(ui.totalListens),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile(
                label = stringResource(R.string.intelligence_completion),
                value = completion?.let { "$it%" } ?: noValue,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                label = stringResource(R.string.intelligence_skips),
                value = numberFormat.format(ui.totalSkips),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ArtistRow(
    artist: IntelligenceArtist,
    numberFormat: NumberFormat,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            if (artist.thumbnailUrl != null) {
                AsyncImage(
                    model = artist.thumbnailUrl.resize(144, 144),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.artist),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                text = artist.taste.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    stringResource(
                        R.string.intelligence_item_stats,
                        numberFormat.format(artist.taste.listens),
                        formatListeningTime(artist.taste.listeningTimeMs),
                    ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
