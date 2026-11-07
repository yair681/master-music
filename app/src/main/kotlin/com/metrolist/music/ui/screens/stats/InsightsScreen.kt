/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.metrolist.music.LocalPlayerAwareWindowInsets
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.extensions.toMediaItem
import com.metrolist.music.playback.queues.ListQueue
import com.metrolist.music.stats.InsightsPeriod
import com.metrolist.music.stats.ListeningInsights
import com.metrolist.music.stats.ListeningStats
import com.metrolist.music.stats.TasteDna
import com.metrolist.music.ui.component.IconButton
import com.metrolist.music.ui.screens.intelligence.canOpenArtistPage
import com.metrolist.music.ui.screens.intelligence.listeningDuration
import com.metrolist.music.ui.utils.backToMain
import com.metrolist.music.ui.utils.resize
import com.metrolist.music.viewmodels.InsightsSong
import com.metrolist.music.viewmodels.InsightsUiState
import com.metrolist.music.viewmodels.InsightsViewModel
import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * Listening insights: when and how much the user listens, and a few measured habits.
 * Every number comes from the local listening history; nothing is sent anywhere.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    navController: NavController,
    viewModel: InsightsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val period by viewModel.period.collectAsStateWithLifecycle()
    val locale = currentLocale()
    val numberFormat = remember(locale) { NumberFormat.getIntegerInstance(locale) }

    LazyColumn(
        contentPadding = LocalPlayerAwareWindowInsets.current.asPaddingValues(),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "period") {
            PeriodSelector(period, viewModel::selectPeriod)
        }

        when (val ui = state) {
            InsightsUiState.Loading ->
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

            InsightsUiState.Failed ->
                item(key = "failed") {
                    MessageCard(stringResource(R.string.insights_failed), null)
                }

            is InsightsUiState.Ready -> {
                val insights = ui.insights
                if (insights.isEmpty) {
                    item(key = "empty") {
                        MessageCard(
                            stringResource(R.string.insights_empty_title),
                            stringResource(R.string.insights_empty_desc),
                        )
                    }
                } else {
                    item(key = "overview") { OverviewCard(insights, numberFormat) }
                    item(key = "hours") { HourChartCard(insights, numberFormat) }
                    item(key = "weekdays") { WeekdayChartCard(insights, numberFormat, locale) }
                    item(key = "dna") { DnaCard(insights, locale) }
                    if (insights.topArtists.isNotEmpty()) {
                        item(key = "top_artists") { TopArtistsCard(insights, navController) }
                    }
                    if (ui.topSongs.isNotEmpty()) {
                        item(key = "top_songs") { TopSongsCard(ui.topSongs, numberFormat) }
                    }
                }
                // The monthly and daily charts cover fixed windows, so they show for any period.
                if (insights.months.any { it.listens > 0 }) {
                    item(key = "months") { MonthChartCard(insights, numberFormat, locale) }
                }
                if (insights.days.any { it.listens > 0 }) {
                    item(key = "days") { DayChartCard(insights, numberFormat, locale) }
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(16.dp)) }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.insights_title)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain,
            ) {
                Icon(painterResource(R.drawable.arrow_back), contentDescription = null)
            }
        },
    )
}

@Composable
private fun currentLocale(): Locale {
    val configuration = LocalConfiguration.current
    return if (configuration.locales.isEmpty) Locale.getDefault() else configuration.locales[0]
}

