@file:OptIn(ExperimentalSerializationApi::class, ExperimentalUuidApi::class)

package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.kamel.core.utils.URL
import io.kamel.image.KamelImage
import io.kamel.image.asyncPainterResource
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import model.Quant
import org.shiroumi.configs.BuildConfigs
import org.shiroumi.quant_kmp.createHttpClient
import org.shiroumi.quant_kmp.model.StrategyModel
import org.shiroumi.quant_kmp.showToast
import org.shiroumi.quant_kmp.ui.theme.ArrowBack
import kotlin.uuid.ExperimentalUuidApi

@ExperimentalSerializationApi
private val json = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    prettyPrint = true
    isLenient = true
    ignoreUnknownKeys = true
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.StrategyPage(
    quant: Quant,
    scope: AnimatedContentScope,
    onBackPressed: () -> Unit
) = Box(modifier = Modifier.fillMaxSize()) {
    val coroutineScope = rememberCoroutineScope { ioDispatcher() }
    var strategy by remember { mutableStateOf<StrategyModel?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth()
            .fillMaxHeight()
            .align(Alignment.TopCenter),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 64.dp)
    ) {
        stickyHeader {
            Box(
                modifier = Modifier
                    .width(640.dp)
                    .height(64.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(0.dp, 12.dp, 0.dp, 0.dp)
            ) {
                IconButton(onClick = onBackPressed) {
                    Icon(imageVector = ArrowBack, contentDescription = "Back")
                }
            }
        }

        item {
            Column(
                modifier = Modifier.width(640.dp).wrapContentHeight()
                    .padding(8.dp, 0.dp, 0.dp, 0.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "${quant.name}(${quant.code})",
                    fontSize = 28.sp,
                    modifier = Modifier.sharedBounds(
                        sharedContentState = rememberSharedContentState(quant.uuid),
                        animatedVisibilityScope = scope
                    )
                )
                Text(
                    text = "价格行为学交易策略分析",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        strategy?.let { s ->
            item { OverviewItem(modifier = Modifier.width(640.dp), s.baseInfo, s.summarise) }
            item { CandleImg(modifier = Modifier.width(640.dp), quant = quant) }
            s.keyPaSignal?.let { signal ->
                item {
                    Row(
                        modifier = Modifier.width(640.dp).wrapContentHeight(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        AreaItem(modifier = Modifier.weight(1f), area = signal.area)
                        CyclesItems(modifier = Modifier.weight(1f), cycle = signal.overview)
                    }
                }
                item { SignalCandleItem(modifier = Modifier.width(640.dp), signal = signal.signal) }
            }
            if (s.tradeStrategy.strategy.isNotEmpty()) {
                item { NamedDivider(modifier = Modifier.width(640.dp), name = "交易策略") }
                item {
                    StrategyItem(
                        modifier = Modifier.width(640.dp),
                        title = "备选策略1",
                        strategy = s.tradeStrategy.strategy[0]
                    )
                }

                item {
                    StrategyItem(
                        modifier = Modifier.width(640.dp),
                        title = "备选策略2",
                        strategy = s.tradeStrategy.strategy[1]
                    )
                }
            }
            item { NamedDivider(modifier = Modifier.width(640.dp), name = "风险提示") }
            item { RiskNotiItem(modifier = Modifier.width(640.dp), risk = s.attentionAndRisk[0]) }
            item { RiskNotiItem(modifier = Modifier.width(640.dp), risk = s.attentionAndRisk[1]) }
            item {
                Card(
                    modifier = Modifier.width(640.dp),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.primaryContainer),
                    shape = remember { RoundedCornerShape(36.dp) }
                ) {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp, 16.dp, 8.dp)) {
                        Text(
                            "以上结果均为AI生成，仅供参考",
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    DisposableEffect(quant) {
        val client = createHttpClient()
        coroutineScope.launch(ioDispatcher() + CoroutineExceptionHandler { _, t ->
            t.printStackTrace()
            coroutineScope.launch { showToast("fetch strategy failed.") }
        }) {
            val raw = client.get("/strategy?uuid=${quant.uuid}").bodyAsText()
            strategy = json.decodeFromString<StrategyModel>(raw)
        }
        onDispose {
        }
    }
}


@Composable
fun CandleImg(modifier: Modifier, quant: Quant) = Box(modifier = modifier.aspectRatio(2f)) {
    val color = MaterialTheme.colorScheme.surface.toArgb().toHexString()
    val primary = MaterialTheme.colorScheme.primary.toArgb().toHexString()
    val secondary = MaterialTheme.colorScheme.secondary.toArgb().toHexString()
    val url by remember {
        mutableStateOf(URL("${BuildConfigs.BASE_URL}:${BuildConfigs.PORT}/candleImg?ts_code=${quant.code}&bg_color=$color&primary=$primary&secondary=$secondary&date=${quant.targetDate}"))
    }

    val painter = asyncPainterResource(data = url) painter@{
        // 在协程中执行，通常用于CPU密集型操作
        coroutineContext = ioDispatcher()
    }

    KamelImage(
        resource = painter,
        contentDescription = "SVG from remote API",
        modifier = Modifier.fillMaxSize().padding(0.dp, 16.dp, 0.dp, 16.dp),
        onLoading = { CircularProgressIndicator() }, // Kamel内部的加载状态
        onFailure = { exception ->
            exception.printStackTrace()
            Text("Kamel failed to loa: ${exception.message}")
        },
        animationSpec = spring()
    )
}

@Composable
private fun NamedDivider(modifier: Modifier, name: String) = Box(modifier = modifier.wrapContentHeight()) {
    HorizontalDivider(modifier = Modifier.align(Alignment.Center))
    Text(
        text = name,
        modifier = Modifier.align(Alignment.Center).background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        color = MaterialTheme.colorScheme.primary
    )
}