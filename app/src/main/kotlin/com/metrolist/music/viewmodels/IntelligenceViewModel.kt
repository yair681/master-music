/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.innertube.models.SongItem
import com.metrolist.music.constants.HideExplicitKey
import com.metrolist.music.constants.HideVideoSongsKey
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.taste.ArtistTaste
import com.metrolist.music.taste.Recommendation
import com.metrolist.music.taste.RecommendationFilters
import com.metrolist.music.taste.RecommendationRepository
import com.metrolist.music.taste.SongTaste
import com.metrolist.music.taste.TasteProfile
import com.metrolist.music.taste.TasteProfileRepository
import com.metrolist.music.utils.dataStore
import com.metrolist.music.utils.get
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/** A favorite song from the taste profile, with its library entry for title and artwork. */
data class IntelligenceSong(
    val song: Song,
    val taste: SongTaste,
)

/** A favorite artist from the taste profile, with the artwork stored in the library if any. */
data class IntelligenceArtist(
    val taste: ArtistTaste,
    val thumbnailUrl: String?,
)

/** Everything the intelligence screen shows, all of it read from the local taste profile. */
data class IntelligenceUiState(
    val isEmpty: Boolean,
    val totalListeningTimeMs: Long,
    val totalListens: Int,
    val totalSkips: Int,
    val completionRate: Double?,
    val topSongs: List<IntelligenceSong>,
    val topArtists: List<IntelligenceArtist>,
)

sealed interface IntelligenceMixState {
    data object Loading : IntelligenceMixState

    data object NotEnoughData : IntelligenceMixState

    data object Failed : IntelligenceMixState

    data class Ready(
        val items: List<Recommendation<SongItem>>,
    ) : IntelligenceMixState
}

@HiltViewModel
class IntelligenceViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val database: MusicDatabase,
        tasteProfiles: TasteProfileRepository,
        private val recommendations: RecommendationRepository,
    ) : ViewModel() {
        /** Null until the first profile is built. */
        @OptIn(ExperimentalCoroutinesApi::class)
        val state: StateFlow<IntelligenceUiState?> =
            tasteProfiles
                .profile()
                .mapLatest { profile -> toUiState(profile) }
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

        private val _dailyMix = MutableStateFlow<IntelligenceMixState>(IntelligenceMixState.Loading)
        val dailyMix: StateFlow<IntelligenceMixState> = _dailyMix.asStateFlow()

        private var mixJob: Job? = null

        init {
            loadDailyMix()
        }

        /** Builds the Daily Mix the same way Home does: the familiar part leaves out today's quick picks. */
        fun loadDailyMix() {
            mixJob?.cancel()
            mixJob =
                viewModelScope.launch(Dispatchers.IO) {
                    _dailyMix.value = IntelligenceMixState.Loading
                    _dailyMix.value =
                        try {
                            val profile = recommendations.profile()
                            if (!recommendations.hasEnoughData(profile)) {
                                IntelligenceMixState.NotEnoughData
                            } else {
                                val filters =
                                    RecommendationFilters(
                                        hideExplicit = context.dataStore.get(HideExplicitKey, false),
                                        hideVideoSongs = context.dataStore.get(HideVideoSongsKey, false),
                                    )
                                val quickPicks = recommendations.quickPicks(profile, filters)
                                val discovery =
                                    recommendations.discovery(
                                        profile = profile,
                                        filters = filters,
                                        shownQuickPicks = quickPicks.songs.mapTo(HashSet()) { it.id },
                                    )
                                if (discovery.dailyMix.isEmpty()) {
                                    IntelligenceMixState.NotEnoughData
                                } else {
                                    IntelligenceMixState.Ready(discovery.dailyMix)
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Timber.tag(TAG).w(e, "Daily Mix failed")
                            IntelligenceMixState.Failed
                        }
                }
        }

        private suspend fun toUiState(profile: TasteProfile): IntelligenceUiState {
            val favoriteSongs = profile.favoriteSongs.take(TOP_SONGS)
            val songsById = database.getSongsByIds(favoriteSongs.map { it.songId }).associateBy { it.id }
            val topSongs =
                favoriteSongs.mapNotNull { taste ->
                    songsById[taste.songId]?.let { IntelligenceSong(it, taste) }
                }
            val topArtists =
                profile.favoriteArtists.take(TOP_ARTISTS).map { taste ->
                    IntelligenceArtist(
                        taste = taste,
                        thumbnailUrl = database.artist(taste.artistId).first()?.artist?.thumbnailUrl,
                    )
                }
            return IntelligenceUiState(
                isEmpty = profile.isEmpty || profile.totalListens == 0,
                totalListeningTimeMs = profile.totalListeningTimeMs,
                totalListens = profile.totalListens,
                totalSkips = profile.totalSkips,
                completionRate = profile.completionRate,
                topSongs = topSongs,
                topArtists = topArtists,
            )
        }

        private companion object {
            const val TAG = "IntelligenceViewModel"
            const val TOP_SONGS = 10
            const val TOP_ARTISTS = 10
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
