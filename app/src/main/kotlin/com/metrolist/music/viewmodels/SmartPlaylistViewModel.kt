/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.PlaylistEntity
import com.metrolist.music.models.toMediaMetadata
import com.metrolist.music.taste.PlaylistRequest
import com.metrolist.music.taste.PlaylistRequestParser
import com.metrolist.music.taste.RecommendationFilters
import com.metrolist.music.taste.SmartPlaylistRepository
import com.metrolist.music.taste.SmartPlaylistResult
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import javax.inject.Inject

sealed interface SmartPlaylistUiState {
    data object Idle : SmartPlaylistUiState

    data object Loading : SmartPlaylistUiState

    data class Done(
        val result: SmartPlaylistResult,
    ) : SmartPlaylistUiState
}

@HiltViewModel
class SmartPlaylistViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        private val repository: SmartPlaylistRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow<SmartPlaylistUiState>(SmartPlaylistUiState.Idle)
        val state: StateFlow<SmartPlaylistUiState> = _state.asStateFlow()

        /** Real YouTube Music mood and genre titles; empty while loading or offline. */
        private val _categories = MutableStateFlow<List<String>>(emptyList())
        val categories: StateFlow<List<String>> = _categories.asStateFlow()

        private val _saved = MutableSharedFlow<String>(extraBufferCapacity = 1)

        /** Emits the playlist name once a generated playlist was saved. */
        val saved: SharedFlow<String> = _saved.asSharedFlow()

        private var job: Job? = null

        init {
            loadCategories()
        }

        fun loadCategories() {
            viewModelScope.launch {
                repository.categories().onSuccess { items -> _categories.value = items.map { it.title } }
            }
        }

        fun generate(text: String) {
            if (text.isBlank()) return
            run(PlaylistRequestParser.parse(text))
        }

        fun generateForCategory(title: String) {
            run(PlaylistRequest(text = title, category = title))
        }

        private fun run(request: PlaylistRequest) {
            job?.cancel()
            job =
                viewModelScope.launch {
                    _state.value = SmartPlaylistUiState.Loading
                    val filters =
                        withContext(Dispatchers.IO) {
                            RecommendationFilters(
                                hideExplicit = context.dataStore.get(HideExplicitKey, false),
                                hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false),
                            )
                        }
                    _state.value = SmartPlaylistUiState.Done(repository.generate(request, filters))
                    if (_categories.value.isEmpty()) loadCategories()
                }
        }

        /** Saves the current result as a local playlist (not synced to YouTube Music). */
        fun save() {
            val ready = (_state.value as? SmartPlaylistUiState.Done)?.result as? SmartPlaylistResult.Ready ?: return
            viewModelScope.launch(Dispatchers.IO) {
                val entity =
                    PlaylistEntity(
                        name = ready.title,
                        bookmarkedAt = LocalDateTime.now(),
                        isEditable = true,
                    )
                database.insert(entity)
                ready.songs.forEach { database.insert(it.song.payload.toMediaMetadata()) }
                val playlist = database.playlist(entity.id).first() ?: return@launch
                database.addSongsToPlaylist(playlist, ready.songs.map { it.song.id to null })
                _saved.emit(entity.name)
            }
        }
    }
