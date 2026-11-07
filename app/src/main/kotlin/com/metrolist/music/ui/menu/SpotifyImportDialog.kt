/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.menu

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.metrolist.music.R
import com.metrolist.music.importer.ImportSummary
import com.metrolist.music.importer.ImportTarget
import com.metrolist.music.importer.NotFoundReason
import com.metrolist.music.importer.NotFoundTrack
import com.metrolist.music.viewmodels.SpotifyImportFailure
import com.metrolist.music.viewmodels.SpotifyImportUiState
import com.metrolist.music.viewmodels.SpotifyImportViewModel

/** Import of a Spotify playlist exported with Exportify. Shown while the import is open. */
@Composable
fun SpotifyImportDialog(
    viewModel: SpotifyImportViewModel,
    onOpenPlaylist: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val busy = state is SpotifyImportUiState.Running || state is SpotifyImportUiState.Saving
    if (state is SpotifyImportUiState.Idle) return

    AlertDialog(
        onDismissRequest = { if (!busy) viewModel.close() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = false),
        title = {
            Text(
                stringResource(
                    if (state is SpotifyImportUiState.Done) R.string.spotify_import_done_title else R.string.spotify_import_title,
                ),
            )
        },
        text = {
            when (val current = state) {
                is SpotifyImportUiState.Ready -> SetupContent(viewModel, current)
                is SpotifyImportUiState.Running -> {
                    val progress = current.progress
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.spotify_import_searching, progress.done, progress.total))
                        LinearProgressIndicator(
                            progress = { if (progress.total == 0) 0f else progress.done.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            stringResource(R.string.spotify_import_found_counts, progress.found, progress.notFound),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                SpotifyImportUiState.Saving ->
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.spotify_import_saving))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                is SpotifyImportUiState.Done -> SummaryContent(current.summary)
                SpotifyImportUiState.Cancelled -> Text(stringResource(R.string.spotify_import_cancelled))
                is SpotifyImportUiState.Failed ->
                    Text(
                        stringResource(
                            when (current.reason) {
                                SpotifyImportFailure.Empty -> R.string.spotify_import_failed_empty
                                SpotifyImportFailure.SaveFailed -> R.string.spotify_import_failed_save
                            },
                        ),
                    )
                SpotifyImportUiState.Idle -> Unit
            }
        },
        confirmButton = {
            when (val current = state) {
                is SpotifyImportUiState.Running ->
                    TextButton(onClick = viewModel::cancel) { Text(stringResource(android.R.string.cancel)) }
                is SpotifyImportUiState.Done -> {
                    val summary = current.summary
                    TextButton(
                        onClick = {
                            viewModel.close()
                            onOpenPlaylist(summary.playlistId)
                        },
                    ) { Text(stringResource(R.string.spotify_import_open_playlist)) }
                }
                SpotifyImportUiState.Saving, is SpotifyImportUiState.Ready -> Unit
                else -> TextButton(onClick = viewModel::close) { Text(stringResource(android.R.string.ok)) }
            }
        },
        dismissButton = {
            if (state is SpotifyImportUiState.Done) {
                TextButton(onClick = viewModel::close) { Text(stringResource(android.R.string.ok)) }
            }
        },
    )
}

@Composable
private fun SetupContent(
    viewModel: SpotifyImportViewModel,
    ready: SpotifyImportUiState.Ready,
) {
    val playlists by viewModel.editablePlaylists.collectAsState()
    var name by rememberSaveable(ready.suggestedName) { mutableStateOf(ready.suggestedName) }
    var toExisting by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    val file = ready.file

    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.spotify_import_file_summary, file.tracks.size))
        if (file.duplicatesInFile.isNotEmpty()) {
            Text(
                stringResource(R.string.spotify_import_file_repeats, file.duplicatesInFile.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (file.skippedRows > 0) {
            Text(
                stringResource(R.string.spotify_import_skipped_rows, file.skippedRows),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(R.string.spotify_import_how),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        ChoiceRow(selected = !toExisting, label = stringResource(R.string.spotify_import_target_new)) { toExisting = false }
        if (!toExisting) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.spotify_import_playlist_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        ChoiceRow(selected = toExisting, label = stringResource(R.string.spotify_import_target_existing)) { toExisting = true }
        if (toExisting) {
            Text(
                stringResource(R.string.spotify_import_existing_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (playlists.isEmpty()) {
                Text(stringResource(R.string.spotify_import_no_local_playlists), style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                ) {
                    playlists.forEach { playlist ->
                        ChoiceRow(
                            selected = selectedId == playlist.id,
                            label = playlist.playlist.name,
                        ) { selectedId = playlist.id }
                    }
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = viewModel::close) { Text(stringResource(android.R.string.cancel)) }
            val target =
                if (toExisting) {
                    selectedId?.takeIf { id -> playlists.any { it.id == id } }?.let { ImportTarget.ExistingPlaylist(it) }
                } else {
                    name.trim().takeIf { it.isNotEmpty() }?.let { ImportTarget.NewPlaylist(it) }
                }
            TextButton(onClick = { target?.let(viewModel::start) }, enabled = target != null) {
                Text(stringResource(R.string.spotify_import_start))
            }
        }
    }
}

@Composable
private fun ChoiceRow(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun SummaryContent(summary: ImportSummary) {
    val context = LocalContext.current
    val listText = notFoundText(context, summary.notFound)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.spotify_import_added, summary.playlistName, summary.added))
        if (summary.alreadyInPlaylist.isNotEmpty()) {
            Text(stringResource(R.string.spotify_import_already_in_playlist, summary.alreadyInPlaylist.size))
        }
        if (summary.repeatedInFile.isNotEmpty()) {
            Text(stringResource(R.string.spotify_import_repeated, summary.repeatedInFile.size))
        }
        Text(stringResource(R.string.spotify_import_not_found, summary.notFound.size))
        if (summary.notFound.isNotEmpty()) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                summary.notFound.forEach { item ->
                    Column {
                        Text(item.track.display, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            reasonText(context, item.reason),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val copied = stringResource(R.string.spotify_import_copied)
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(summary.playlistName, listText))
                        Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                    },
                ) { Text(stringResource(R.string.spotify_import_copy)) }
                TextButton(
                    onClick = {
                        val send =
                            Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, summary.playlistName)
                                putExtra(Intent.EXTRA_TEXT, listText)
                            }
                        context.startActivity(Intent.createChooser(send, null))
                    },
                ) { Text(stringResource(R.string.spotify_import_share)) }
            }
        }
    }
}

private fun reasonText(
    context: Context,
    reason: NotFoundReason,
): String =
    context.getString(
        when (reason) {
            NotFoundReason.NoResults -> R.string.spotify_import_reason_no_results
            NotFoundReason.NoConfidentMatch -> R.string.spotify_import_reason_no_match
            NotFoundReason.OnlyExplicit -> R.string.spotify_import_reason_explicit
            NotFoundReason.OnlyVideo -> R.string.spotify_import_reason_video
            NotFoundReason.SearchFailed -> R.string.spotify_import_reason_failed
        },
    )

private fun notFoundText(
    context: Context,
    items: List<NotFoundTrack>,
): String =
    buildString {
        append(context.getString(R.string.spotify_import_not_found_header, items.size))
        items.forEach { item ->
            append('\n')
            append(item.track.display)
            if (item.reason != NotFoundReason.NoConfidentMatch) {
                append(" (")
                append(reasonText(context, item.reason))
                append(')')
            }
        }
    }
