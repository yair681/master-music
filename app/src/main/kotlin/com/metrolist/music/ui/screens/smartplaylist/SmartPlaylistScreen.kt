/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.smartplaylist

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton as MenuIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.taste.SmartPlaylistFailure
import com.metrolist.music.taste.SmartPlaylistNote
import com.metrolist.music.taste.SmartPlaylistResult
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.component.LocalMenuState
import com.metrolist.music.ui.component.YouTubeListItem
import com.metrolist.music.ui.component.recommendationReasonText
import com.metrolist.music.ui.menu.YouTubeSongMenu
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.viewmodels.SmartPlaylistUiState
import com.metrolist.music.viewmodels.SmartPlaylistViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SmartPlaylistScreen(
    navController: NavController,
    initialPrompt: String? = null,
    viewModel: SmartPlaylistViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val menuState = LocalMenuState.current
    val haptic = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val playerConnection = LocalPlayerConnection.current ?: return
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsStateWithLifecycle()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsStateWithLifecycle()

    val state by viewModel.state.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var text by rememberSaveable { mutableStateOf("") }

    val savedMessage = stringResource(R.string.smart_playlist_saved)
    LaunchedEffect(Unit) {
        viewModel.saved.collect { name ->
            Toast.makeText(context, savedMessage.format(name), Toast.LENGTH_SHORT).show()
        }
    }

    fun generate(request: String) {
        focusManager.clearFocus()
        viewModel.generate(request)
    }

    // A prompt handed over by another screen (the example chips on the intelligence screen) runs once.
    var initialPromptHandled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialPrompt) {
        if (!initialPromptHandled && !initialPrompt.isNullOrBlank()) {
            initialPromptHandled = true
            text = initialPrompt
            generate(initialPrompt)
        }
    }

    val examples =
        listOf(
            stringResource(R.string.smart_playlist_example_style),
            stringResource(R.string.smart_playlist_example_forgotten),
            stringResource(R.string.smart_playlist_example_new),
        )

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
    ) {
        item(key = "input") {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.smart_playlist_hint)) },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { generate(text) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { generate(text) },
                    enabled = text.isNotBlank() && state !is SmartPlaylistUiState.Loading,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text(stringResource(R.string.smart_playlist_generate))
                }
            }
        }

        item(key = "examples") {
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(
                    text = stringResource(R.string.smart_playlist_examples),
                    style = MaterialTheme.typography.titleSmall,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    examples.forEach { example ->
                        SuggestionChip(
                            onClick = {
                                text = example
                                generate(example)
                            },
                            label = { Text(example) },
                        )
                    }
                }
            }
        }

        if (categories.isNotEmpty()) {
            item(key = "categories") {
                Column {
                    Text(
                        text = stringResource(R.string.smart_playlist_moods),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(categories, key = { it }) { category ->
                            AssistChip(
                                onClick = {
                                    focusManager.clearFocus()
                                    text = category
                                    viewModel.generateForCategory(category)
                                },
                                label = { Text(category) },
                            )
                        }
                    }
                }
            }
        }

        when (val current = state) {
            SmartPlaylistUiState.Idle -> Unit
            SmartPlaylistUiState.Loading ->
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            is SmartPlaylistUiState.Done ->
                when (val result = current.result) {
                    is SmartPlaylistResult.Unavailable ->
                        item(key = "unavailable") {
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = failureText(result.reason),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = stringResource(R.string.smart_playlist_supported),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    is SmartPlaylistResult.Ready -> {
                        item(key = "summary") {
                            val minutes = (result.totalMs / 60_000.0).roundToInt()
                            Column(
                                modifier = Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = result.title,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    text = stringResource(R.string.smart_playlist_summary, result.songs.size, minutes),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                result.notes.forEach { note ->
                                    Text(
                                        text = noteText(note),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            playerConnection.playQueue(
                                                ListQueue(
                                                    title = result.title,
                                                    items = result.songs.map { it.song.payload.toMediaItem() },
                                                ),
                                            )
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.play),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.smart_playlist_play))
                                    }
                                    OutlinedButton(onClick = viewModel::save) {
                                        Icon(
                                            painter = painterResource(R.drawable.playlist_add),
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(stringResource(R.string.smart_playlist_save))
                                    }
                                }
                            }
                        }
                        itemsIndexed(
                            items = result.songs,
                            key = { _, rec -> "smart_playlist_${rec.song.id}" },
                        ) { index, rec ->
                            val item = rec.song.payload
                            YouTubeListItem(
                                item = item,
                                subtitleOverride = "${item.artists.joinToString { it.name }} • ${recommendationReasonText(rec.reason)}",
                                isActive = item.id == mediaMetadata?.id,
                                isPlaying = isPlaying,
                                isSwipeable = false,
                                trailingContent = {
                                    MenuIconButton(
                                        onClick = {
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = item,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.more_vert),
                                            contentDescription = null,
                                        )
                                    }
                                },
                                modifier =
                                    Modifier.combinedClickable(
                                        onClick = {
                                            if (item.id == mediaMetadata?.id) {
                                                playerConnection.togglePlayPause()
                                            } else {
                                                playerConnection.playQueue(
                                                    ListQueue(
                                                        title = result.title,
                                                        items = result.songs.map { it.song.payload.toMediaItem() },
                                                        startIndex = index,
                                                    ),
                                                )
                                            }
                                        },
                                        onLongClick = {
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            menuState.show {
                                                YouTubeSongMenu(
                                                    song = item,
                                                    onDismiss = menuState::dismiss,
                                                )
                                            }
                                        },
                                    ),
                            )
                        }
                    }
                }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.smart_playlist)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun failureText(reason: SmartPlaylistFailure): String =
    when (reason) {
        SmartPlaylistFailure.NotUnderstood -> stringResource(R.string.smart_playlist_error_not_understood)
        is SmartPlaylistFailure.ArtistNotFound -> stringResource(R.string.smart_playlist_error_artist, reason.name)
        is SmartPlaylistFailure.CategoryNotFound ->
            stringResource(R.string.smart_playlist_error_category, reason.words.joinToString(" "))
        SmartPlaylistFailure.NotEnoughHistory -> stringResource(R.string.smart_playlist_error_history)
        SmartPlaylistFailure.NoMatchingSongs -> stringResource(R.string.smart_playlist_error_no_songs)
        SmartPlaylistFailure.NetworkError -> stringResource(R.string.smart_playlist_error_network)
    }

@Composable
private fun noteText(note: SmartPlaylistNote): String =
    when (note) {
        is SmartPlaylistNote.ShorterThanRequested ->
            stringResource(
                R.string.smart_playlist_note_shorter,
                (note.actualMs / 60_000.0).roundToInt(),
                (note.targetMs / 60_000.0).roundToInt(),
            )
        is SmartPlaylistNote.FewerSongsThanRequested ->
            stringResource(R.string.smart_playlist_note_fewer, note.actual, note.requested)
        is SmartPlaylistNote.IgnoredWords ->
            stringResource(R.string.smart_playlist_note_ignored, note.words.joinToString(" "))
    }
