package org.shiroumi.quant_kmp.feature.candle.presentation.sentiment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import model.candle.StrategySentimentResponse
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.feature.candle.data.repository.CandleRepositoryImpl
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.CompactChartCard
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.FeaturedSentimentCard
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.MarketSentimentFoundationCard
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.MomentumMetricsCard
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.SecondaryMetricsCard
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.SentimentHeader
import org.shiroumi.quant_kmp.feature.candle.presentation.sentiment.components.VolatilityMetricsCard
import org.shiroumi.quant_kmp.service.GlobalWebSocketClient
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.navigation.ProvideScrollableLargeMobileTitleBar

private const val INTRADAY_SNAPSHOT_OWNER = "sentiment"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SentimentScreen(
    viewModel: SentimentViewModel = viewModel {
        val repository = CandleRepositoryImpl(
            httpClient = HttpClientProvider.apiClient,
            baseUrl = org.shiroumi.config.AppConfig.apiBaseUrl
        )
        SentimentViewModel(repository)
    }
) {
    val history by viewModel.history.collectAsState()
    val currentSentiment by viewModel.currentSentiment.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val snapshotError by viewModel.snapshotError.collectAsState()
    val topAppBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(topAppBarState)
    val latestTradeDate = remember(history, currentSentiment) {
        history.withRealtimeSentiment(currentSentiment).lastOrNull()?.tradeDate
    }

    ProvideScrollableLargeMobileTitleBar(
        title = "市场情绪与策略水位",
        subtitle = latestTradeDate?.let { "最后更新 $it" },
        scrollBehavior = scrollBehavior,
    )

    LaunchedEffect(Unit) {
        println("[SentimentScreen] Screen displayed, subscribing to INTRADAY_SNAPSHOT")
        GlobalWebSocketClient.subscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
    }

    DisposableEffect(Unit) {
        onDispose {
            println("[SentimentScreen] Screen disposed, unsubscribing from INTRADAY_SNAPSHOT")
            GlobalWebSocketClient.unsubscribeIntradaySnapshot(INTRADAY_SNAPSHOT_OWNER)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> CircularProgressIndicator()
            history.isEmpty() && currentSentiment == null -> Text(
                snapshotError ?: "暂无数据",
                style = MaterialTheme.typography.bodyLarge,
                color = if (snapshotError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> SentimentContent(
                history = history,
                currentSentiment = currentSentiment,
                snapshotError = snapshotError
            )
        }
    }
}

@Composable
private fun SentimentContent(
    history: List<StrategySentimentResponse>,
    currentSentiment: StrategySentimentResponse?,
    snapshotError: String?,
) {
    val specs = remember { buildParameterSpecs() }
    val adaptiveConfig = rememberAdaptiveLayoutConfig()
    val displayHistory = remember(history, currentSentiment) {
        history.withRealtimeSentiment(currentSentiment)
    }

    val latest = displayHistory.lastOrNull()

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val gridAvailableWidth = if (maxWidth > 0.dp) maxWidth else adaptiveConfig.windowWidth
        val gridSpec = remember(gridAvailableWidth) { sentimentGridSpec(gridAvailableWidth) }
        val columns = gridSpec.columns
        val cardHeights = sentimentCardHeights(gridSpec)
        val wideSpan = wideCardSpan(columns)
        val mediumSpan = mediumCardSpan(columns)
        val narrowSpan = 1

        Column(
            modifier = Modifier.fillMaxSize().padding(gridSpec.pagePadding),
            verticalArrangement = Arrangement.spacedBy(titleContentGap(gridSpec))
        ) {
            // Compact / Medium 由 TopBar 承载标题；Expanded 及以上保留页内大字 Header
            if (gridSpec.widthClass != SentimentGridWidthClass.Compact &&
                !adaptiveConfig.isCompact && !adaptiveConfig.isMedium
            ) {
                SentimentHeader(latestTradeDate = latest?.tradeDate)
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = PaddingValues(),
                horizontalArrangement = Arrangement.spacedBy(gridSpec.gutter),
                verticalArrangement = Arrangement.spacedBy(gridSpec.gutter),
            ) {
                if (snapshotError != null) {
                    item(span = { GridItemSpan(columns) }) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            shape = MaterialTheme.shapes.medium
                        ) {
                            Text(
                                text = snapshotError,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(gridSpec.gutter),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }

                val secondaryIds = setOf("absolute_floor", "fft_score", "residual_score")
                val secondarySpecs = specs.filter { it.id in secondaryIds }

                val featuredSpec = specs.first { it.isFeatured }
                item(span = { GridItemSpan(wideSpan) }) {
                    FeaturedSentimentCard(
                        spec = featuredSpec,
                        history = displayHistory,
                        latest = latest,
                        cardHeight = cardHeights.featured,
                        compactLayout = columns == 1,
                    )
                }

                item(span = { GridItemSpan(mediumSpan) }) {
                    SecondaryMetricsCard(
                        specs = secondarySpecs,
                        latest = latest,
                        cardHeight = cardHeights.featured,
                    )
                }

                val foundationIds = setOf("bull_ratio", "ratio_norm")
                val foundationSpecs = specs.filter { it.id in foundationIds }
                val momentumIds = setOf("accel_z", "accel_score")
                val momentumSpecs = specs.filter { it.id in momentumIds }
                val volatilityIds = setOf("vol_z", "vol_score")
                val volatilitySpecs = specs.filter { it.id in volatilityIds }

                if (columns >= 3) {
                    item(span = { GridItemSpan(columns) }) {
                        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                            val unitWidth = (maxWidth - gridSpec.gutter * (columns - 1).toFloat()) / columns.toFloat()
                            val wideWidth = unitWidth * 2f + gridSpec.gutter

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(gridSpec.gutter)
                            ) {
                                Column(
                                    modifier = Modifier.width(wideWidth),
                                    verticalArrangement = Arrangement.spacedBy(gridSpec.gutter)
                                ) {
                                    MarketSentimentFoundationCard(
                                        bullRatioSpec = foundationSpecs.first { it.id == "bull_ratio" },
                                        ratioNormSpec = foundationSpecs.first { it.id == "ratio_norm" },
                                        history = displayHistory,
                                        latest = latest,
                                        cardHeight = cardHeights.foundation,
                                    )
                                    VolatilityMetricsCard(
                                        specs = volatilitySpecs,
                                        latest = latest,
                                        cardHeight = cardHeights.volatility,
                                        stacked = false,
                                        compactLayout = false,
                                    )
                                }

                                MomentumMetricsCard(
                                    specs = momentumSpecs,
                                    latest = latest,
                                    cardHeight = cardHeights.momentum,
                                    modifier = Modifier.width(unitWidth),
                                    compactLayout = true,
                                    stackDetailPanels = false,
                                )
                            }
                        }
                    }
                } else {
                    item(span = { GridItemSpan(wideSpan) }) {
                        MarketSentimentFoundationCard(
                            bullRatioSpec = foundationSpecs.first { it.id == "bull_ratio" },
                            ratioNormSpec = foundationSpecs.first { it.id == "ratio_norm" },
                            history = displayHistory,
                            latest = latest,
                            cardHeight = cardHeights.foundation,
                            stacked = columns == 1,
                        )
                    }

                    item(span = { GridItemSpan(mediumSpan) }) {
                        MomentumMetricsCard(
                            specs = momentumSpecs,
                            latest = latest,
                            cardHeight = cardHeights.momentum,
                            compactLayout = columns == 1,
                            stackDetailPanels = columns == 1,
                        )
                    }

                    item(span = { GridItemSpan(mediumSpan) }) {
                        VolatilityMetricsCard(
                            specs = volatilitySpecs,
                            latest = latest,
                            cardHeight = cardHeights.volatility,
                            stacked = columns == 1,
                            compactLayout = columns == 1,
                        )
                    }
                }

                val excludedIds = secondaryIds + foundationIds + momentumIds + volatilityIds + setOf("market_vol")
                val remainingSpecs = specs.filter { !it.isFeatured && it.id !in excludedIds }
                if (remainingSpecs.isNotEmpty()) {
                    items(
                        count = remainingSpecs.size,
                        key = { remainingSpecs[it].id },
                        span = { GridItemSpan(narrowSpan) },
                    ) { index ->
                        CompactChartCard(
                            spec = remainingSpecs[index],
                            history = displayHistory,
                            latest = latest,
                            cardHeight = cardHeights.compact,
                        )
                    }
                }
            }
        }
    }
}