@Composable
private fun PeriodSelector(
    selected: InsightsPeriod,
    onSelect: (InsightsPeriod) -> Unit,
) {
    val labels =
        listOf(
            InsightsPeriod.LAST_30_DAYS to stringResource(R.string.insights_period_30_days),
            InsightsPeriod.LAST_12_MONTHS to stringResource(R.string.insights_period_12_months),
            InsightsPeriod.ALL_TIME to stringResource(R.string.insights_period_all),
        )
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(labels, key = { it.first.name }) { (period, label) ->
            FilterChip(
                selected = period == selected,
                onClick = { onSelect(period) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun formatDuration(ms: Long): String {
    val duration = listeningDuration(ms)
    return if (duration.hours > 0) {
        stringResource(R.string.duration_hours_minutes, duration.hours.toInt(), duration.minutes.toInt())
    } else {
        stringResource(R.string.duration_minutes_only, duration.minutes.toInt())
    }
}

@Composable
private fun SectionCard(
    title: String? = null,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
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
        ) {
            if (title != null) {
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (subtitle != null) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            content()
        }
    }
}

@Composable
private fun MessageCard(
    title: String,
    description: String?,
) {
    SectionCard {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OverviewCard(
    insights: ListeningInsights,
    numberFormat: NumberFormat,
) {
    SectionCard {
        Text(
            text = stringResource(R.string.intelligence_listening_time),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatDuration(insights.totalPlayTimeMs),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(stringResource(R.string.intelligence_listens), numberFormat.format(insights.totalListens), Modifier.weight(1f))
            StatTile(stringResource(R.string.insights_songs), numberFormat.format(insights.distinctSongs), Modifier.weight(1f))
            StatTile(stringResource(R.string.insights_artists), numberFormat.format(insights.distinctArtists), Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            StatTile(stringResource(R.string.insights_new_songs), numberFormat.format(insights.newSongs), Modifier.weight(1f))
            StatTile(stringResource(R.string.intelligence_skips), numberFormat.format(insights.skips), Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One bar of a chart: its value, an optional axis label, and the text shown when it is tapped. */
private data class ChartBar(
    val value: Long,
    val axisLabel: String?,
    val detail: String,
)

@Composable
private fun barDetail(
    label: String,
    playTimeMs: Long,
    listens: Int,
    numberFormat: NumberFormat,
): String = stringResource(R.string.insights_bar_value, label, formatDuration(playTimeMs), numberFormat.format(listens))

@Composable
private fun HourChartCard(
    insights: ListeningInsights,
    numberFormat: NumberFormat,
) {
    val bars =
        insights.hours.map { bucket ->
            val label = hourLabel(bucket.bucket)
            ChartBar(
                value = bucket.playTimeMs,
                axisLabel = if (bucket.bucket % 6 == 0) label else null,
                detail = barDetail(label, bucket.playTimeMs, bucket.listens, numberFormat),
            )
        }
    SectionCard(title = stringResource(R.string.insights_by_hour)) {
        BarChart(bars, highlight = insights.dna.peakHour)
    }
}

@Composable
private fun WeekdayChartCard(
    insights: ListeningInsights,
    numberFormat: NumberFormat,
    locale: Locale,
) {
    val order = remember(locale) { ListeningStats.weekdayOrder(WeekFields.of(locale).firstDayOfWeek) }
    val byIndex = insights.weekdays.associateBy { it.bucket }
    val bars =
        order.map { index ->
            val bucket = byIndex.getValue(index)
            val day = ListeningStats.dayOfWeek(index)
            ChartBar(
                value = bucket.playTimeMs,
                axisLabel = day.getDisplayName(TextStyle.SHORT, locale),
                detail = barDetail(day.getDisplayName(TextStyle.FULL, locale), bucket.playTimeMs, bucket.listens, numberFormat),
            )
        }
    val peak = insights.dna.peakWeekday?.let { order.indexOf(it) }
    SectionCard(title = stringResource(R.string.insights_by_weekday)) {
        BarChart(bars, highlight = peak)
    }
}

@Composable
private fun MonthChartCard(
    insights: ListeningInsights,
    numberFormat: NumberFormat,
    locale: Locale,
) {
    val short = remember(locale) { DateTimeFormatter.ofPattern("LLL", locale) }
    val full = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }
    val last = insights.months.lastIndex
    val bars =
        insights.months.mapIndexed { index, bar ->
            ChartBar(
                value = bar.playTimeMs,
                axisLabel = if ((last - index) % 3 == 0) short.format(bar.month) else null,
                detail = barDetail(full.format(bar.month), bar.playTimeMs, bar.listens, numberFormat),
            )
        }
    SectionCard(title = stringResource(R.string.insights_by_month)) {
        BarChart(bars, highlight = null)
    }
}

@Composable
private fun DayChartCard(
    insights: ListeningInsights,
    numberFormat: NumberFormat,
    locale: Locale,
) {
    val short = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val full = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val last = insights.days.lastIndex
    val bars =
        insights.days.mapIndexed { index, bar ->
            ChartBar(
                value = bar.playTimeMs,
                axisLabel = if ((last - index) % 7 == 0) short.format(bar.date) else null,
                detail = barDetail(full.format(bar.date), bar.playTimeMs, bar.listens, numberFormat),
            )
        }
    SectionCard(title = stringResource(R.string.insights_by_day)) {
        BarChart(bars, highlight = null)
    }
}

private fun hourLabel(hour: Int): String = "%02d:00".format(Locale.ROOT, hour)

/**
 * A plain bar chart drawn with boxes. Bars follow the layout direction, so in RTL the first bar is
 * on the right. Tapping a bar shows its value above the chart.
 */
@Composable
private fun BarChart(
    bars: List<ChartBar>,
    highlight: Int?,
    height: Dp = 112.dp,
) {
    var selected by remember(bars) { mutableStateOf<Int?>(null) }
    val max = bars.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1L
    val primary = MaterialTheme.colorScheme.primary
    val muted = primary.copy(alpha = 0.4f)
    val empty = MaterialTheme.colorScheme.outlineVariant
    val gap = if (bars.size > 12) 2.dp else 6.dp

    Text(
        text = selected?.let { bars.getOrNull(it)?.detail } ?: stringResource(R.string.insights_chart_hint),
        style = MaterialTheme.typography.bodySmall,
        color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Row(
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().height(height),
    ) {
        bars.forEachIndexed { index, bar ->
            val isStrong = index == selected || (selected == null && index == highlight)
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = if (selected == index) null else index }
                        .semantics { contentDescription = bar.detail },
            ) {
                if (bar.value <= 0L) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .background(empty, RoundedCornerShape(1.dp)),
                    )
                } else {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((bar.value.toFloat() / max).coerceIn(0.03f, 1f))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(if (isStrong) primary else muted),
                    )
                }
            }
        }
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(gap),
        modifier = Modifier.fillMaxWidth(),
    ) {
        bars.forEach { bar ->
            Box(contentAlignment = Alignment.Center, modifier = Modifier.weight(1f)) {
                if (bar.axisLabel != null) {
                    Text(
                        text = bar.axisLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.wrapContentWidth(unbounded = true),
                    )
                }
            }
        }
    }
}

@Composable
private fun DnaCard(
    insights: ListeningInsights,
    locale: Locale,
) {
    val dna = insights.dna
    val percent = remember(locale) { NumberFormat.getPercentInstance(locale) }
    val topArtist = insights.topArtists.firstOrNull()?.name

    SectionCard(
        title = stringResource(R.string.insights_dna_title),
        subtitle = stringResource(R.string.insights_dna_desc),
    ) {
        DnaRatio(
            stringResource(R.string.insights_dna_top_artist),
            if (topArtist != null) stringResource(R.string.insights_dna_top_artist_desc, topArtist) else null,
            dna.topArtistShare,
            percent,
        )
        DnaRatio(stringResource(R.string.insights_dna_top_five), stringResource(R.string.insights_dna_top_five_desc), dna.topFiveArtistShare, percent)
        DnaRatio(stringResource(R.string.insights_dna_variety), stringResource(R.string.insights_dna_variety_desc), dna.artistVariety, percent)
        DnaRatio(stringResource(R.string.insights_dna_discovery), stringResource(R.string.insights_dna_discovery_desc), dna.discoveryRate, percent)
        DnaRatio(stringResource(R.string.insights_dna_repeat), stringResource(R.string.insights_dna_repeat_desc), dna.repeatRate, percent)
        DnaRatio(stringResource(R.string.insights_dna_completion), stringResource(R.string.insights_dna_completion_desc), dna.completionRate, percent)
        DnaRatio(stringResource(R.string.insights_dna_skip), stringResource(R.string.insights_dna_skip_desc), dna.skipRate, percent)
        DnaRatio(stringResource(R.string.insights_dna_liked), stringResource(R.string.insights_dna_liked_desc), dna.likedShare, percent)
        DnaText(
            stringResource(R.string.insights_dna_peak_hour),
            stringResource(R.string.insights_dna_peak_hour_desc),
            dna.peakHour?.let { hourLabel(it) },
        )
        DnaText(
            stringResource(R.string.insights_dna_peak_day),
            stringResource(R.string.insights_dna_peak_day_desc),
            dna.peakWeekday?.let { ListeningStats.dayOfWeek(it).getDisplayName(TextStyle.FULL, locale) },
        )
    }
}

@Composable
private fun DnaRatio(
    title: String,
    description: String?,
    value: Double?,
    percent: NumberFormat,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DnaHeader(title, value?.let { percent.format(it) })
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (value != null && value > 0.0) {
                Box(
                    Modifier
                        .fillMaxWidth(value.toFloat().coerceIn(0.02f, 1f))
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        if (description != null) DnaDescription(description)
    }
}

@Composable
private fun DnaText(
    title: String,
    description: String,
    value: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        DnaHeader(title, value)
        DnaDescription(description)
    }
}

@Composable
private fun DnaHeader(
    title: String,
    value: String?,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value ?: stringResource(R.string.insights_dna_not_enough),
            style = if (value != null) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
            fontWeight = if (value != null) FontWeight.SemiBold else null,
            color = if (value != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DnaDescription(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TopArtistsCard(
    insights: ListeningInsights,
    navController: NavController,
) {
    SectionCard(title = stringResource(R.string.intelligence_top_artists)) {
        insights.topArtists.take(InsightsTopLimit).forEach { artist ->
            val canOpen = canOpenArtistPage(artist.artistId)
            TopRow(
                title = artist.name,
                subtitle = formatDuration(artist.playTimeMs),
                thumbnailUrl = artist.thumbnailUrl,
                placeholder = R.drawable.artist,
                circle = true,
                onClick = if (canOpen) ({ navController.navigate("artist/${artist.artistId}") }) else null,
            )
        }
    }
}

@Composable
private fun TopSongsCard(
    songs: List<InsightsSong>,
    numberFormat: NumberFormat,
) {
    val playerConnection = LocalPlayerConnection.current
    val title = stringResource(R.string.intelligence_top_songs)
    val playable = songs.mapNotNull { it.song }
    SectionCard(title = title) {
        songs.forEach { entry ->
            val stats = entry.stats
            val subtitle =
                listOfNotNull(
                    stats.artistName,
                    stringResource(
                        R.string.intelligence_item_stats,
                        numberFormat.format(stats.songCountListened),
                        formatDuration(stats.timeListened ?: 0L),
                    ),
                ).joinToString(" · ")
            val song = entry.song
            TopRow(
                title = stats.title,
                subtitle = subtitle,
                thumbnailUrl = stats.thumbnailUrl,
                placeholder = R.drawable.music_note,
                circle = false,
                onClick =
                    if (playerConnection != null && song != null) {
                        {
                            playerConnection.playQueue(
                                ListQueue(
                                    title = title,
                                    items = playable.map { it.toMediaItem() },
                                    startIndex = playable.indexOf(song).coerceAtLeast(0),
                                ),
                            )
                        }
                    } else {
                        null
                    },
            )
        }
    }
}

@Composable
private fun TopRow(
    title: String,
    subtitle: String,
    thumbnailUrl: String?,
    placeholder: Int,
    circle: Boolean,
    onClick: (() -> Unit)?,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(vertical = 4.dp),
    ) {
        val shape = if (circle) CircleShape else RoundedCornerShape(8.dp)
        Box(
            contentAlignment = Alignment.Center,
            modifier =
                Modifier
                    .size(48.dp)
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            if (!thumbnailUrl.isNullOrBlank()) {
                AsyncImage(
                    model = thumbnailUrl.resize(144, 144),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    painter = painterResource(placeholder),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val InsightsTopLimit = 5
