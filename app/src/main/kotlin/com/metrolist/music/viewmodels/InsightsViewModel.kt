/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.metrolist.music.db.MusicDatabase
import com.metrolist.music.db.entities.Song
import com.metrolist.music.db.entities.SongWithStats
import com.metrolist.music.stats.InsightsPeriod
import com.metrolist.music.stats.InsightsRepository
import com.metrolist.music.stats.ListeningInsights
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import timber.log.Timber
import java.time.LocalDateTime
import javax.inject.Inject

/** A top song of the period: its stats, plus the library entry used to play it. */
data class InsightsSong(
    val stats: SongWithStats,
    val song: Song?,
)

sealed interface InsightsUiState {
    data object Loading : InsightsUiState

    data object Failed : InsightsUiState

    data class Ready(
        val insights: ListeningInsights,
        val topSongs: List<InsightsSong>,
    ) : InsightsUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class InsightsViewModel
    @Inject
    constructor(
        private val database: MusicDatabase,
        private val repository: InsightsRepository,
    ) : ViewModel() {
        val period = MutableStateFlow(InsightsPeriod.LAST_30_DAYS)

        val state: StateFlow<InsightsUiState> =
            period
                .mapLatest { load(it) }
                .flowOn(Dispatchers.IO)
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), InsightsUiState.Loading)

        fun selectPeriod(value: InsightsPeriod) {
            period.value = value
        }

        private suspend fun load(period: InsightsPeriod): InsightsUiState =
            try {
                val now = LocalDateTime.now()
                val insights = repository.load(period, now)
                val from = period.start(now)
                val stats = database.mostPlayedSongsStats(fromTimeStamp = from, limit = TOP_LIMIT, toTimeStamp = now).first()
                val songs =
                    database
                        .mostPlayedSongs(fromTimeStamp = from, limit = TOP_LIMIT, toTimeStamp = now)
                        .first()
                        .associateBy { it.id }
                InsightsUiState.Ready(
                    insights = insights,
                    topSongs = stats.map { InsightsSong(it, songs[it.id]) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Insights could not be loaded")
                InsightsUiState.Failed
            }

        companion object {
            const val TOP_LIMIT = 5
        }
    }