private data class SentimentCardHeights(
    val featured: Dp,
    val foundation: Dp,
    val momentum: Dp,
    val volatility: Dp,
    val compact: Dp,
)

private data class SentimentGridSpec(
    val widthClass: SentimentGridWidthClass,
    val columns: Int,
    val pagePadding: PaddingValues,
    val gutter: Dp,
    val rowUnit: Dp,
)

private enum class SentimentGridWidthClass {
    Compact,
    Medium,
    Expanded,
    Large,
    XLarge,
}

private fun sentimentGridSpec(availableWidth: Dp): SentimentGridSpec = when {
    availableWidth < 600.dp -> SentimentGridSpec(
        widthClass = SentimentGridWidthClass.Compact,
        columns = 1,
        pagePadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
        gutter = 16.dp,
        rowUnit = 220.dp,
    )
    availableWidth < 840.dp -> SentimentGridSpec(
        widthClass = SentimentGridWidthClass.Medium,
        columns = 2,
        pagePadding = PaddingValues(24.dp),
        gutter = 16.dp,
        rowUnit = 212.dp,
    )
    availableWidth < 1200.dp -> SentimentGridSpec(
        widthClass = SentimentGridWidthClass.Expanded,
        columns = 3,
        pagePadding = PaddingValues(32.dp),
        gutter = 24.dp,
        rowUnit = 200.dp,
    )
    availableWidth < 1600.dp -> SentimentGridSpec(
        widthClass = SentimentGridWidthClass.Large,
        columns = 3,
        pagePadding = PaddingValues(40.dp),
        gutter = 24.dp,
        rowUnit = 200.dp,
    )
    else -> SentimentGridSpec(
        widthClass = SentimentGridWidthClass.XLarge,
        columns = 3,
        pagePadding = PaddingValues(40.dp),
        gutter = 24.dp,
        rowUnit = 200.dp,
    )
}

