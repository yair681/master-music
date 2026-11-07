/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Playlist
import com.metrolist.music.importer.ExportifyCsv
import com.metrolist.music.importer.ExportifyFile
import com.metrolist.music.importer.ImportProgress
import com.metrolist.music.importer.ImportSummary
import com.metrolist.music.importer.ImportTarget
import com.metrolist.music.importer.MatchFilters
import com.metrolist.music.importer.SpotifyPlaylistImporter
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import com.metrolist.music.utils.reportException
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SpotifyImportUiState {
    data object Idle : SpotifyImportUiState

    data class Ready(
        val file: ExportifyFile,
        val suggestedName: String,
    ) : SpotifyImportUiState

    data class Running(
        val progress: ImportProgress,
    ) : SpotifyImportUiState

    data object Saving : SpotifyImportUiState

    data class Done(
        val summary: ImportSummary,
    ) : SpotifyImportUiState

    data object Cancelled : SpotifyImportUiState

    data class Failed(
        val reason: SpotifyImportFailure,
    ) : SpotifyImportUiState
}

enum class SpotifyImportFailure { Empty, SaveFailed }

/** Imports a Spotify playlist exported with Exportify. No Spotify account or API is used. */
@HiltViewModel
class SpotifyImportViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
    ) : ViewModel() {
        private val _state = MutableStateFlow<SpotifyImportUiState>(SpotifyImportUiState.Idle)
        val state: StateFlow<SpotifyImportUiState> = _state.asStateFlow()

        /** Local playlists that can be appended to (not YouTube Music playlists). */
        val editablePlaylists: StateFlow<List<Playlist>> =
            database
                .playlistsByNameAsc()
                .map { list -> list.filter { it.playlist.isEditable && it.playlist.browseId == null } }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        private val importer =
            SpotifyPlaylistImporter(
                database = database,
                search = { query ->
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).map { result ->
                        result.items.filterIsInstance<SongItem>()
                    }
                },
            )

        private var job: Job? = null

        /**
         * Reads the file. When it is an Exportify export, opens the import and returns true;
         * otherwise returns false and leaves the generic CSV import to handle it.
         */
        suspend fun open(uri: Uri): Boolean {
            val (text, fileName) =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader(Charsets.UTF_8).readText() }
                        text to displayName(uri)
                    }.getOrNull()
                } ?: return false
            if (text == null) return false
            val file = withContext(Dispatchers.Default) { ExportifyCsv.parse(text) } ?: return false
            _state.value =
                if (file.tracks.isEmpty()) {
                    SpotifyImportUiState.Failed(SpotifyImportFailure.Empty)
                } else {
                    SpotifyImportUiState.Ready(file, ExportifyCsv.playlistNameFromFileName(fileName))
                }
            return true
        }

        private fun displayName(uri: Uri): String? =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment

        fun start(target: ImportTarget) {
            val ready = _state.value as? SpotifyImportUiState.Ready ?: return
            val tracks = ready.file.tracks
            job?.cancel()
            job =
                viewModelScope.launch {
                    _state.value = SpotifyImportUiState.Running(ImportProgress(0, tracks.size, 0, 0))
                    val filters =
                        withContext(Dispatchers.IO) {
                            MatchFilters(
                                hideExplicit = context.dataStore.get(HideExplicitKey, false),
                                hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false),
                            )
                        }
                    val results =
                        try {
                            importer.matchAll(tracks, filters) { progress ->
                                _state.value = SpotifyImportUiState.Running(progress)
                            }
                        } catch (e: CancellationException) {
                            _state.value = SpotifyImportUiState.Cancelled
                            throw e
                        }
                    _state.value = SpotifyImportUiState.Saving
                    // Once searching is over the playlist is written in full, even if the
                    // screen closes, so a playlist is never left half-filled.
                    val summary =
                        withContext(NonCancellable) {
                            runCatching { importer.write(target, tracks, results, ready.file.duplicatesInFile) }
                                .onFailure { reportException(it) }
                                .getOrNull()
                        }
                    _state.value =
                        if (summary != null) {
                            SpotifyImportUiState.Done(summary)
                        } else {
                            SpotifyImportUiState.Failed(SpotifyImportFailure.SaveFailed)
                        }
                }
        }

        /** Stops searching. Nothing is written to any playlist. */
        fun cancel() {
            if (_state.value is SpotifyImportUiState.Running) {
                job?.cancel()
                _state.value = SpotifyImportUiState.Cancelled
            }
        }

        fun close() {
            job?.cancel()
            _state.value = SpotifyImportUiState.Idle
        }
    }