private fun wideCardSpan(columns: Int): Int = when (columns) {
    1 -> 1
    2 -> 2
    else -> 2
}

private fun mediumCardSpan(columns: Int): Int = when (columns) {
    1 -> 1
    2 -> 2
    else -> 1
}

private fun titleContentGap(spec: SentimentGridSpec): Dp = when (spec.widthClass) {
    SentimentGridWidthClass.Compact -> 20.dp
    SentimentGridWidthClass.Medium,
    SentimentGridWidthClass.Expanded,
    SentimentGridWidthClass.Large,
    SentimentGridWidthClass.XLarge -> 32.dp
}

private fun sentimentCardHeights(spec: SentimentGridSpec): SentimentCardHeights = when (spec.columns) {
    1 -> SentimentCardHeights(
        featured = 520.dp,
        foundation = 560.dp,
        momentum = 460.dp,
        volatility = 460.dp,
        compact = 220.dp,
    )
    2 -> SentimentCardHeights(
        featured = spec.rowUnit * 2f + spec.gutter,
        foundation = spec.rowUnit * 2f + spec.gutter,
        momentum = spec.rowUnit * 2f + spec.gutter,
        volatility = spec.rowUnit * 2f + spec.gutter,
        compact = 200.dp,
    )
    else -> SentimentCardHeights(
        featured = spec.rowUnit * 2f + spec.gutter,
        foundation = spec.rowUnit * 1.35f,
        momentum = spec.rowUnit * 1.35f + spec.rowUnit * 0.94f + spec.gutter,
        volatility = spec.rowUnit * 0.94f,
        compact = spec.rowUnit,
    )
}
